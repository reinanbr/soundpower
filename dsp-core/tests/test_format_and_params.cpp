// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "sp/dsp/format.h"
#include "sp/dsp/limits.h"
#include "sp/dsp/smoothing.h"

namespace sp::dsp {
namespace {

constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();
constexpr double kInf = std::numeric_limits<double>::infinity();

TEST(Format, Int16ToFloatCoversTheFullRange) {
    const std::int16_t in[] = {-32768, -16384, 0, 16384, 32767};
    float out[5];
    int16ToFloat(in, out, 5);
    EXPECT_EQ(out[0], -1.0f);
    EXPECT_EQ(out[1], -0.5f);
    EXPECT_EQ(out[2], 0.0f);
    EXPECT_EQ(out[3], 0.5f);
    EXPECT_FLOAT_EQ(out[4], 32767.0f / 32768.0f);
}

TEST(Format, FloatToInt16RoundTripsWithinOneLsbAndClips) {
    std::vector<std::int16_t> all(65536);
    for (int i = 0; i < 65536; ++i) all[static_cast<std::size_t>(i)] = static_cast<std::int16_t>(i - 32768);
    std::vector<float> f(all.size());
    int16ToFloat(all.data(), f.data(), all.size());
    std::vector<std::int16_t> back(all.size());
    TpdfDither dither;
    floatToInt16(f.data(), back.data(), f.size(), dither);
    for (std::size_t i = 0; i < all.size(); ++i) {
        ASSERT_LE(std::abs(back[i] - all[i]), 1) << i;
    }

    const float extremes[] = {2.0f, -2.0f, std::numeric_limits<float>::quiet_NaN(),
                              std::numeric_limits<float>::infinity()};
    std::int16_t out[4];
    floatToInt16(extremes, out, 4, dither);
    EXPECT_EQ(out[0], 32767);
    EXPECT_EQ(out[1], -32768);
    EXPECT_EQ(out[2], 0);
    EXPECT_EQ(out[3], 0);
}

TEST(Format, TpdfDitherIsZeroMeanAndBounded) {
    TpdfDither d(123);
    double sum = 0.0;
    constexpr int kN = 1'000'000;
    for (int i = 0; i < kN; ++i) {
        const float v = d.next();
        ASSERT_GT(v, -1.0f);
        ASSERT_LT(v, 1.0f);
        sum += static_cast<double>(v);
    }
    EXPECT_NEAR(sum / kN, 0.0, 2e-3);
}

TEST(Format, SanitizeFloatClampsAndRemovesNonFinite) {
    float v[] = {0.5f, 1.5f, -3.0f, std::numeric_limits<float>::quiet_NaN(),
                 -std::numeric_limits<float>::infinity()};
    sanitizeFloat(v, 5);
    EXPECT_EQ(v[0], 0.5f);
    EXPECT_EQ(v[1], 1.0f);
    EXPECT_EQ(v[2], -1.0f);
    EXPECT_EQ(v[3], 0.0f);
    EXPECT_EQ(v[4], 0.0f);
}

TEST(LinearRamp, ReachesTheTargetExactly) {
    LinearRamp r;
    r.reset(1.0);
    r.setTarget(0.1, 7);
    for (int i = 0; i < 6; ++i) {
        r.next();
        EXPECT_TRUE(r.ramping());
    }
    EXPECT_EQ(r.next(), 0.1);
    EXPECT_FALSE(r.ramping());
    EXPECT_EQ(r.next(), 0.1);
    r.setTarget(0.5, 0);
    EXPECT_EQ(r.current(), 0.5);
}

TEST(Sanitize, ClampsEveryField) {
    ChainParams p;
    p.eq.count = 99;
    p.eq.bands[0] = {FilterType::Peaking, true, 5.0, 99.0, 0.001};
    p.eq.bands[1] = {static_cast<FilterType>(200), true, 1e9, -99.0, 1e6};
    p.eq.bands[2] = {FilterType::LowPass, true, kNaN, kInf, kNaN};
    p.preampDb = 100.0;
    p.limiter = {5.0, 0.0, 1e9};

    const ChainParams s = sanitize(p, 44100.0);
    EXPECT_EQ(s.eq.count, kMaxBands);
    EXPECT_EQ(s.eq.bands[0].freqHz, limits::kFreqMinHz);
    EXPECT_EQ(s.eq.bands[0].gainDb, limits::kGainMaxDb);
    EXPECT_EQ(s.eq.bands[0].q, limits::kQMin);
    EXPECT_EQ(s.eq.bands[1].type, FilterType::Peaking);
    EXPECT_DOUBLE_EQ(s.eq.bands[1].freqHz, 0.49 * 44100.0);
    EXPECT_EQ(s.eq.bands[1].gainDb, limits::kGainMinDb);
    EXPECT_EQ(s.eq.bands[1].q, limits::kQMax);
    EXPECT_EQ(s.eq.bands[2].freqHz, BandParams{}.freqHz);
    EXPECT_EQ(s.eq.bands[2].gainDb, 0.0);
    EXPECT_EQ(s.eq.bands[2].q, BandParams{}.q);
    EXPECT_EQ(s.preampDb, limits::kPreampMaxDb);
    EXPECT_EQ(s.limiter.ceilingDb, limits::kCeilingMaxDb);
    EXPECT_EQ(s.limiter.lookaheadMs, limits::kLookaheadMinMs);
    EXPECT_EQ(s.limiter.releaseMs, limits::kReleaseMaxMs);
}

TEST(Sanitize, DisablesBandsBeyondCount) {
    ChainParams p;
    p.eq.count = 2;
    for (auto& b : p.eq.bands) b.enabled = true;
    const ChainParams s = sanitize(p, 48000.0);
    EXPECT_TRUE(s.eq.bands[1].enabled);
    EXPECT_FALSE(s.eq.bands[2].enabled);
    EXPECT_FALSE(s.eq.bands[kMaxBands - 1].enabled);
}

}  // namespace
}  // namespace sp::dsp
