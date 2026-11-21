// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "sp/dsp/biquad.h"
#include "sp/dsp/types.h"

namespace sp::dsp {

/// Real-time-ready EQ: coefficients for each band slot, computed off the audio thread.
/// Slot i corresponds to band i of EqParams. Inactive slots (disabled, beyond `count`, or
/// identity) are skipped during processing.
struct EqDesign {
    std::array<BiquadCoeffs, kMaxBands> coeffs{};
    std::array<bool, kMaxBands> active{};
    std::array<std::uint8_t, kMaxBands> activeSlots{};  ///< active slot indices, ascending
    std::uint32_t activeCount = 0;

    friend bool operator==(const EqDesign&, const EqDesign&) = default;
};

/// Designs every band. `params` must already be sanitized for `sampleRate`.
[[nodiscard]] EqDesign designEq(const EqParams& params, double sampleRate) noexcept;

/// Magnitude of the whole cascade at `freqHz`, in dB.
[[nodiscard]] double eqMagnitudeDb(const EqDesign& design, double freqHz,
                                   double sampleRate) noexcept;

/// Highest point of the cascade's magnitude response in dB, searched on a 512-point log grid
/// from 10 Hz to 0.49 fs plus every active band's centre frequency. Used by auto-preamp.
[[nodiscard]] double eqPeakGainDb(const EqDesign& design, const EqParams& params,
                                  double sampleRate) noexcept;

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
    EqDesign design_{};
    std::array<std::array<Df1State, kMaxBands>, kMaxChannels> state_{};
    /// Last two samples that entered the cascade, per channel: {x[n-1], x[n-2]}.
    std::array<std::array<double, 2>, kMaxChannels> inputHistory_{};
};

}  // namespace sp::dsp
