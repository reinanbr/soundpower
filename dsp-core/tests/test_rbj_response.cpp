// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Each RBJ filter type is checked two ways:
//  1. measured: the impulse response of the actual DF-I implementation, Fourier-transformed,
//     against the analytic magnitude of the cookbook's analog prototype (bilinear-mapped);
//  2. cookbook checkpoints: properties that follow directly from the formulas (gain at f0,
//     DC and Nyquist gains, -3 dB points, nulls).
#include <gtest/gtest.h>

#include <cmath>
#include <string>
#include <tuple>

#include "sp/dsp/biquad.h"
#include "sp/dsp/rbj.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

using test::analyticMagnitude;

struct BandCase {
    double f0;      // Hz, or a fraction of fs if < 1
    double gainDb;
    double q;
};

double resolveF0(const BandCase& c, double fs) { return c.f0 < 1.0 ? c.f0 * fs : c.f0; }

std::vector<double> measureImpulse(const BiquadCoeffs& c) {
    Df1State s;
    return test::impulseResponse([&](double x) { return processSample(c, s, x); });
}

class RbjResponseTest
    : public ::testing::TestWithParam<std::tuple<FilterType, double, BandCase>> {};

TEST_P(RbjResponseTest, MeasuredResponseMatchesAnalyticFormula) {
    const auto [type, fs, band] = GetParam();
    const double f0 = resolveF0(band, fs);
    const BiquadCoeffs c = designBiquad(type, fs, f0, band.gainDb, band.q);
    ASSERT_TRUE(isStable(c));

    const std::vector<double> h = measureImpulse(c);
    std::vector<double> freqs = test::logFrequencies(20.0, 0.45 * fs, 24);
    freqs.push_back(f0);

    for (const double f : freqs) {
        const double expected = analyticMagnitude(type, fs, f0, band.gainDb, band.q, f);
        const double measured = std::abs(test::dftAt(h, f, fs));
        // Also check the coefficient-domain evaluation used by the UI and auto-preamp.
        const double evaluated = std::abs(response(c, f, fs));
        if (expected > 1e-3) {
            EXPECT_NEAR(gainToDb(measured), gainToDb(expected), 1e-4) << "f=" << f;
            EXPECT_NEAR(gainToDb(evaluated), gainToDb(expected), 1e-6) << "f=" << f;
        } else {
            EXPECT_NEAR(measured, expected, 1e-7) << "f=" << f;
            EXPECT_NEAR(evaluated, expected, 1e-9) << "f=" << f;
        }
    }
}

const BandCase kBandCases[] = {
    {32.0, 6.0, 0.7071},   {125.0, -9.0, 1.41}, {1000.0, 12.0, 0.5},
    {1000.0, -3.0, 4.0},   {6000.0, 4.5, 2.0},  {0.4, -6.0, 0.9},  // 0.4 * fs
};

std::string caseName(const ::testing::TestParamInfo<RbjResponseTest::ParamType>& info) {
    const FilterType type = std::get<0>(info.param);
    const double fs = std::get<1>(info.param);
    return std::string(test::typeName(type)) + "_" + std::to_string(static_cast<int>(fs)) +
           "_case" + std::to_string(info.index % std::size(kBandCases));
}

INSTANTIATE_TEST_SUITE_P(AllTypes, RbjResponseTest,
                         ::testing::Combine(::testing::ValuesIn(test::kAllTypes),
                                            ::testing::Values(44100.0, 48000.0, 96000.0),
                                            ::testing::ValuesIn(kBandCases)),
                         caseName);

// ---- Cookbook checkpoints -------------------------------------------------------------------

constexpr double kRates[] = {44100.0, 48000.0, 96000.0};

double dbAt(const BiquadCoeffs& c, double f, double fs) { return magnitudeDb(c, f, fs); }
double linAt(const BiquadCoeffs& c, double f, double fs) { return std::abs(response(c, f, fs)); }

TEST(RbjCheckpoints, PeakingGainAtCentreEqualsGain) {
    for (double fs : kRates) {
        for (double gain : {-30.0, -12.0, -0.5, 0.5, 6.0, 30.0}) {
            for (double q : {0.1, 0.7071, 4.0, 40.0}) {
                const auto c = designBiquad(FilterType::Peaking, fs, 1000.0, gain, q);
                EXPECT_NEAR(dbAt(c, 1000.0, fs), gain, 1e-9);
                EXPECT_NEAR(dbAt(c, 0.0, fs), 0.0, 1e-9);       // DC
                EXPECT_NEAR(dbAt(c, fs / 2, fs), 0.0, 1e-9);    // Nyquist
            }
        }
    }
}

TEST(RbjCheckpoints, ShelvesReachGainAtTheirEndAndHalfGainAtCentre) {
    for (double fs : kRates) {
        for (double gain : {-24.0, -6.0, 3.0, 12.0}) {
            for (double q : {0.5, 0.7071, 1.0}) {
                const auto low = designBiquad(FilterType::LowShelf, fs, 200.0, gain, q);
                EXPECT_NEAR(dbAt(low, 0.0, fs), gain, 1e-9);
                EXPECT_NEAR(dbAt(low, fs / 2, fs), 0.0, 1e-9);
                EXPECT_NEAR(dbAt(low, 200.0, fs), gain / 2, 1e-9);

                const auto high = designBiquad(FilterType::HighShelf, fs, 5000.0, gain, q);
                EXPECT_NEAR(dbAt(high, 0.0, fs), 0.0, 1e-9);
                EXPECT_NEAR(dbAt(high, fs / 2, fs), gain, 1e-9);
                EXPECT_NEAR(dbAt(high, 5000.0, fs), gain / 2, 1e-9);
            }
        }
    }
}

TEST(RbjCheckpoints, ButterworthPassFiltersAreMinus3dBAtCutoff) {
    const double q = 1.0 / std::sqrt(2.0);
    const double minus3 = gainToDb(1.0 / std::sqrt(2.0));
    for (double fs : kRates) {
        for (double f0 : {50.0, 1000.0, 0.3 * fs}) {
            const auto lp = designBiquad(FilterType::LowPass, fs, f0, 0.0, q);
            EXPECT_NEAR(dbAt(lp, f0, fs), minus3, 1e-9);
            EXPECT_NEAR(dbAt(lp, 0.0, fs), 0.0, 1e-9);
            EXPECT_LT(linAt(lp, fs / 2, fs), 1e-12);

            const auto hp = designBiquad(FilterType::HighPass, fs, f0, 0.0, q);
            EXPECT_NEAR(dbAt(hp, f0, fs), minus3, 1e-9);
            EXPECT_LT(linAt(hp, 0.0, fs), 1e-12);
            EXPECT_NEAR(dbAt(hp, fs / 2, fs), 0.0, 1e-9);
        }
    }
}

TEST(RbjCheckpoints, PassFiltersPeakAtQ) {
    // A second-order low pass has |H(f0)| = Q exactly.
    for (double fs : kRates) {
        for (double q : {0.5, 2.0, 10.0}) {
            const auto lp = designBiquad(FilterType::LowPass, fs, 800.0, 0.0, q);
            EXPECT_NEAR(linAt(lp, 800.0, fs), q, 1e-9 * q);
        }
    }
}

TEST(RbjCheckpoints, NotchNullsTheCentreAndPassesTheEnds) {
    for (double fs : kRates) {
        for (double q : {0.5, 5.0, 30.0}) {
            const auto c = designBiquad(FilterType::Notch, fs, 3000.0, 0.0, q);
            EXPECT_LT(linAt(c, 3000.0, fs), 1e-12);
            EXPECT_NEAR(dbAt(c, 0.0, fs), 0.0, 1e-9);
            EXPECT_NEAR(dbAt(c, fs / 2, fs), 0.0, 1e-9);
        }
    }
}

TEST(RbjCheckpoints, BandPassHasUnityPeakAndBlocksTheEnds) {
    for (double fs : kRates) {
        for (double q : {0.5, 5.0, 30.0}) {
            const auto c = designBiquad(FilterType::BandPass, fs, 3000.0, 0.0, q);
            EXPECT_NEAR(dbAt(c, 3000.0, fs), 0.0, 1e-9);
            EXPECT_LT(linAt(c, 0.0, fs), 1e-12);
            EXPECT_LT(linAt(c, fs / 2, fs), 1e-12);
        }
    }
}

TEST(RbjCheckpoints, ZeroGainPeakingAndShelvesAreIdentity) {
    for (FilterType t : {FilterType::Peaking, FilterType::LowShelf, FilterType::HighShelf}) {
        EXPECT_TRUE(isIdentityBand(t, 0.0));
        const auto c = designBiquad(t, 48000.0, 1000.0, 0.0, 1.0);
        EXPECT_NEAR(c.b0, 1.0, 1e-12);
        EXPECT_NEAR(c.b1, c.a1, 1e-12);
        EXPECT_NEAR(c.b2, c.a2, 1e-12);
    }
    for (FilterType t : {FilterType::LowPass, FilterType::HighPass, FilterType::Notch,
                         FilterType::BandPass}) {
        EXPECT_FALSE(isIdentityBand(t, 0.0));
    }
}

}  // namespace
}  // namespace sp::dsp
