// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/parametric_eq.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "sp/dsp/limits.h"
#include "sp/dsp/rbj.h"

namespace sp::dsp {

namespace {

void addSlot(EqDesign& d, std::size_t slot, FilterType type, double sampleRate, double freqHz,
             double gainDb, double q, BandChannel channel = BandChannel::All) noexcept {
    if (isIdentityBand(type, gainDb)) return;
    d.coeffs[slot] = designBiquad(type, sampleRate, freqHz, gainDb, q);
    d.active[slot] = true;
    d.centerHz[slot] = freqHz;
    d.channel[slot] = channel;
    d.activeSlots[d.activeCount++] = static_cast<std::uint8_t>(slot);
}

using History = std::array<std::array<double, 2>, kMaxChannels>;  // {x[n-1], x[n-2]} per channel

bool isMidSide(BandChannel c) noexcept { return c == BandChannel::Mid || c == BandChannel::Side; }

/// The filter-state indices a slot routed to `c` uses: mid lives in channel 0, side in 1.
bool usesState(BandChannel c, std::size_t ch) noexcept {
    switch (c) {
        case BandChannel::All: return true;
        case BandChannel::Left:
        case BandChannel::Mid: return ch == 0;
        case BandChannel::Right:
        case BandChannel::Side: return ch == 1;
    }
    return false;
}

/// History of what a slot routed to `c` sees on state index `ch`, given the L/R history `h`.
std::array<double, 2> seenBy(BandChannel c, std::size_t ch, const History& h) noexcept {
    if (c == BandChannel::Mid) return {0.5 * (h[0][0] + h[1][0]), 0.5 * (h[0][1] + h[1][1])};
    if (c == BandChannel::Side) return {0.5 * (h[0][0] - h[1][0]), 0.5 * (h[0][1] - h[1][1])};
    return h[ch];
}

/// Moves the L/R history `h` past a slot routed to `c` whose filter states are `st`.
void advance(BandChannel c, const std::array<Df1State, kMaxChannels>& st, History& h) noexcept {
    switch (c) {
        case BandChannel::All:
            for (std::size_t ch = 0; ch < kMaxChannels; ++ch) h[ch] = {st[ch].y1, st[ch].y2};
            break;
        case BandChannel::Left: h[0] = {st[0].y1, st[0].y2}; break;
        case BandChannel::Right: h[1] = {st[1].y1, st[1].y2}; break;
        case BandChannel::Mid:
        case BandChannel::Side: {
            const auto mid = seenBy(BandChannel::Mid, 0, h);
            const auto side = seenBy(BandChannel::Side, 1, h);
            const std::array<double, 2> m = c == BandChannel::Mid ? std::array{st[0].y1, st[0].y2} : mid;
            const std::array<double, 2> s = c == BandChannel::Side ? std::array{st[1].y1, st[1].y2} : side;
            h[0] = {m[0] + s[0], m[1] + s[1]};
            h[1] = {m[0] - s[0], m[1] - s[1]};
            break;
        }
    }
}

void toMidSide(double* l, double* r, std::size_t frames) noexcept {
    for (std::size_t n = 0; n < frames; ++n) {
        const double m = 0.5 * (l[n] + r[n]);
        const double s = 0.5 * (l[n] - r[n]);
        l[n] = m;
        r[n] = s;
    }
}

void fromMidSide(double* m, double* s, std::size_t frames) noexcept {
    for (std::size_t n = 0; n < frames; ++n) {
        const double l = m[n] + s[n];
        const double r = m[n] - s[n];
        m[n] = l;
        s[n] = r;
    }
}

}  // namespace

EqDesign designEq(const EqParams& params, double sampleRate) noexcept {
    EqDesign d{};
    const std::size_t count = std::min(params.count, kMaxBands);
    for (std::size_t i = 0; i < count; ++i) {
        const BandParams& b = params.bands[i];
        if (b.enabled) addSlot(d, i, b.type, sampleRate, b.freqHz, b.gainDb, b.q, b.channel);
    }
    return d;
}

EqDesign designEq(const EqParams& params, const ToneParams& tone, double sampleRate) noexcept {
    EqDesign d = designEq(params, sampleRate);
    if (tone.enabled) {
        addSlot(d, kBassSlot, FilterType::LowShelf, sampleRate, tone.bassFreqHz, tone.bassDb,
                kToneShelfQ);
        addSlot(d, kTrebleSlot, FilterType::HighShelf, sampleRate, tone.trebleFreqHz,
                tone.trebleDb, kToneShelfQ);
    }
    return d;
}

namespace {

/// dB sum of the active slots at `freqHz`, per channel they are routed to.
std::array<double, kBandChannelCount> routedDb(const EqDesign& design, double freqHz,
                                                double sampleRate) noexcept {
    std::array<double, kBandChannelCount> db{};
    for (std::uint32_t k = 0; k < design.activeCount; ++k) {
        const std::size_t slot = design.activeSlots[k];
        db[static_cast<std::size_t>(design.channel[slot])] +=
            magnitudeDb(design.coeffs[slot], freqHz, sampleRate);
    }
    return db;
}

double peakAt(const EqDesign& design, double freqHz, double sampleRate) noexcept {
    const auto db = routedDb(design, freqHz, sampleRate);
    auto at = [&db](BandChannel c) { return db[static_cast<std::size_t>(c)]; };
    return at(BandChannel::All) + std::max(at(BandChannel::Left), at(BandChannel::Right)) +
           std::max(at(BandChannel::Mid), at(BandChannel::Side));
}

}  // namespace

double eqMagnitudeDb(const EqDesign& design, double freqHz, double sampleRate,
                     BandChannel path) noexcept {
    const auto db = routedDb(design, freqHz, sampleRate);
    const double all = db[static_cast<std::size_t>(BandChannel::All)];
    return path == BandChannel::All ? all : all + db[static_cast<std::size_t>(path)];
}

double eqPeakGainDb(const EqDesign& design, double sampleRate) noexcept {
    if (design.activeCount == 0) return 0.0;

    constexpr int kGridPoints = 512;
    const double fLo = limits::kFreqMinHz;
    const double fHi = limits::kFreqMaxFsRatio * sampleRate;
    double peak = -std::numeric_limits<double>::infinity();
    for (int i = 0; i < kGridPoints; ++i) {
        const double t = static_cast<double>(i) / (kGridPoints - 1);
        const double f = fLo * std::pow(fHi / fLo, t);
        peak = std::max(peak, peakAt(design, f, sampleRate));
    }
    for (std::uint32_t k = 0; k < design.activeCount; ++k) {
        const double f = design.centerHz[design.activeSlots[k]];
        peak = std::max(peak, peakAt(design, f, sampleRate));
    }
    return peak;
}

void ParametricEq::reset() noexcept {
    state_ = {};
    inputHistory_ = {};
}

void ParametricEq::takeHistoryFrom(const ParametricEq& other) noexcept {
    // Walk `other`'s chain, tracking the L/R history of the signal entering each slot.
    History h = other.inputHistory_;
    for (std::size_t slot = 0; slot < kEqSlots; ++slot) {
        const bool was = other.design_.active[slot];
        const bool is = design_.active[slot];
        const BandChannel before = other.design_.channel[slot];
        const BandChannel now = design_.channel[slot];
        if (is) {
            for (std::size_t ch = 0; ch < kMaxChannels; ++ch) {
                if (!usesState(now, ch)) continue;
                if (was && before == now) {
                    state_[ch][slot] = other.state_[ch][slot];
                } else {
                    // A new or re-routed slot starts as a pass-through of what enters it.
                    const auto x = seenBy(now, ch, h);
                    state_[ch][slot] = Df1State{x[0], x[1], x[0], x[1]};
                }
            }
        }
        if (was) {
            std::array<Df1State, kMaxChannels> st{};
            for (std::size_t ch = 0; ch < kMaxChannels; ++ch) st[ch] = other.state_[ch][slot];
            advance(before, st, h);
        }
    }
    inputHistory_ = other.inputHistory_;
}

void ParametricEq::filter(std::size_t slot, std::size_t stateIndex, double* x,
                          std::size_t frames) noexcept {
    const BiquadCoeffs c = design_.coeffs[slot];
    Df1State s = state_[stateIndex][slot];
    for (std::size_t n = 0; n < frames; ++n) x[n] = processSample(c, s, x[n]);
    state_[stateIndex][slot] = s;
}

void ParametricEq::process(double* const* channels, std::size_t numChannels,
                           std::size_t frames) noexcept {
    if (frames == 0) return;
    const std::size_t nch = std::min(numChannels, kMaxChannels);
    for (std::size_t ch = 0; ch < nch; ++ch) {
        const double* x = channels[ch];
        auto& hist = inputHistory_[ch];
        hist = frames >= 2 ? std::array{x[frames - 1], x[frames - 2]} : std::array{x[0], hist[0]};
    }

    const bool stereo = nch >= 2;
    bool midSide = false;  // channels 0 and 1 hold mid and side
    for (std::uint32_t k = 0; k < design_.activeCount; ++k) {
        const std::size_t slot = design_.activeSlots[k];
        const BandChannel route = design_.channel[slot];
        if (stereo && isMidSide(route) != midSide) {
            if (midSide) {
                fromMidSide(channels[0], channels[1], frames);
            } else {
                toMidSide(channels[0], channels[1], frames);
            }
            midSide = !midSide;
        }
        switch (route) {
            case BandChannel::All:
                for (std::size_t ch = 0; ch < nch; ++ch) filter(slot, ch, channels[ch], frames);
                break;
            case BandChannel::Left:
            case BandChannel::Right:
                // A mono stream has no left or right of its own.
                if (stereo) {
                    const std::size_t ch = route == BandChannel::Left ? 0 : 1;
                    filter(slot, ch, channels[ch], frames);
                }
                break;
            case BandChannel::Mid:
                // The mid of a mono stream is the stream itself.
                filter(slot, 0, channels[0], frames);
                break;
            case BandChannel::Side:
                if (stereo) filter(slot, 1, channels[1], frames);
                break;
        }
    }
    if (midSide) fromMidSide(channels[0], channels[1], frames);
}

}  // namespace sp::dsp
