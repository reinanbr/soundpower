// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Stereo stage: balance, width (mid/side) and mono downmix as one 2x2 matrix.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "sp/dsp/smoothing.h"
#include "sp/dsp/types.h"

namespace sp::dsp {

/// out_L = ll * L + lr * R,  out_R = rl * L + rr * R.
struct StereoDesign {
    double ll = 1.0;
    double lr = 0.0;
    double rl = 0.0;
    double rr = 1.0;

    [[nodiscard]] bool identity() const noexcept {
        return ll == 1.0 && lr == 0.0 && rl == 0.0 && rr == 1.0;
    }
    friend bool operator==(const StereoDesign&, const StereoDesign&) = default;
};

/// Width w scales the side signal: L' = M + w S, R' = M - w S with M = (L + R) / 2 and
/// S = (L - R) / 2; mono uses w = 0. Balance then attenuates the opposite channel linearly
/// (balance +0.5 halves the left channel). `params` must already be sanitized.
[[nodiscard]] StereoDesign designStereo(const StereoParams& params) noexcept;

/// Applies a StereoDesign to stereo audio, ramping the matrix on every change. Real-time safe.
class StereoStage {
public:
    /// Sets the ramp length used by later non-instant changes.
    void prepare(std::uint32_t rampFrames) noexcept { rampFrames_ = rampFrames; }

    void setDesign(const StereoDesign& design, bool instant) noexcept;

    /// Processes channels 0 and 1 in place. Does nothing for fewer than two channels, or when
    /// the matrix is the identity and not ramping.
    void process(double* const* channels, std::size_t numChannels, std::size_t frames) noexcept;

private:
    std::array<LinearRamp, 4> m_{};  // ll, lr, rl, rr
    std::uint32_t rampFrames_ = 0;
    bool identity_ = true;
};

}  // namespace sp::dsp
