// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/parametric_eq.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "sp/dsp/limits.h"
#include "sp/dsp/rbj.h"

namespace sp::dsp {

EqDesign designEq(const EqParams& params, double sampleRate) noexcept {
    EqDesign d{};
    const std::size_t count = std::min(params.count, kMaxBands);
    for (std::size_t i = 0; i < count; ++i) {
        const BandParams& b = params.bands[i];
        if (!b.enabled || isIdentityBand(b.type, b.gainDb)) continue;
        d.coeffs[i] = designBiquad(b.type, sampleRate, b.freqHz, b.gainDb, b.q);
        d.active[i] = true;
        d.activeSlots[d.activeCount++] = static_cast<std::uint8_t>(i);
    }
    return d;
}

double eqMagnitudeDb(const EqDesign& design, double freqHz, double sampleRate) noexcept {
    double db = 0.0;
    for (std::uint32_t k = 0; k < design.activeCount; ++k) {
        db += magnitudeDb(design.coeffs[design.activeSlots[k]], freqHz, sampleRate);
    }
    return db;
}

double eqPeakGainDb(const EqDesign& design, const EqParams& params, double sampleRate) noexcept {
    if (design.activeCount == 0) return 0.0;

    constexpr int kGridPoints = 512;
    const double fLo = limits::kFreqMinHz;
    const double fHi = limits::kFreqMaxFsRatio * sampleRate;
    double peak = -std::numeric_limits<double>::infinity();
    for (int i = 0; i < kGridPoints; ++i) {
        const double t = static_cast<double>(i) / (kGridPoints - 1);
        const double f = fLo * std::pow(fHi / fLo, t);
        peak = std::max(peak, eqMagnitudeDb(design, f, sampleRate));
    }
    for (std::uint32_t k = 0; k < design.activeCount; ++k) {
        const double f = params.bands[design.activeSlots[k]].freqHz;
        peak = std::max(peak, eqMagnitudeDb(design, f, sampleRate));
    }
    return peak;
}

void ParametricEq::reset() noexcept {
    state_ = {};
    inputHistory_ = {};
}

void ParametricEq::takeHistoryFrom(const ParametricEq& other) noexcept {
    for (std::size_t ch = 0; ch < kMaxChannels; ++ch) {
        // History of the signal entering the current slot in `other`'s chain.
        double x1 = other.inputHistory_[ch][0];
        double x2 = other.inputHistory_[ch][1];
        for (std::size_t slot = 0; slot < kMaxBands; ++slot) {
            if (other.design_.active[slot]) {
                const Df1State& s = other.state_[ch][slot];
                if (design_.active[slot]) state_[ch][slot] = s;
                x1 = s.y1;
                x2 = s.y2;
            } else if (design_.active[slot]) {
                // The slot was a pass-through: its input and output were the same signal.
                state_[ch][slot] = Df1State{x1, x2, x1, x2};
            }
        }
        inputHistory_[ch] = other.inputHistory_[ch];
    }
}

void ParametricEq::process(double* const* channels, std::size_t numChannels,
                           std::size_t frames) noexcept {
    if (frames == 0) return;
    const std::size_t nch = std::min(numChannels, kMaxChannels);
    for (std::size_t ch = 0; ch < nch; ++ch) {
        double* x = channels[ch];

        auto& hist = inputHistory_[ch];
        if (frames >= 2) {
            hist = {x[frames - 1], x[frames - 2]};
        } else {
            hist = {x[0], hist[0]};
        }

        for (std::uint32_t k = 0; k < design_.activeCount; ++k) {
            const std::size_t slot = design_.activeSlots[k];
            const BiquadCoeffs c = design_.coeffs[slot];
            Df1State s = state_[ch][slot];
            for (std::size_t n = 0; n < frames; ++n) x[n] = processSample(c, s, x[n]);
            state_[ch][slot] = s;
        }
    }
}

}  // namespace sp::dsp
