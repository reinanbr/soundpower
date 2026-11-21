// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Tone shelves, the stereo stage, the compressor and turning the limiter off.
#include <gtest/gtest.h>

#include <cmath>
#include <cstddef>
#include <vector>

#include "sp/dsp/compressor.h"
#include "sp/dsp/limits.h"
#include "sp/dsp/math.h"
#include "sp/dsp/parametric_eq.h"
#include "sp/dsp/processor.h"
#include "sp/dsp/stereo.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

constexpr double kFs = 48000.0;

std::vector<float> runProcessor(Processor& p, std::vector<float> in, std::size_t block = 256) {
    const std::size_t ch = p.channels();
    const std::size_t frames = in.size() / ch;
    for (std::size_t off = 0; off < frames; off += block) {
        const std::size_t n = std::min(block, frames - off);
        p.process(in.data() + off * ch, in.data() + off * ch, n);
    }
    return in;
}

ChainParams quiet() {
    ChainParams p;
    p.autoPreamp = false;
    return p;
}

// ---- Tone -----------------------------------------------------------------------------------

TEST(Tone, ShelvesMatchTheAnalyticShelfResponses) {
    ToneParams tone;
    tone.bassDb = 6.0;
    tone.bassFreqHz = 120.0;
    tone.trebleDb = -4.5;
    tone.trebleFreqHz = 6000.0;
    const EqDesign d = designEq(EqParams{}, tone, kFs);
    ASSERT_EQ(d.activeCount, 2u);
    EXPECT_TRUE(d.active[kBassSlot]);
    EXPECT_TRUE(d.active[kTrebleSlot]);
    for (double f : test::logFrequencies(20.0, 20000.0, 40)) {
        const double expected =
            gainToDb(test::analyticMagnitude(FilterType::LowShelf, kFs, 120.0, 6.0, kToneShelfQ, f)) +
            gainToDb(test::analyticMagnitude(FilterType::HighShelf, kFs, 6000.0, -4.5, kToneShelfQ, f));
        EXPECT_NEAR(eqMagnitudeDb(d, f, kFs), expected, 1e-6) << "f=" << f;
    }
}

TEST(Tone, FlatOrDisabledToneAddsNoSections) {
    ToneParams flat;
    EXPECT_EQ(designEq(EqParams{}, flat, kFs).activeCount, 0u);

    ToneParams off;
    off.enabled = false;
    off.bassDb = 10.0;
    off.trebleDb = 10.0;
    EXPECT_EQ(designEq(EqParams{}, off, kFs).activeCount, 0u);
}

TEST(Tone, BandsKeepTheirSlotsBeforeTheShelves) {
    EqParams eq;
    eq.count = 1;
    eq.bands[0] = BandParams{FilterType::Peaking, true, 1000.0, 3.0, 1.0};
    ToneParams tone;
    tone.trebleDb = 2.0;
    const EqDesign d = designEq(eq, tone, kFs);
    ASSERT_EQ(d.activeCount, 2u);
    EXPECT_EQ(d.activeSlots[0], 0u);
    EXPECT_EQ(d.activeSlots[1], kTrebleSlot);
    EXPECT_DOUBLE_EQ(d.centerHz[kTrebleSlot], tone.trebleFreqHz);
}

TEST(Tone, AutoPreampCountsTheShelves) {
    Processor p;
    ASSERT_TRUE(p.prepare(kFs, 2));
    ChainParams params;  // auto-preamp on
    params.tone.bassDb = 9.0;
    p.setParams(params);
    // A low shelf reaches its full gain toward DC; the search grid starts at 10 Hz.
    EXPECT_NEAR(p.effectivePreampDb(), -9.0, 0.05);

    params.tone.enabled = false;
    p.setParams(params);
    EXPECT_DOUBLE_EQ(p.effectivePreampDb(), 0.0);
}

TEST(Tone, ProcessorAppliesTheShelves) {
    ChainParams params = quiet();
    params.tone.bassDb = -6.0;
    params.tone.trebleDb = 5.0;
    params.tone.trebleFreqHz = 4000.0;
    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(kFs, 1));

    constexpr double kAmp = 1e-3;
    const std::vector<double> h = test::impulseResponse([&](double x) {
        float s = static_cast<float>(x * kAmp);
        p.process(&s, &s, 1);
        return static_cast<double>(s) / kAmp;
    }, std::size_t{1} << 18);
    const EqDesign d = designEq(params.eq, sanitize(params, kFs).tone, kFs);
    for (double f : test::logFrequencies(20.0, 20000.0, 25)) {
        EXPECT_NEAR(gainToDb(std::abs(test::dftAt(h, f, kFs))), eqMagnitudeDb(d, f, kFs), 0.01)
            << "f=" << f;
    }
}

// ---- Stereo ---------------------------------------------------------------------------------

TEST(Stereo, DesignMatrix) {
    EXPECT_TRUE(designStereo(StereoParams{}).identity());

    StereoParams mono;
    mono.mono = true;
    mono.width = 1.7;  // ignored while mono is on
    EXPECT_EQ(designStereo(mono), (StereoDesign{0.5, 0.5, 0.5, 0.5}));

    StereoParams zero;
    zero.width = 0.0;
    EXPECT_EQ(designStereo(zero), designStereo(mono));

    StereoParams wide;
    wide.width = 2.0;
    EXPECT_EQ(designStereo(wide), (StereoDesign{1.5, -0.5, -0.5, 1.5}));

    StereoParams right;
    right.balance = 0.5;
    EXPECT_EQ(designStereo(right), (StereoDesign{0.5, 0.0, 0.0, 1.0}));
    StereoParams left;
    left.balance = -1.0;
    EXPECT_EQ(designStereo(left), (StereoDesign{1.0, 0.0, 0.0, 0.0}));
}

TEST(Stereo, MonoDownmixGivesEqualChannels) {
    ChainParams params = quiet();
    params.stereo.mono = true;
    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(kFs, 2));

    constexpr std::size_t kFrames = 4800;
    std::vector<float> in(kFrames * 2);
    const std::vector<float> s = test::sine(440.0, kFs, 0.4, kFrames);
    for (std::size_t n = 0; n < kFrames; ++n) in[2 * n] = s[n];  // right channel silent
    const std::vector<float> out = runProcessor(p, in);
    const std::size_t lat = p.latencyFrames();
    double peak = 0.0;
    for (std::size_t n = lat; n < kFrames; ++n) {
        ASSERT_EQ(out[2 * n], out[2 * n + 1]) << "frame " << n;
        EXPECT_NEAR(static_cast<double>(out[2 * n]), 0.5 * static_cast<double>(s[n - lat]), 1e-6);
        peak = std::max(peak, std::abs(static_cast<double>(out[2 * n])));
    }
    EXPECT_NEAR(peak, 0.2, 1e-3);
}

TEST(Stereo, WidthChangeRampsWithoutClicks) {
    Processor p;
    p.setParams(quiet());
    ASSERT_TRUE(p.prepare(kFs, 2));

    // Pure side signal (R = -L): width 0 removes it entirely.
    constexpr std::size_t kFrames = 9600;
    const std::vector<float> s = test::sine(1000.0, kFs, 0.5, kFrames);
    std::vector<float> in(kFrames * 2);
    for (std::size_t n = 0; n < kFrames; ++n) {
        in[2 * n] = s[n];
        in[2 * n + 1] = -s[n];
    }
    const double sineStep = test::maxStep(s, 1, 0);
    ChainParams narrow = quiet();
    narrow.stereo.width = 0.0;

    std::vector<float> out(in);
    const std::size_t ch = 2;
    for (std::size_t off = 0; off < kFrames; off += 128) {
        if (off == 2048) p.setParams(narrow);
        p.process(out.data() + off * ch, out.data() + off * ch, std::min<std::size_t>(128, kFrames - off));
    }
    EXPECT_LE(test::maxStep(out, 2, 0), sineStep * 1.01);
    EXPECT_LE(test::maxStep(out, 2, 1), sineStep * 1.01);
    // 20 ms after the change (plus latency and one block) the side signal is gone.
    for (std::size_t n = 2048 + 128 + 960 + p.latencyFrames(); n < kFrames; ++n) {
        EXPECT_NEAR(out[2 * n], 0.0f, 1e-6f) << "frame " << n;
    }
}

TEST(Stereo, MonoStreamsIgnoreTheStereoStage) {
    ChainParams params = quiet();
    params.stereo.balance = -1.0;  // would silence a right channel
    params.stereo.width = 2.0;
    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(kFs, 1));
    const std::vector<float> in = test::sine(300.0, kFs, 0.3, 2400);
    const std::vector<float> out = runProcessor(p, in);
    const std::size_t lat = p.latencyFrames();
    for (std::size_t n = lat; n < in.size(); ++n) ASSERT_FLOAT_EQ(out[n], in[n - lat]);
}

// ---- Compressor -----------------------------------------------------------------------------

CompressorDesign compressor(double thresholdDb, double ratio, double kneeDb, double attackMs,
                            double releaseMs, double makeupDb = 0.0) {
    CompressorParams c;
    c.enabled = true;
    c.thresholdDb = thresholdDb;
    c.ratio = ratio;
    c.kneeDb = kneeDb;
    c.attackMs = attackMs;
    c.releaseMs = releaseMs;
    c.makeupDb = makeupDb;
    return designCompressor(c, kFs);
}

TEST(Compressor, StaticCurve) {
    const CompressorDesign hard = compressor(-20.0, 4.0, 0.0, 10.0, 100.0);
    EXPECT_DOUBLE_EQ(compressorReductionDb(hard, -30.0), 0.0);
    EXPECT_DOUBLE_EQ(compressorReductionDb(hard, -20.0), 0.0);
    EXPECT_DOUBLE_EQ(compressorReductionDb(hard, -8.0), 9.0);  // 12 dB over, 3/4 of it removed

    const CompressorDesign soft = compressor(-20.0, 4.0, 8.0, 10.0, 100.0);
    EXPECT_DOUBLE_EQ(compressorReductionDb(soft, -24.0), 0.0);
    EXPECT_DOUBLE_EQ(compressorReductionDb(soft, -20.0), 0.75 * 8.0 / 8.0);  // slope * W / 8
    EXPECT_NEAR(compressorReductionDb(soft, -16.0), 0.75 * 4.0, 1e-12);       // meets the line
    EXPECT_DOUBLE_EQ(compressorReductionDb(soft, -10.0), 7.5);
    // Continuous and monotonic across the knee.
    double prev = 0.0;
    for (double x = -30.0; x <= 0.0; x += 0.01) {
        const double r = compressorReductionDb(soft, x);
        EXPECT_GE(r, prev - 1e-12);
        EXPECT_LE(r - prev, 0.75 * 0.01 + 1e-9);
        prev = r;
    }
}

TEST(Compressor, SettlesOnTheCurveWithTheAttackTime) {
    Compressor c;
    c.setDesign(compressor(-20.0, 4.0, 0.0, 5.0, 100.0, 2.0));
    c.reset();
    EXPECT_DOUBLE_EQ(c.gainDb(), 2.0);  // makeup only, for silence

    // Constant level 0.5 (-6.02 dBFS): 13.98 dB over, reduction 10.49 dB.
    const double levelDb = gainToDb(0.5);
    const double target = 2.0 - 0.75 * (levelDb + 20.0);
    const std::size_t attack = static_cast<std::size_t>(5.0 * kFs / 1000.0);
    std::vector<double> l(attack, 0.5), r(attack, 0.5);
    double* chans[2] = {l.data(), r.data()};
    c.process(chans, 2, attack);
    // One time constant: 1 - 1/e of the way from +2 dB to the target.
    const double expected = target + (2.0 - target) * std::exp(-1.0);
    EXPECT_NEAR(c.gainDb(), expected, 0.01);

    std::vector<double> more(static_cast<std::size_t>(kFs), 0.5);
    double* one[1] = {more.data()};
    c.process(one, 1, more.size());
    EXPECT_NEAR(c.gainDb(), target, 1e-6);
    EXPECT_NEAR(more.back(), 0.5 * dbToGain(target), 1e-6);
}

TEST(Compressor, OffIsBitExactPassThrough) {
    Compressor c;
    CompressorDesign d = compressor(-40.0, 10.0, 0.0, 1.0, 10.0);
    d.enabled = false;
    c.setDesign(d);
    c.reset();
    std::vector<float> noise = test::whiteNoise(0.9, 4096, 7);
    std::vector<double> x(noise.begin(), noise.end());
    const std::vector<double> ref = x;
    double* chans[1] = {x.data()};
    c.process(chans, 1, x.size());
    EXPECT_EQ(x, ref);
}

TEST(Compressor, TurningOffReleasesToUnity) {
    Compressor c;
    c.setDesign(compressor(-30.0, 8.0, 0.0, 1.0, 20.0));
    c.reset();
    std::vector<double> x(4800, 0.8);
    double* chans[1] = {x.data()};
    c.process(chans, 1, x.size());
    ASSERT_LT(c.gainDb(), -15.0);

    CompressorDesign off = c.design();
    off.enabled = false;
    c.setDesign(off);
    std::vector<double> y(static_cast<std::size_t>(kFs), 0.8);
    double* ychans[1] = {y.data()};
    c.process(ychans, 1, y.size());
    EXPECT_EQ(c.gainDb(), 0.0);
    for (std::size_t n = 1; n < y.size(); ++n) ASSERT_GE(y[n], y[n - 1]);  // monotonic recovery
    EXPECT_EQ(y.back(), 0.8);
}

TEST(Compressor, ProcessorKeepsTheLimiterGuarantee) {
    ChainParams params = quiet();
    params.preampDb = 12.0;
    params.compressor.enabled = true;
    params.compressor.thresholdDb = -30.0;
    params.compressor.ratio = 2.0;
    params.compressor.makeupDb = 20.0;
    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(kFs, 2));
    const std::vector<float> out = runProcessor(p, test::whiteNoise(0.9, 48000 * 2, 3));
    EXPECT_LE(test::maxAbs(out), dbToGain(params.limiter.ceilingDb) + 1e-6);
    EXPECT_EQ(p.limiterSafetyClampCount(), 0u);
}

// ---- Limiter off ----------------------------------------------------------------------------

TEST(LimiterOff, PassesPeaksAboveTheCeilingWithTheSameLatency) {
    ChainParams params = quiet();
    params.preampDb = 6.0;
    Processor on;
    on.setParams(params);
    ASSERT_TRUE(on.prepare(kFs, 1));

    params.limiter.enabled = false;
    Processor off;
    off.setParams(params);
    ASSERT_TRUE(off.prepare(kFs, 1));
    EXPECT_EQ(off.latencyFrames(), on.latencyFrames());

    const std::vector<float> in = test::sine(1000.0, kFs, 0.9, 4800);
    const std::vector<float> limited = runProcessor(on, in);
    const std::vector<float> open = runProcessor(off, in);
    EXPECT_LE(test::maxAbs(limited), dbToGain(-1.0) + 1e-6);
    EXPECT_NEAR(test::maxAbs(open), 0.9 * dbToGain(6.0), 1e-3);
}

TEST(LimiterOff, TurningItOffRecoversWithoutClicks) {
    ChainParams params = quiet();
    params.preampDb = 6.0;
    params.limiter.releaseMs = 20.0;
    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(kFs, 1));
    const std::vector<float> in = test::sine(200.0, kFs, 0.9, 19200);
    ChainParams off = params;
    off.limiter.enabled = false;
    std::vector<float> out(in);
    for (std::size_t offset = 0; offset < out.size(); offset += 256) {
        if (offset == 4096) p.setParams(off);
        p.process(out.data() + offset, out.data() + offset, std::min<std::size_t>(256, out.size() - offset));
    }
    // The open signal's own largest step, plus a small allowance for the gain recovery.
    const double openStep = test::maxStep(in, 1, 0) * dbToGain(6.0);
    EXPECT_LE(test::maxStep(out, 1, 0), openStep * 1.05);
    EXPECT_NEAR(test::maxAbs(std::vector<float>(out.end() - 4800, out.end())), 0.9 * dbToGain(6.0), 1e-3);
}

}  // namespace
}  // namespace sp::dsp
