// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <cmath>
#include <random>
#include <vector>

#include "sp/dsp/biquad.h"
#include "sp/dsp/limits.h"
#include "sp/dsp/math.h"
#include "sp/dsp/processor.h"
#include "sp/dsp/rbj.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

TEST(Stability, EveryDesignInTheValidRangeIsStable) {
    for (double fs : {44100.0, 48000.0, 96000.0, 192000.0}) {
        const auto freqs =
            test::logFrequencies(limits::kFreqMinHz, limits::kFreqMaxFsRatio * fs, 40);
        for (FilterType type : test::kAllTypes) {
            for (double f : freqs) {
                for (double q : {limits::kQMin, 0.1, 0.5, 0.7071, 2.0, 10.0, limits::kQMax}) {
                    for (double gain : {limits::kGainMinDb, -6.0, 0.5, 6.0, limits::kGainMaxDb}) {
                        const BiquadCoeffs c = designBiquad(type, fs, f, gain, q);
                        ASSERT_TRUE(std::isfinite(c.b0) && std::isfinite(c.b1) &&
                                    std::isfinite(c.b2))
                            << test::typeName(type) << " fs=" << fs << " f=" << f << " q=" << q;
                        ASSERT_TRUE(isStable(c)) << test::typeName(type) << " fs=" << fs
                                                 << " f=" << f << " q=" << q << " g=" << gain;
                    }
                }
            }
        }
    }
}

struct Extreme {
    FilterType type;
    double fs;
    double f;
    double gain;
    double q;
};

const Extreme kExtremes[] = {
    {FilterType::LowPass, 96000.0, 10.0, 0.0, 40.0},
    {FilterType::HighPass, 96000.0, 10.0, 0.0, 40.0},
    {FilterType::BandPass, 96000.0, 10.0, 0.0, 40.0},
    {FilterType::Notch, 96000.0, 10.0, 0.0, 40.0},
    {FilterType::Peaking, 96000.0, 10.0, 30.0, 40.0},
    {FilterType::Peaking, 96000.0, 10.0, -30.0, 0.05},
    {FilterType::LowShelf, 96000.0, 10.0, 30.0, 0.05},
    {FilterType::HighShelf, 96000.0, 10.0, -30.0, 40.0},
    {FilterType::Peaking, 44100.0, 0.49 * 44100.0, 30.0, 40.0},
    {FilterType::LowPass, 44100.0, 0.49 * 44100.0, 0.0, 40.0},
    {FilterType::HighShelf, 44100.0, 0.49 * 44100.0, 30.0, 40.0},
};

TEST(Stability, ExtremeFiltersStayFiniteAndBoundedOnLongNoise) {
    for (const Extreme& e : kExtremes) {
        const BiquadCoeffs c = designBiquad(e.type, e.fs, e.f, e.gain, e.q);
        Df1State s;
        std::mt19937 rng(42);
        std::uniform_real_distribution<double> u(-1.0, 1.0);
        double peak = 0.0;
        for (int n = 0; n < 2'000'000; ++n) {
            const double y = processSample(c, s, u(rng));
            ASSERT_TRUE(std::isfinite(y)) << test::typeName(e.type) << " n=" << n;
            peak = std::max(peak, std::abs(y));
        }
        EXPECT_LT(peak, 1e4) << test::typeName(e.type) << " f=" << e.f;
    }
}

/// Largest pole radius of the section: roots of z^2 + a1 z + a2.
double poleRadius(const BiquadCoeffs& c) {
    const double disc = c.a1 * c.a1 - 4.0 * c.a2;
    if (disc < 0.0) return std::sqrt(c.a2);
    const double s = std::sqrt(disc);
    return std::max(std::abs((-c.a1 + s) / 2.0), std::abs((-c.a1 - s) / 2.0));
}

TEST(Stability, ImpulseResponsesDecayAsFastAsTheirPolesPredict) {
    // Extreme settings are legitimately slow: a +30 dB, Q 40 peak at 10 Hz has a pole Q of
    // A·Q ≈ 225, i.e. a time constant of ~7 s. Run each filter long enough for its slowest
    // pole to decay by 1e-12, then require the response to have actually decayed that much.
    for (const Extreme& e : kExtremes) {
        const BiquadCoeffs c = designBiquad(e.type, e.fs, e.f, e.gain, e.q);
        const double r = poleRadius(c);
        ASSERT_LT(r, 1.0);
        const auto length = static_cast<std::size_t>(std::ceil(std::log(1e-12) / std::log(r))) + 8192;
        ASSERT_LT(length, std::size_t{1} << 26) << test::typeName(e.type);

        Df1State s;
        double peak = 0.0, tail = 0.0;
        for (std::size_t n = 0; n < length; ++n) {
            const double y = std::abs(processSample(c, s, n == 0 ? 1.0 : 0.0));
            peak = std::max(peak, y);
            if (n >= length - 4096) tail = std::max(tail, y);
        }
        // Allow a modest constant for the residues and repeated-pole growth.
        EXPECT_LT(tail, 1e-9 * std::max(peak, 1.0)) << test::typeName(e.type) << " f=" << e.f;
    }
}

TEST(Stability, FullChainSurvivesTenMillionSamplesOfExtremeSettings) {
    const double fs = 96000.0;
    ChainParams params;
    params.autoPreamp = false;
    params.preampDb = limits::kPreampMaxDb;
    const auto freqs = test::logFrequencies(limits::kFreqMinHz, 0.49 * fs, kMaxBands);
    for (std::size_t i = 0; i < kMaxBands; ++i) {
        BandParams& b = params.eq.bands[i];
        b.type = test::kAllTypes[i % std::size(test::kAllTypes)];
        b.enabled = true;
        b.freqHz = freqs[i];
        b.gainDb = (i % 2 == 0) ? limits::kGainMaxDb : limits::kGainMinDb;
        b.q = (i % 3 == 0) ? limits::kQMax : limits::kQMin;
    }
    params.eq.count = kMaxBands;

    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(fs, 2));
    const float ceiling = static_cast<float>(dbToGain(params.limiter.ceilingDb));

    std::vector<float> block = test::whiteNoise(1.0, 2 * 1000, 1);
    std::mt19937 rng(9);
    std::uniform_real_distribution<float> u(-1.0f, 1.0f);
    constexpr std::size_t kTotalSamples = 10'000'000;
    for (std::size_t done = 0; done < kTotalSamples; done += block.size()) {
        for (auto& v : block) v = u(rng);
        p.process(block.data(), block.data(), block.size() / 2);
        for (float v : block) {
            ASSERT_TRUE(std::isfinite(v));
            ASSERT_LE(std::abs(v), ceiling);
        }
    }
    EXPECT_FALSE(p.takeNonFiniteFlag());
    EXPECT_EQ(p.limiterSafetyClampCount(), 0u);
}

}  // namespace
}  // namespace sp::dsp
