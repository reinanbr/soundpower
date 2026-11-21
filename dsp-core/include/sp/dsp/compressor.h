// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Feed-forward, stereo-linked downward compressor with a soft knee.
#pragma once

#include <cstddef>

#include "sp/dsp/types.h"

namespace sp::dsp {

/// Real-time-ready compressor settings.
struct CompressorDesign {
    bool enabled = false;
    double thresholdDb = -18.0;
    double slope = 2.0 / 3.0;  ///< 1 - 1 / ratio
    double kneeDb = 6.0;
    double makeupDb = 0.0;
    double attackCoef = 0.0;   ///< one-pole coefficients per sample
    double releaseCoef = 0.0;

    friend bool operator==(const CompressorDesign&, const CompressorDesign&) = default;
};

/// `params` must already be sanitized.
[[nodiscard]] CompressorDesign designCompressor(const CompressorParams& params,
                                                double sampleRate) noexcept;

/// The static curve: gain reduction in dB (>= 0) for a detector level in dBFS. Below
/// threshold - knee / 2 there is none; above threshold + knee / 2 the output rises by 1 / ratio
/// dB per dB; in between a quadratic joins the two.
[[nodiscard]] double compressorReductionDb(const CompressorDesign& design,
                                           double levelDb) noexcept;

/// For each frame the detector takes the largest absolute sample of all channels, the static
/// curve gives the target gain (makeup minus reduction), and the applied gain moves toward it
/// in dB with the attack time when it falls and the release time when it rises. Turning the
/// compressor off releases to 0 dB the same way, so no setting change clicks.
/// All methods are real-time safe.
class Compressor {
public:
    void setDesign(const CompressorDesign& design) noexcept { design_ = design; }
    [[nodiscard]] const CompressorDesign& design() const noexcept { return design_; }

    /// Jumps to the gain for silence (the makeup gain, or 0 dB when off).
    void reset() noexcept;

    /// Compresses in place.
    void process(double* const* channels, std::size_t numChannels, std::size_t frames) noexcept;

    /// Gain applied at the end of the last block, in dB (makeup included).
    [[nodiscard]] double gainDb() const noexcept { return gainDb_; }

private:
    CompressorDesign design_{};
    double gainDb_ = 0.0;
};

}  // namespace sp::dsp
