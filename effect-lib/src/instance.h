// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// One effect instance, i.e. one audio stream: the format-handling and enable/disable layer
// around a dsp::Processor. No Android headers: the AELI glue in src/android/ translates
// effect_config_t and audio_buffer_t into the plain types used here.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "codec.h"
#include "sp/dsp/types.h"

namespace sp::fx {

class GlobalState;

enum class SampleFormat : std::uint8_t { Int16, Float };

struct StreamConfig {
    std::uint32_t sampleRate = 48000;
    std::uint32_t channels = 2;
    SampleFormat format = SampleFormat::Float;
    bool accumulate = false;  ///< output += processed, instead of output = processed

    bool operator==(const StreamConfig&) const = default;
};

/// Created and destroyed through GlobalState, which owns the registry.
///
/// Threading:
///  - configure(), setEnabled(), reset(): control side (binder threads), any number of them.
///  - setDevice(), setStreamVolume(): any thread, lock-free (AudioFlinger may call them from its
///    mixer thread).
///  - process(): the audio thread. No allocation, locks, logging or I/O. It may run at the same
///    time as any control call, including configure(): engines are swapped with a hazard
///    pointer, and the control side waits for the audio thread to let go of the old one.
class Instance {
public:
    Instance(const Instance&) = delete;
    Instance& operator=(const Instance&) = delete;

    [[nodiscard]] std::int32_t sessionId() const noexcept { return sessionId_; }
    [[nodiscard]] std::int32_t ioId() const noexcept { return ioId_; }

    // ---- Control side ----------------------------------------------------------------------

    /// Builds an engine for `config` with the current global parameters and swaps it in.
    /// Returns 0, -EINVAL for an unsupported format, or -ENOMEM. Re-applying the current
    /// configuration is a no-op, so repeated SET_CONFIG commands don't restart processing.
    int configure(const StreamConfig& config);

    /// Enabling fades the processed signal in over 10 ms; disabling fades back to the input,
    /// after which process() returns -ENODATA.
    void setEnabled(bool enabled) noexcept { enabled_.store(enabled, std::memory_order_release); }
    [[nodiscard]] bool enabled() const noexcept { return enabled_.load(std::memory_order_acquire); }

    /// Clears all signal history at the start of the next block.
    void reset();

    /// The current configuration; `false` before the first successful configure().
    bool config(StreamConfig& out) const;

    // ---- Any thread --------------------------------------------------------------------------

    void setDevice(std::uint32_t device) noexcept { device_.store(device, std::memory_order_relaxed); }
    [[nodiscard]] std::uint32_t device() const noexcept {
        return device_.load(std::memory_order_relaxed);
    }
    /// Stream volume per channel, 8.24 fixed point, as sent by EFFECT_CMD_SET_VOLUME. With
    /// `apply` this instance told the mixer it applies the volume itself (direct volume
    /// control), so every path out of process() carries it, pass-through included.
    void setStreamVolume(std::uint32_t left, std::uint32_t right, bool apply) noexcept {
        volume_.store(std::uint64_t{left} | (std::uint64_t{right} << 32),
                      std::memory_order_relaxed);
        volumeApplied_.store(apply, std::memory_order_release);
    }
    [[nodiscard]] bool volumeApplied() const noexcept {
        return volumeApplied_.load(std::memory_order_acquire);
    }
    /// The last stream volume received, as linear gains (clamped to 0..1).
    [[nodiscard]] dsp::StreamVolume streamVolume() const noexcept;

    /// For DUMP: the volume, the gain of the chain's volume stage, and the input and output
    /// peaks since the previous call (in dBFS, -inf for silence).
    struct VolumeReport {
        dsp::StreamVolume volume;
        double stageGainDb = 0.0;
        double inputPeakDb = 0.0;
        double outputPeakDb = 0.0;
    };
    [[nodiscard]] VolumeReport takeVolumeReport();
    /// True while the duplicate-session guard keeps this instance in pass-through.
    [[nodiscard]] bool duplicate() const noexcept {
        return duplicate_.load(std::memory_order_acquire);
    }

    // ---- Audio thread --------------------------------------------------------------------

    /// Processes `frames` interleaved frames in the configured format. `in` and `out` may be the
    /// same buffer, but must not otherwise overlap. Returns 0 while the effect is active,
    /// -ENODATA once it is disabled and has faded out (the input is copied through, and the
    /// caller may stop calling), or -EINVAL before configure().
    int process(const void* in, void* out, std::size_t frames) noexcept;

private:
    friend class GlobalState;
    struct Engine;

    /// What the audio thread is doing, for STATUS.
    enum class AudioState : std::uint32_t { Idle, Running, FadingOut };

    Instance(GlobalState& global, std::int32_t sessionId, std::int32_t ioId) noexcept;
    ~Instance();

    // Called by GlobalState with its mutex held.
    void applyParamsLocked(const dsp::ChainParams& params);
    void setDuplicateLocked(bool duplicate) noexcept {
        duplicate_.store(duplicate, std::memory_order_release);
    }
    [[nodiscard]] Status statusLocked();

    Engine* acquireEngine() noexcept;
    void releaseEngine() noexcept { inUse_.store(nullptr, std::memory_order_release); }
    /// `old` is no longer reachable through engine_; waits until the audio thread is not using
    /// it, then frees it.
    void retireEngine(Engine* old) noexcept;

    void updateStats(std::int64_t elapsedNs, std::size_t frames, std::uint32_t sampleRate,
                     bool processed) noexcept;

    GlobalState& global_;
    const std::int32_t sessionId_;
    const std::int32_t ioId_;

    std::atomic<Engine*> engine_{nullptr};  ///< written under the global mutex only
    std::atomic<Engine*> inUse_{nullptr};   ///< hazard pointer, written by the audio thread
    std::atomic<bool> enabled_{false};
    std::atomic<bool> duplicate_{false};
    std::atomic<std::uint32_t> device_{0};
    std::atomic<std::uint64_t> volume_{(std::uint64_t{1} << 24) | (std::uint64_t{1} << 56)};
    std::atomic<bool> volumeApplied_{false};

    // Audio thread → control side.
    std::atomic<AudioState> audioState_{AudioState::Idle};
    std::atomic<float> cpuAvgPercent_{0.0f};
    std::atomic<float> cpuPeakPercent_{0.0f};
    std::atomic<std::uint64_t> framesProcessed_{0};
    std::atomic<float> inputPeak_{0.0f};   ///< max |sample| since the last report
    std::atomic<float> outputPeak_{0.0f};

    static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
    static_assert(std::atomic<float>::is_always_lock_free);
    static_assert(std::atomic<Engine*>::is_always_lock_free);
};

}  // namespace sp::fx
