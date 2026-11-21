// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Valid parameter ranges. They mirror the SP_* limits in protocol.h; effect-lib checks that the
// two stay identical with static_asserts. sanitize() is the only place that enforces them.
#pragma once

#include "sp/dsp/types.h"

namespace sp::dsp::limits {

inline constexpr double kFreqMinHz = 10.0;
inline constexpr double kFreqMaxFsRatio = 0.49;
inline constexpr double kGainMinDb = -30.0;
inline constexpr double kGainMaxDb = 30.0;
inline constexpr double kQMin = 0.05;
inline constexpr double kQMax = 40.0;
inline constexpr double kPreampMinDb = -30.0;
inline constexpr double kPreampMaxDb = 30.0;

inline constexpr double kCeilingMinDb = -24.0;
inline constexpr double kCeilingMaxDb = 0.0;
inline constexpr double kLookaheadMinMs = 0.5;
inline constexpr double kLookaheadMaxMs = 10.0;
inline constexpr double kReleaseMinMs = 1.0;
inline constexpr double kReleaseMaxMs = 1000.0;
inline constexpr double kBalanceMin = -1.0;
inline constexpr double kBalanceMax = 1.0;
inline constexpr double kWidthMin = 0.0;
inline constexpr double kWidthMax = 2.0;
inline constexpr double kCompThresholdMinDb = -60.0;
inline constexpr double kCompThresholdMaxDb = 0.0;
inline constexpr double kCompRatioMin = 1.0;
inline constexpr double kCompRatioMax = 20.0;
inline constexpr double kCompAttackMinMs = 0.1;
inline constexpr double kCompAttackMaxMs = 200.0;
inline constexpr double kCompReleaseMinMs = 5.0;
inline constexpr double kCompReleaseMaxMs = 2000.0;
inline constexpr double kCompKneeMinDb = 0.0;
inline constexpr double kCompKneeMaxDb = 24.0;
inline constexpr double kCompMakeupMinDb = 0.0;
inline constexpr double kCompMakeupMaxDb = 24.0;

}  // namespace sp::dsp::limits

namespace sp::dsp {

/// Clamps every field into its valid range for `sampleRate`. Non-finite values fall back to the
/// field's default, and unknown filter types become peaking. Never fails.
[[nodiscard]] ChainParams sanitize(const ChainParams& params, double sampleRate) noexcept;

}  // namespace sp::dsp
