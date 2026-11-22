// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "instance.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <new>
#include <thread>

#include "global_state.h"
#include "soundpower/protocol.h"
#include "sp/dsp/format.h"
#include "sp/dsp/math.h"
#include "sp/dsp/processor.h"
#include "sp/dsp/smoothing.h"

namespace sp::fx {

namespace {

constexpr double kEnableFadeMs = 10.0;
constexpr double kVolumeRampMs = 10.0;  // same as the processor's volume stage
constexpr double kUnity824 = 16777216.0;  // 1.0 in 8.24 fixed point
constexpr std::size_t kBlock = dsp::kMaxBlockFrames;
constexpr std::size_t kBlockSamples = dsp::kMaxBlockFrames * dsp::kMaxChannels;
constexpr float kCpuAverageWeight = 0.05f;  // exponential average over ~20 process() calls

std::int16_t saturate16(std::int32_t v) noexcept {
    return static_cast<std::int16_t>(std::clamp<std::int32_t>(v, INT16_MIN, INT16_MAX));
}

float peakOf(const float* x, std::size_t n) noexcept {
    float peak = 0.0f;
    for (std::size_t i = 0; i < n; ++i) peak = std::max(peak, std::fabs(x[i]));
    return peak;  // NaN never wins std::max against a number, so a NaN input reads as silence
}

/// Single writer (the audio thread); the reader resets it with exchange(0).
void raisePeak(std::atomic<float>& peak, float value) noexcept {
    if (value > peak.load(std::memory_order_relaxed)) peak.store(value, std::memory_order_relaxed);
}

double peakDb(float peak) noexcept { return dsp::gainToDb(static_cast<double>(peak)); }

}  // namespace

struct Instance::Engine {
    StreamConfig config;
    dsp::Processor processor;
    dsp::TpdfDither dither;
    dsp::LinearRamp mix;  ///< 0 = input passes untouched, 1 = fully processed
    std::uint32_t fadeFrames = 1;
    /// Stream volume on the dry signal (pass-through and the dry half of the enable/disable
    /// fade), while this instance applies the volume. The processor ramps its own copy.
    std::array<dsp::LinearRamp, dsp::kMaxChannels> dryVolume{};
    std::uint32_t volumeRampFrames = 1;
    bool started = false;  ///< false until the first process(): the volume must not ramp in

    // Block scratch. `dry` holds the input, `wet` the processed (then mixed) signal.
    std::array<float, kBlockSamples> dry{};
    std::array<float, kBlockSamples> wet{};
    std::array<std::int16_t, kBlockSamples> pcm{};
};

Instance::Instance(GlobalState& global, std::int32_t sessionId, std::int32_t ioId) noexcept
    : global_(global), sessionId_(sessionId), ioId_(ioId) {}

Instance::~Instance() { retireEngine(engine_.exchange(nullptr, std::memory_order_seq_cst)); }

// ---- Control side --------------------------------------------------------------------------

int Instance::configure(const StreamConfig& config) {
    if (config.channels < 1 || config.channels > dsp::kMaxChannels) return -EINVAL;
    if (config.format != SampleFormat::Int16 && config.format != SampleFormat::Float) return -EINVAL;

    std::lock_guard lock(global_.mutex_);
    if (Engine* current = engine_.load(std::memory_order_relaxed);
        current != nullptr && current->config == config) {
        return 0;
    }

    auto* fresh = new (std::nothrow) Engine;
    if (fresh == nullptr) return -ENOMEM;
    fresh->config = config;
    fresh->processor.setParams(global_.params_);
    if (!fresh->processor.prepare(static_cast<double>(config.sampleRate), config.channels)) {
        delete fresh;
        return -EINVAL;
    }
    fresh->fadeFrames = static_cast<std::uint32_t>(
        std::max(1.0, std::round(kEnableFadeMs * config.sampleRate / 1000.0)));
    fresh->volumeRampFrames = static_cast<std::uint32_t>(
        std::max(1.0, std::round(kVolumeRampMs * config.sampleRate / 1000.0)));
    for (auto& g : fresh->dryVolume) g.reset(1.0);
    // A new engine starts dry and fades in, so a reconfiguration mid-stream doesn't click.
    fresh->mix.reset(0.0);

    retireEngine(engine_.exchange(fresh, std::memory_order_seq_cst));
    return 0;
}

void Instance::reset() {
    std::lock_guard lock(global_.mutex_);
    if (Engine* e = engine_.load(std::memory_order_relaxed)) e->processor.requestReset();
}

bool Instance::config(StreamConfig& out) const {
    std::lock_guard lock(global_.mutex_);
    const Engine* e = engine_.load(std::memory_order_relaxed);
    if (e == nullptr) return false;
    out = e->config;
    return true;
}

void Instance::applyParamsLocked(const dsp::ChainParams& params) {
    if (Engine* e = engine_.load(std::memory_order_relaxed)) e->processor.setParams(params);
}

dsp::StreamVolume Instance::streamVolume() const noexcept {
    const std::uint64_t packed = volume_.load(std::memory_order_relaxed);
    const auto gain = [](std::uint32_t v824) {
        return std::min(1.0, static_cast<double>(v824) / kUnity824);
    };
    dsp::StreamVolume v;
    v.apply = volumeApplied();
    v.atInput = volumeAtInput();
    v.left = gain(static_cast<std::uint32_t>(packed & 0xffffffffu));
    v.right = gain(static_cast<std::uint32_t>(packed >> 32));
    return v;
}

Instance::VolumeReport Instance::takeVolumeReport() {
    VolumeReport r;
    r.volume = streamVolume();
    {
        std::lock_guard lock(global_.mutex_);
        if (const Engine* e = engine_.load(std::memory_order_relaxed)) {
            r.stageGainDb = dsp::gainToDb(static_cast<double>(e->processor.volumeStageGain()));
        }
    }
    r.inputPeakDb = peakDb(inputPeak_.exchange(0.0f, std::memory_order_relaxed));
    r.outputPeakDb = peakDb(outputPeak_.exchange(0.0f, std::memory_order_relaxed));
    return r;
}

Status Instance::statusLocked() {
    Status s;
    s.sessionId = sessionId_;
    Engine* e = engine_.load(std::memory_order_relaxed);
    const bool on = enabled();
    if (e == nullptr) {
        s.state = SP_STATE_UNINITIALIZED;
    } else {
        s.sampleRate = e->config.sampleRate;
        s.channels = e->config.channels;
        s.format = e->config.format == SampleFormat::Int16 ? SP_FORMAT_PCM_16_BIT : SP_FORMAT_PCM_FLOAT;
        if (on) {
            s.state = SP_STATE_ACTIVE;
        } else if (audioState_.load(std::memory_order_relaxed) == AudioState::FadingOut) {
            s.state = SP_STATE_DISABLING;
        } else {
            s.state = SP_STATE_INITIALIZED;
        }
        s.limiterGrDb = e->processor.limiterGainReductionDb();
        if (e->processor.takeNonFiniteFlag()) s.flags |= SP_STATUS_FLAG_NONFINITE_INPUT;
    }
    if (duplicate()) s.flags |= SP_STATUS_FLAG_DUPLICATE_SESSION;
    s.cpuAvgPercent = cpuAvgPercent_.load(std::memory_order_relaxed);
    s.cpuPeakPercent = cpuPeakPercent_.exchange(0.0f, std::memory_order_relaxed);
    s.framesProcessed = framesProcessed_.load(std::memory_order_relaxed);
    return s;
}

// ---- Engine hand-over ----------------------------------------------------------------------

Instance::Engine* Instance::acquireEngine() noexcept {
    // Single-reader hazard pointer. Publishing the hazard and re-checking engine_ (both
    // seq_cst) guarantees that retireEngine() either sees our hazard or we see its new engine.
    Engine* e = engine_.load(std::memory_order_seq_cst);
    for (;;) {
        inUse_.store(e, std::memory_order_seq_cst);
        Engine* again = engine_.load(std::memory_order_seq_cst);
        if (again == e) return e;
        e = again;
    }
}

void Instance::retireEngine(Engine* old) noexcept {
    if (old == nullptr) return;
    // At most one process() call can still hold it; that takes well under a millisecond.
    while (inUse_.load(std::memory_order_seq_cst) == old) {
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
    delete old;
}

// ---- Audio thread --------------------------------------------------------------------------

int Instance::process(const void* in, void* out, std::size_t frames) noexcept {
    Engine* e = acquireEngine();
    if (e == nullptr) {
        releaseEngine();
        return -EINVAL;
    }
    const auto start = std::chrono::steady_clock::now();
    const StreamConfig& c = e->config;
    const std::size_t nch = c.channels;

    // ---- Enable / disable / duplicate guard: all of them just move the dry↔processed mix.
    const bool on = enabled();
    const bool wanted = on && !duplicate();
    const double target = wanted ? 1.0 : 0.0;
    if (e->mix.target() != target) {
        if (wanted && !e->mix.ramping() && e->mix.current() == 0.0) {
            // Resuming from pass-through: the processor's history is stale.
            e->processor.requestReset();
        }
        e->mix.setTarget(target, e->fadeFrames);
    }
    const bool processing = e->mix.ramping() || e->mix.current() != 0.0;

    // ---- Stream volume (direct volume control). The processor applies it to what it
    // processes; the dry signal gets it here, so that no path leaves at the mixer's full scale.
    const dsp::StreamVolume volume = streamVolume();
    const bool instantVolume = !e->started;
    e->started = true;
    e->processor.setStreamVolume(volume, instantVolume);
    bool scaleDry = false;
    for (std::size_t ch = 0; ch < nch; ++ch) {
        double gain = 1.0;
        if (volume.apply) {
            gain = nch == 1 ? std::max(volume.left, volume.right)
                            : (ch == 0 ? volume.left : volume.right);
        }
        dsp::LinearRamp& g = e->dryVolume[ch];
        if (instantVolume) {
            g.reset(gain);
        } else if (g.target() != gain) {
            g.setTarget(gain, e->volumeRampFrames);
        }
        if (g.ramping() || g.current() != 1.0) scaleDry = true;
    }
    float inPeak = 0.0f;
    float outPeak = 0.0f;

    for (std::size_t done = 0; done < frames;) {
        const std::size_t n = std::min(kBlock, frames - done);
        const std::size_t samples = n * nch;
        const std::size_t offset = done * nch;
        float* y = e->dry.data();

        if (c.format == SampleFormat::Float) {
            std::memcpy(e->dry.data(), static_cast<const float*>(in) + offset, samples * sizeof(float));
        } else {
            dsp::int16ToFloat(static_cast<const std::int16_t*>(in) + offset, e->dry.data(), samples);
        }
        inPeak = std::max(inPeak, peakOf(e->dry.data(), samples));

        if (processing) {
            std::memcpy(e->wet.data(), e->dry.data(), samples * sizeof(float));
            e->processor.process(e->wet.data(), e->wet.data(), n);
            if (e->mix.ramping() || e->mix.current() != 1.0) {
                for (std::size_t f = 0; f < n; ++f) {
                    const auto m = static_cast<float>(e->mix.next());
                    for (std::size_t ch = 0; ch < nch; ++ch) {
                        const std::size_t i = f * nch + ch;
                        const float g =
                            scaleDry ? static_cast<float>(e->dryVolume[ch].next()) : 1.0f;
                        const float d = e->dry[i] * g;
                        const float v = d + m * (e->wet[i] - d);
                        // The processor already silences non-finite input; don't let the dry
                        // half of the mix bring it back.
                        e->wet[i] = std::isfinite(v) ? v : 0.0f;
                    }
                }
            } else if (scaleDry) {
                for (std::size_t f = 0; f < n; ++f) {
                    for (std::size_t ch = 0; ch < nch; ++ch) (void)e->dryVolume[ch].next();
                }
            }
            y = e->wet.data();
        } else if (scaleDry) {
            for (std::size_t f = 0; f < n; ++f) {
                for (std::size_t ch = 0; ch < nch; ++ch) {
                    e->dry[f * nch + ch] *= static_cast<float>(e->dryVolume[ch].next());
                }
            }
        }
        const bool rewritten = processing || scaleDry;  // y differs from the input
        outPeak = std::max(outPeak, peakOf(y, samples));

        if (c.format == SampleFormat::Float) {
            float* o = static_cast<float*>(out) + offset;
            if (c.accumulate) {
                for (std::size_t i = 0; i < samples; ++i) o[i] += y[i];
            } else if (rewritten || o != static_cast<const float*>(in) + offset) {
                std::memcpy(o, y, samples * sizeof(float));
            }
        } else {
            std::int16_t* o = static_cast<std::int16_t*>(out) + offset;
            const std::int16_t* x = static_cast<const std::int16_t*>(in) + offset;
            if (!rewritten) {
                // Pass-through stays bit-exact: copy the integers, don't round-trip through float.
                if (c.accumulate) {
                    for (std::size_t i = 0; i < samples; ++i) o[i] = saturate16(o[i] + x[i]);
                } else if (o != x) {
                    std::memmove(o, x, samples * sizeof(std::int16_t));
                }
            } else if (c.accumulate) {
                dsp::floatToInt16(y, e->pcm.data(), samples, e->dither);
                for (std::size_t i = 0; i < samples; ++i) o[i] = saturate16(o[i] + e->pcm[i]);
            } else {
                dsp::floatToInt16(y, o, samples, e->dither);
            }
        }
        done += n;
    }

    raisePeak(inputPeak_, inPeak);
    raisePeak(outputPeak_, outPeak);
    const bool idle = !e->mix.ramping() && e->mix.current() == 0.0;
    audioState_.store(idle ? AudioState::Idle : (wanted ? AudioState::Running : AudioState::FadingOut),
                      std::memory_order_relaxed);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    updateStats(std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count(), frames,
                c.sampleRate, processing);
    releaseEngine();
    return (!on && idle) ? -ENODATA : 0;
}

void Instance::updateStats(std::int64_t elapsedNs, std::size_t frames, std::uint32_t sampleRate,
                           bool processed) noexcept {
    if (frames == 0 || sampleRate == 0) return;
    if (processed) framesProcessed_.fetch_add(frames, std::memory_order_relaxed);
    const double budgetNs = static_cast<double>(frames) * 1e9 / static_cast<double>(sampleRate);
    const auto load = static_cast<float>(100.0 * static_cast<double>(elapsedNs) / budgetNs);
    const float avg = cpuAvgPercent_.load(std::memory_order_relaxed);
    cpuAvgPercent_.store(avg + kCpuAverageWeight * (load - avg), std::memory_order_relaxed);
    if (load > cpuPeakPercent_.load(std::memory_order_relaxed)) {
        cpuPeakPercent_.store(load, std::memory_order_relaxed);
    }
}

}  // namespace sp::fx
