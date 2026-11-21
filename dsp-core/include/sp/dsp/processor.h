// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "sp/dsp/compressor.h"
#include "sp/dsp/limiter.h"
#include "sp/dsp/parametric_eq.h"
#include "sp/dsp/smoothing.h"
#include "sp/dsp/stereo.h"
#include "sp/dsp/triple_buffer.h"
#include "sp/dsp/types.h"

namespace sp::dsp {

/// Everything the audio thread needs for one parameter update, designed off-thread.
struct RtParams {
    EqDesign eq{};
    std::uint64_t eqSerial = 0;  ///< changes only when the EQ design changes
    double preampGain = 1.0;     ///< linear, auto-preamp included
    StereoDesign stereo{};
    CompressorDesign compressor{};
    LimiterDesign limiter{};
    bool bypass = false;
};

/// The complete SoundPower chain for one stream: preamp → parametric EQ and tone shelves →
/// stereo stage → compressor → limiter, with a latency-aligned dry path for click-free A/B
/// bypass.
///
/// Threading contract:
///  - prepare() is control-side and must never run concurrently with process().
///  - setParams() is control-side with a single writer at a time (callers serialize it), and
///    may run concurrently with process().
///  - requestReset() may be called from any thread.
///  - process() runs on the audio thread: no allocation, locks, logging or I/O.
class Processor {
public:
    Processor();

    // ---- Control side --------------------------------------------------------------------

    /// Allocates for the given format and applies the current parameters immediately (no
    /// transitions). Returns false, and leaves the processor in pass-through, if the format is
    /// unsupported (channels outside 1..kMaxChannels, or a sample rate outside 8–384 kHz).
    bool prepare(double sampleRate, std::size_t channels);

    /// Publishes new parameters. The audio thread picks them up at its next block and moves to
    /// them without clicks: preamp ramps over 10 ms, a new EQ or tone curve crossfades over
    /// 10 ms, the stereo matrix and bypass ramp over 20 ms, compressor changes follow its
    /// attack and release, turning the limiter off lets its gain recover with the release
    /// time, and a lookahead change fades the output out and in over 2 ms each way. Can be
    /// called before prepare(); the values are applied there.
    void setParams(const ChainParams& params);

    /// The parameters last passed to setParams() (unsanitized).
    [[nodiscard]] const ChainParams& params() const noexcept { return requested_; }

    /// Preamp actually applied by the latest design: user preamp plus auto-preamp, in dB.
    [[nodiscard]] double effectivePreampDb() const noexcept { return effectivePreampDb_; }

    [[nodiscard]] bool prepared() const noexcept { return prepared_; }
    [[nodiscard]] double sampleRate() const noexcept { return sampleRate_; }
    [[nodiscard]] std::size_t channels() const noexcept { return channels_; }

    // ---- Any thread ----------------------------------------------------------------------

    /// Asks the audio thread to clear all history and jump to the latest parameters at the
    /// start of its next block.
    void requestReset() noexcept { resetRequested_.store(true, std::memory_order_release); }

    /// Current processing latency in frames (the limiter lookahead).
    [[nodiscard]] std::uint32_t latencyFrames() const noexcept {
        return latency_.load(std::memory_order_relaxed);
    }

    /// Limiter gain reduction over the last block, in dB (0 or negative).
    [[nodiscard]] float limiterGainReductionDb() const noexcept {
        return limiterGrDb_.load(std::memory_order_relaxed);
    }

    /// True if a non-finite value appeared since the last call. The block that contained it
    /// is replaced by silence and the filters are reset.
    [[nodiscard]] bool takeNonFiniteFlag() noexcept {
        return nonFinite_.exchange(false, std::memory_order_relaxed);
    }

    // ---- Audio thread --------------------------------------------------------------------

    /// Processes interleaved float frames. `in` and `out` may be the same buffer.
    /// Before a successful prepare() this does nothing (the channel count is unknown); the
    /// caller is responsible for pass-through in that state.
    void process(const float* in, float* out, std::size_t frames) noexcept;

    /// For tests: see Limiter::safetyClampCount(). Audio thread only.
    [[nodiscard]] std::uint64_t limiterSafetyClampCount() const noexcept {
        return limiter_.safetyClampCount();
    }

private:
    /// Output fade around a latency change: fade out, hold silence while the new delay lines
    /// fill, fade in.
    enum class Fade : std::uint8_t { None, Out, Hold, In };

    RtParams design(const ChainParams& params);
    void applyParams(const RtParams& params, bool instant) noexcept;
    void resetAudioState() noexcept;
    void setDryDelay(std::uint32_t frames) noexcept;
    void processBlock(const float* in, float* out, std::size_t frames) noexcept;

    // Format (written by prepare() only).
    double sampleRate_ = 0.0;
    std::size_t channels_ = 0;
    bool prepared_ = false;
    std::uint32_t preampRampFrames_ = 0;
    std::uint32_t bypassRampFrames_ = 0;
    std::uint32_t eqCrossfadeFrames_ = 0;
    std::uint32_t fadeFrames_ = 0;

    // Control side.
    ChainParams requested_{};
    RtParams lastDesign_{};
    std::uint64_t eqSerialCounter_ = 0;
    double effectivePreampDb_ = 0.0;

    // Control → audio.
    TripleBuffer<RtParams> pending_;
    std::atomic<bool> resetRequested_{false};

    // Audio side.
    RtParams current_{};
    std::array<ParametricEq, 2> banks_{};
    std::size_t activeBank_ = 0;
    bool eqTransition_ = false;
    LinearRamp eqCrossfade_;
    /// During an EQ transition the outgoing bank keeps this preamp and the incoming bank gets
    /// preamp_.current(), so preamp and curve change as one crossfade (no gain overshoot).
    double transitionPreampFrom_ = 1.0;
    LinearRamp preamp_;
    LinearRamp wet_;
    LinearRamp fade_;
    Fade fadeState_ = Fade::None;
    std::uint32_t fadeHoldFrames_ = 0;
    StereoStage stereo_;
    Compressor compressor_;
    LimiterDesign pendingLimiter_{};
    Limiter limiter_;

    std::array<std::vector<double>, kMaxChannels> dryDelay_{};
    std::size_t dryCapacity_ = 0;
    std::size_t dryPos_ = 0;
    std::uint32_t dryDelayFrames_ = 0;

    using BlockBuffer = std::array<std::array<double, kMaxBlockFrames>, kMaxChannels>;
    BlockBuffer wetA_{};
    BlockBuffer wetB_{};
    BlockBuffer dry_{};

    // Audio → anyone.
    std::atomic<std::uint32_t> latency_{0};
    std::atomic<float> limiterGrDb_{0.0f};
    std::atomic<bool> nonFinite_{false};
};

}  // namespace sp::dsp
