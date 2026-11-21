// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// The stream volume stage (direct volume control): placement before the limiter, the headroom
// auto-preamp gets back, ramps, the dry path and clamping.
#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>
#include <vector>

#include "sp/dsp/math.h"
#include "sp/dsp/processor.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

constexpr double kFs = 48000.0;

std::vector<float> run(Processor& p, std::vector<float> in, std::size_t block = 256) {
    const std::size_t ch = p.channels();
    const std::size_t frames = in.size() / ch;
    for (std::size_t off = 0; off < frames; off += block) {
        const std::size_t n = std::min(block, frames - off);
        p.process(in.data() + off * ch, in.data() + off * ch, n);
    }
    return in;
}

/// Largest |sample| over the last `frames` frames (all channels).
double tailPeak(const std::vector<float>& y, std::size_t channels, std::size_t frames) {
    double peak = 0.0;
    for (std::size_t i = y.size() - frames * channels; i < y.size(); ++i) {
        peak = std::max(peak, static_cast<double>(std::fabs(y[i])));
    }
    return peak;
}

ChainParams flat() {
    ChainParams p;
    p.autoPreamp = false;
    return p;
}

/// One +12 dB peaking band at 1 kHz: a 1 kHz sine gets exactly +12 dB, and auto-preamp cuts
/// exactly 12 dB.
ChainParams boost12() {
    ChainParams p;
    p.autoPreamp = true;
    p.eq.count = 1;
    p.eq.bands[0] = {FilterType::Peaking, true, 1000.0, 12.0, 1.0, BandChannel::All};
    return p;
}

void setUp(Processor& p, const ChainParams& params, std::size_t channels = 2) {
    p.setParams(params);
    ASSERT_TRUE(p.prepare(kFs, channels));
}

constexpr std::size_t kFrames = 9600;  // 200 ms: every ramp and filter has settled
constexpr std::size_t kTail = 2400;

TEST(StreamVolume, WithoutApplyTheChainIsUnchanged) {
    const std::vector<float> in = test::whiteNoise(0.3, kFrames * 2, 7);
    Processor a;
    setUp(a, boost12());
    Processor b;
    setUp(b, boost12());
    b.setStreamVolume({false, 0.3, 0.2});
    EXPECT_EQ(run(a, in), run(b, in));
    EXPECT_EQ(b.volumeStageGain(), 1.0f);
}

TEST(StreamVolume, IsAppliedBeforeTheLimiter) {
    // 0.9 is above the default -1 dB ceiling (0.891): at full volume the limiter works, at
    // half volume the chain's output is plain 0.45 and the limiter stays idle.
    const std::vector<float> in = test::sine(1000.0, kFs, 0.9, kFrames, 2);
    Processor full;
    setUp(full, flat());
    full.setStreamVolume({true, 1.0, 1.0}, /*instant=*/true);
    (void)run(full, in);
    EXPECT_LT(full.limiterGainReductionDb(), -0.05f);

    Processor half;
    setUp(half, flat());
    half.setStreamVolume({true, 0.5, 0.5}, /*instant=*/true);
    const std::vector<float> out = run(half, in);
    EXPECT_NEAR(tailPeak(out, 2, kTail), 0.45, 0.45 * 1e-3);
    EXPECT_EQ(half.limiterGainReductionDb(), 0.0f);
    EXPECT_NEAR(half.volumeStageGain(), 0.5f, 1e-6f);
}

TEST(StreamVolume, GivesBackTheHeadroomTheVolumeLeaves) {
    // Auto-preamp alone keeps the boosted 1 kHz sine at its input level (0.2) at any volume.
    // With direct volume control the output is min(volume · 4, 1) · 0.2 (+12 dB = ×3.98).
    const double boost = dbToGain(12.0);
    const std::vector<float> in = test::sine(1000.0, kFs, 0.2, kFrames, 2);
    for (const double volume : {0.05, 0.1, 0.2, 0.5, 1.0}) {
        SCOPED_TRACE(volume);
        Processor p;
    setUp(p, boost12());
        p.setStreamVolume({true, volume, volume}, /*instant=*/true);
        const double expected = 0.2 * std::min(volume * boost, 1.0);
        EXPECT_NEAR(tailPeak(run(p, in), 2, kTail), expected, expected * 2e-3);
        EXPECT_NEAR(p.volumeStageGain(), std::min(volume * boost, 1.0), 1e-5);
    }
}

TEST(StreamVolume, NoHeadroomIsGivenBackWithoutAutoPreamp) {
    ChainParams params = boost12();
    params.autoPreamp = false;
    params.preampDb = -12.0;  // the same attenuation, chosen by hand
    const std::vector<float> in = test::sine(1000.0, kFs, 0.2, kFrames, 2);
    Processor p;
    setUp(p, params);
    p.setStreamVolume({true, 0.1, 0.1}, /*instant=*/true);
    EXPECT_NEAR(tailPeak(run(p, in), 2, kTail), 0.02, 0.02 * 2e-3);
}

TEST(StreamVolume, ChangesRampWithoutSteps) {
    // A 100 Hz sine at 0.5 moves by at most 0.0066 per sample; a 20 dB volume step at its peak
    // would jump by 0.45.
    const std::vector<float> in = test::sine(100.0, kFs, 0.5, kFrames, 2);
    Processor p;
    setUp(p, flat());
    p.setStreamVolume({true, 1.0, 1.0}, /*instant=*/true);
    std::vector<float> out = in;
    const std::size_t half = kFrames / 2;
    p.process(out.data(), out.data(), half);
    p.setStreamVolume({true, 0.1, 0.1});
    p.process(out.data() + half * 2, out.data() + half * 2, kFrames - half);
    EXPECT_LT(test::maxStep(out, 2, 0, 100), 0.008);
    EXPECT_NEAR(tailPeak(out, 2, kTail), 0.05, 0.05 * 1e-3);
}

TEST(StreamVolume, InstantVolumeIsThereFromTheFirstSample) {
    const std::vector<float> in = test::sine(1000.0, kFs, 0.5, 2400, 2);
    Processor p;
    setUp(p, flat());
    p.setStreamVolume({true, 0.25, 0.25}, /*instant=*/true);
    const std::vector<float> out = run(p, in);
    // Past the limiter's delay, the very first block is already at a quarter.
    EXPECT_LE(test::maxAbs(out), 0.125 + 1e-6);
    EXPECT_NEAR(tailPeak(out, 2, 1200), 0.125, 0.125 * 1e-3);
}

TEST(StreamVolume, TheBypassedDryPathCarriesThePlainVolume) {
    ChainParams params = boost12();
    params.bypass = true;
    const std::vector<float> in = test::whiteNoise(0.5, kFrames * 2, 3);
    Processor p;
    setUp(p, params);
    p.setStreamVolume({true, 0.5, 0.25}, /*instant=*/true);
    const std::vector<float> out = run(p, in);
    const std::size_t delay = p.latencyFrames();
    ASSERT_GT(delay, 0u);
    for (std::size_t f = delay; f < kFrames; ++f) {
        const std::size_t from = (f - delay) * 2;
        ASSERT_EQ(out[f * 2], static_cast<float>(static_cast<double>(in[from]) * 0.5)) << f;
        ASSERT_EQ(out[f * 2 + 1], static_cast<float>(static_cast<double>(in[from + 1]) * 0.25))
            << f;
    }
}

TEST(StreamVolume, PerChannelVolumesKeepTheirBalance) {
    // Left at -6 dB, right at -12 dB, +12 dB boost: headroom is limited by the louder side
    // (left reaches unity), and the right side stays 6 dB below it.
    const double boost = dbToGain(12.0);
    const std::vector<float> in = test::sine(1000.0, kFs, 0.2, kFrames, 2);
    Processor p;
    setUp(p, boost12());
    p.setStreamVolume({true, 0.5, 0.25}, /*instant=*/true);
    const std::vector<float> out = run(p, in);
    double left = 0.0;
    double right = 0.0;
    for (std::size_t f = kFrames - kTail; f < kFrames; ++f) {
        left = std::max(left, static_cast<double>(std::fabs(out[f * 2])));
        right = std::max(right, static_cast<double>(std::fabs(out[f * 2 + 1])));
    }
    const double restore = std::min(boost, 1.0 / 0.5);
    EXPECT_NEAR(left, 0.2 * 0.5 * restore, 1e-3);
    EXPECT_NEAR(right, 0.2 * 0.25 * restore, 1e-3);
}

TEST(StreamVolume, AMonoStreamUsesTheLouderChannel) {
    const std::vector<float> in = test::sine(1000.0, kFs, 0.4, kFrames, 1);
    Processor p;
    setUp(p, flat(), 1);
    p.setStreamVolume({true, 0.25, 0.5}, /*instant=*/true);
    EXPECT_NEAR(tailPeak(run(p, in), 1, kTail), 0.2, 0.2 * 1e-3);
}

TEST(StreamVolume, VolumesAreClampedToUnity) {
    const std::vector<float> in = test::sine(1000.0, kFs, 0.4, kFrames, 2);
    Processor loud;
    setUp(loud, flat());
    loud.setStreamVolume({true, 4.0, 4.0}, /*instant=*/true);
    EXPECT_NEAR(tailPeak(run(loud, in), 2, kTail), 0.4, 0.4 * 1e-3);

    Processor silent;
    setUp(silent, flat());
    const double nan = std::numeric_limits<double>::quiet_NaN();
    silent.setStreamVolume({true, -1.0, nan}, /*instant=*/true);
    EXPECT_EQ(tailPeak(run(silent, in), 2, kTail), 0.0);
}

TEST(StreamVolume, SettingsBeforePrepareAreAppliedThere) {
    Processor p;
    p.setParams(flat());
    p.setStreamVolume({true, 0.5, 0.5});
    ASSERT_TRUE(p.prepare(kFs, 2));
    const std::vector<float> in = test::sine(1000.0, kFs, 0.4, 2400, 2);
    EXPECT_LE(test::maxAbs(run(p, in)), 0.2 + 1e-6);
}

}  // namespace
}  // namespace sp::dsp
