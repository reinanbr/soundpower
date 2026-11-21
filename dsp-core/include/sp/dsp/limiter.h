// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "sp/dsp/types.h"

namespace sp::dsp {

/// Real-time-ready limiter settings.
struct LimiterDesign {
    double ceiling = 0.891250938;  ///< linear (-1 dBFS)
    std::uint32_t lookahead = 72;  ///< samples, >= 1; also the limiter's latency
    double releaseCoef = 0.0;      ///< one-pole coefficient per sample

    friend bool operator==(const LimiterDesign&, const LimiterDesign&) = default;
};

/// `params` must already be sanitized.
[[nodiscard]] LimiterDesign designLimiter(const LimiterParams& params, double sampleRate) noexcept;

/// Largest lookahead in samples that a design for `sampleRate` can request.
[[nodiscard]] std::uint32_t maxLookaheadSamples(double sampleRate) noexcept;

/// Stereo-linked lookahead peak limiter with a hard guarantee: no output sample exceeds the
/// ceiling.
///
/// For every frame it computes the gain that would bring the frame's peak down to the ceiling,
/// takes the minimum of that over a window of lookahead + 1 frames, lets the result recover
/// with the release time (never faster than the minimum allows), and smooths it with a moving
/// average of `lookahead` frames. The audio is delayed by `lookahead` frames. Every value
/// averaged for a given output frame is at most that frame's required gain, so the average is
/// too. A final clamp catches floating-point rounding and is counted for tests.
class Limiter {
public:
    /// Allocates for up to maxLookaheadSamples(sampleRate). Not real-time safe.
    void prepare(double sampleRate, std::size_t channels);

    /// Real-time safe. A lookahead change resets the delay line (and changes latency), so the
    /// caller should fade the output around it; ceiling and release changes are seamless.
    void setDesign(const LimiterDesign& design) noexcept;
    [[nodiscard]] const LimiterDesign& design() const noexcept { return design_; }

    /// Clears audio and gain history. Real-time safe.
    void reset() noexcept;

    /// Limits in place. Output is the input delayed by latency() frames.
    void process(double* const* channels, std::size_t numChannels, std::size_t frames) noexcept;

    [[nodiscard]] std::uint32_t latency() const noexcept { return design_.lookahead; }

    /// Lowest gain applied since the last call (1.0 = no reduction). Audio thread only.
    [[nodiscard]] double takeMinGain() noexcept;

    /// Number of samples the final safety clamp had to change. Stays 0 unless the algorithm's
    /// guarantee is broken; tests assert on it.
    [[nodiscard]] std::uint64_t safetyClampCount() const noexcept { return safetyClamps_; }

private:
    void recomputeBoxSum() noexcept;

    LimiterDesign design_{};
    std::size_t channels_ = 0;
    std::size_t capacity_ = 0;  // max lookahead + 1

    // Audio delay line, per channel.
    std::vector<double> delay_[kMaxChannels];
    std::size_t delayPos_ = 0;

    // Sliding minimum of the required gain (monotonic deque in a ring buffer).
    std::vector<double> dequeValue_;
    std::vector<std::uint64_t> dequeIndex_;
    std::size_t dequeHead_ = 0;
    std::size_t dequeSize_ = 0;

    // Release follower and moving average.
    double release_ = 1.0;
    std::vector<double> box_;
    std::size_t boxPos_ = 0;
    double boxSum_ = 0.0;
    std::uint32_t boxSinceRecompute_ = 0;

    std::uint64_t frame_ = 0;
    double minGain_ = 1.0;
    std::uint64_t safetyClamps_ = 0;
};

}  // namespace sp::dsp
