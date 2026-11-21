// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Plain parameter types shared by all dsp-core modules. These are "user-facing" values
// (Hz, dB, Q, ms); the real-time code works on designs derived from them off-thread.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace sp::dsp {

inline constexpr std::size_t kMaxBands = 32;
inline constexpr std::size_t kMaxChannels = 2;
/// Internal processing block. process() splits host buffers into blocks of at most this size.
inline constexpr std::size_t kMaxBlockFrames = 256;

/// Numeric values are part of the parameter protocol (see protocol/include/soundpower/protocol.h).
enum class FilterType : std::uint8_t {
    Peaking = 0,
    LowShelf = 1,
    HighShelf = 2,
    LowPass = 3,
    HighPass = 4,
    Notch = 5,
    BandPass = 6,
};
inline constexpr std::size_t kFilterTypeCount = 7;

struct BandParams {
    FilterType type = FilterType::Peaking;
    bool enabled = false;
    double freqHz = 1000.0;
    double gainDb = 0.0;
    double q = 0.70710678118654752;
};

struct EqParams {
    std::array<BandParams, kMaxBands> bands{};
    std::size_t count = 0;  ///< bands[0, count) are meaningful
};

struct LimiterParams {
    double ceilingDb = -1.0;
    double lookaheadMs = 1.5;
    double releaseMs = 50.0;
};

struct ChainParams {
    EqParams eq;
    double preampDb = 0.0;
    bool autoPreamp = true;  ///< subtract the EQ curve's positive peak from the preamp
    bool bypass = false;     ///< A/B bypass: crossfade to the latency-aligned dry signal
    LimiterParams limiter;
};

}  // namespace sp::dsp
