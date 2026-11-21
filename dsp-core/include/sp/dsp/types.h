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

/// The EQ cascade holds the kMaxBands bands, then the bass and treble shelves of ToneParams.
inline constexpr std::size_t kToneSlots = 2;
inline constexpr std::size_t kEqSlots = kMaxBands + kToneSlots;
inline constexpr std::size_t kBassSlot = kMaxBands;
inline constexpr std::size_t kTrebleSlot = kMaxBands + 1;
/// Q of the tone shelves (shelf slope S = 1: no overshoot).
inline constexpr double kToneShelfQ = 0.70710678118654752;

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

/// The channels a band filters. Mid and side are (L+R)/2 and (L-R)/2 of the first two channels;
/// a band on one of them converts to mid/side, filters, and converts back. On a mono stream only
/// All and Mid bands apply; on more than two channels, Left, Right, Mid and Side use the first two.
enum class BandChannel : std::uint8_t {
    All = 0,
    Left = 1,
    Right = 2,
    Mid = 3,
    Side = 4,
};
inline constexpr std::size_t kBandChannelCount = 5;

struct BandParams {
    FilterType type = FilterType::Peaking;
    bool enabled = false;
    double freqHz = 1000.0;
    double gainDb = 0.0;
    double q = 0.70710678118654752;
    BandChannel channel = BandChannel::All;
};

struct EqParams {
    std::array<BandParams, kMaxBands> bands{};
    std::size_t count = 0;  ///< bands[0, count) are meaningful
};

/// Bass and treble tone controls: a low shelf and a high shelf after the EQ bands. They are
/// part of the EQ curve (crossfaded with it, and counted by auto-preamp).
struct ToneParams {
    bool enabled = true;
    double bassDb = 0.0;
    double bassFreqHz = 100.0;
    double trebleDb = 0.0;
    double trebleFreqHz = 8000.0;
};

/// Stereo stage after the EQ. Ignored for mono streams.
struct StereoParams {
    double balance = 0.0;  ///< -1 = left only, 0 = centre, +1 = right only
    double width = 1.0;    ///< side level: 0 = mono, 1 = unchanged, 2 = doubled
    bool mono = false;     ///< downmix to mono; `width` is kept for when it is turned off
};

/// Feed-forward, stereo-linked compressor with a soft knee, before the limiter.
struct CompressorParams {
    bool enabled = false;
    double thresholdDb = -18.0;
    double ratio = 3.0;
    double attackMs = 10.0;
    double releaseMs = 150.0;
    double kneeDb = 6.0;
    double makeupDb = 0.0;
};

struct LimiterParams {
    double ceilingDb = -1.0;
    double lookaheadMs = 1.5;
    double releaseMs = 50.0;
    /// When false the limiter stops reducing gain (its delay stays, so latency does not change).
    bool enabled = true;
};

struct ChainParams {
    EqParams eq;
    ToneParams tone;
    double preampDb = 0.0;
    bool autoPreamp = true;  ///< subtract the EQ curve's positive peak from the preamp
    bool bypass = false;     ///< A/B bypass: crossfade to the latency-aligned dry signal
    StereoParams stereo;
    CompressorParams compressor;
    LimiterParams limiter;
};

}  // namespace sp::dsp
