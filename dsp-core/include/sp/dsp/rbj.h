// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Biquad designs from Robert Bristow-Johnson's "Audio EQ Cookbook" (Q parameterization for all
// types, including shelves). Implemented from the published formulas.
#pragma once

#include "sp/dsp/biquad.h"
#include "sp/dsp/types.h"

namespace sp::dsp {

/// Designs one section. `gainDb` is used by peaking and shelf types only.
/// Inputs are expected to be sanitized (0 < freqHz < fs/2, q > 0); the function does not clamp.
[[nodiscard]] BiquadCoeffs designBiquad(FilterType type, double sampleRate, double freqHz,
                                        double gainDb, double q) noexcept;

/// True if the band is an exact identity (peaking or shelf with 0 dB gain) and can be skipped.
[[nodiscard]] bool isIdentityBand(FilterType type, double gainDb) noexcept;

/// True if `type` uses its gain parameter.
[[nodiscard]] bool usesGain(FilterType type) noexcept;

}  // namespace sp::dsp
