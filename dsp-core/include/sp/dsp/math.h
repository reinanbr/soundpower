// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <cmath>

namespace sp::dsp {

inline constexpr double kPi = 3.14159265358979323846;

[[nodiscard]] inline double dbToGain(double db) noexcept { return std::pow(10.0, db / 20.0); }

/// Returns -inf dB for zero gain, like the mathematical definition.
[[nodiscard]] inline double gainToDb(double gain) noexcept { return 20.0 * std::log10(gain); }

}  // namespace sp::dsp
