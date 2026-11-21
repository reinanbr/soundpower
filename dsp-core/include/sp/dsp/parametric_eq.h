// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "sp/dsp/biquad.h"
#include "sp/dsp/types.h"

namespace sp::dsp {

/// Real-time-ready EQ: coefficients for each slot, computed off the audio thread.
/// Slot i < kMaxBands corresponds to band i of EqParams; slots kBassSlot and kTrebleSlot hold
/// the tone shelves. Inactive slots (disabled, beyond `count`, or identity) are skipped during
/// processing.
struct EqDesign {
    std::array<BiquadCoeffs, kEqSlots> coeffs{};
    std::array<bool, kEqSlots> active{};
    std::array<std::uint8_t, kEqSlots> activeSlots{};  ///< active slot indices, ascending
    std::array<double, kEqSlots> centerHz{};           ///< design frequency of each active slot
    std::array<BandChannel, kEqSlots> channel{};       ///< channels each active slot filters
    std::uint32_t activeCount = 0;

    friend bool operator==(const EqDesign&, const EqDesign&) = default;
};

/// Designs every band. `params` must already be sanitized for `sampleRate`.
[[nodiscard]] EqDesign designEq(const EqParams& params, double sampleRate) noexcept;

/// Designs every band plus the tone shelves. Both must already be sanitized.
[[nodiscard]] EqDesign designEq(const EqParams& params, const ToneParams& tone,
                                double sampleRate) noexcept;

/// Magnitude in dB at `freqHz` of the slots on `path`: with BandChannel::All, the slots that
/// filter every channel; with another channel, those plus the slots routed to that channel.
[[nodiscard]] double eqMagnitudeDb(const EqDesign& design, double freqHz, double sampleRate,
                                   BandChannel path = BandChannel::All) noexcept;

/// Highest gain of the cascade in dB, searched on a 512-point log grid from 10 Hz to 0.49 fs
/// plus every active slot's centre frequency. Used by auto-preamp. At each frequency the gain
/// is all + max(left, right) + max(mid, side), each term the dB sum of the slots routed there:
/// exact without routed slots or with only left/right or only mid/side ones, a close estimate
/// when both kinds are combined.
[[nodiscard]] double eqPeakGainDb(const EqDesign& design, double sampleRate) noexcept;

/// Cascade of up to kMaxBands biquads for up to kMaxChannels planar channels, processed in
/// double precision. All methods are real-time safe.
class ParametricEq {
public:
    void setDesign(const EqDesign& design) noexcept { design_ = design; }
    [[nodiscard]] const EqDesign& design() const noexcept { return design_; }

    /// Clears all filter history.
    void reset() noexcept;

    /// Initializes this bank's history from `other` (the bank it is about to replace) so that
    /// switching banks does not restart the filters from silence. Call after setDesign().
    /// Active slots inherit the matching slot's history; slots that were inactive in `other`
    /// start as a pass-through of the signal that reached that point in `other`'s chain.
    void takeHistoryFrom(const ParametricEq& other) noexcept;

    /// Filters `frames` samples of each channel in place.
    void process(double* const* channels, std::size_t numChannels, std::size_t frames) noexcept;

private:
    /// Runs one slot over one channel's block, with the filter state at `stateIndex`.
    void filter(std::size_t slot, std::size_t stateIndex, double* x, std::size_t frames) noexcept;

    EqDesign design_{};
    /// Per channel and slot; a mid slot keeps its state at index 0, a side slot at index 1.
    std::array<std::array<Df1State, kEqSlots>, kMaxChannels> state_{};
    /// Last two samples that entered the cascade, per channel: {x[n-1], x[n-2]}.
    std::array<std::array<double, 2>, kMaxChannels> inputHistory_{};
};

}  // namespace sp::dsp
