// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "sp/dsp/format.h"
#include "sp/dsp/math.h"
#include "sp/dsp/parametric_eq.h"
#include "sp/dsp/processor.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

BandParams band(FilterType type, double f, double gainDb, double q) {
    BandParams b;
    b.type = type;
    b.enabled = true;
    b.freqHz = f;
    b.gainDb = gainDb;
    b.q = q;
    return b;
}

ChainParams chainOf(std::initializer_list<BandParams> bands, bool autoPreamp = false) {
    ChainParams p;
    p.autoPreamp = autoPreamp;
    for (const auto& b : bands) p.eq.bands[p.eq.count++] = b;
    return p;
}

/// Processes `in` (interleaved) in host blocks of `block` frames, calling `between(frame)`
/// before each block.
template <typename Between>
std::vector<float> run(Processor& p, std::vector<float> in, std::size_t block, Between&& between) {
    const std::size_t ch = p.channels();
    const std::size_t frames = in.size() / ch;
    for (std::size_t off = 0; off < frames; off += block) {
        between(off);
        const std::size_t n = std::min(block, frames - off);
        p.process(in.data() + off * ch, in.data() + off * ch, n);
    }
    return in;
}

std::vector<float> run(Processor& p, std::vector<float> in, std::size_t block = 256) {
    return run(p, std::move(in), block, [](std::size_t) {});
}

// ---- Formats --------------------------------------------------------------------------------

TEST(Processor, RejectsUnsupportedFormats) {
    Processor p;
    EXPECT_FALSE(p.prepare(48000.0, 0));
    EXPECT_FALSE(p.prepare(48000.0, 3));
    EXPECT_FALSE(p.prepare(1000.0, 2));
    EXPECT_FALSE(p.prepare(std::numeric_limits<double>::quiet_NaN(), 2));
    EXPECT_FALSE(p.prepared());
    EXPECT_TRUE(p.prepare(48000.0, 2));
    EXPECT_TRUE(p.prepared());
}

class ProcessorFormatTest
    : public ::testing::TestWithParam<std::tuple<double, std::size_t>> {};

TEST_P(ProcessorFormatTest, FlatChainIsABitExactDelayForFloat) {
    const auto [fs, channels] = GetParam();
    Processor p;
    p.setParams(ChainParams{});
    ASSERT_TRUE(p.prepare(fs, channels));
    const std::uint32_t latency = p.latencyFrames();
    EXPECT_EQ(latency, static_cast<std::uint32_t>(std::round(1.5e-3 * fs)));

    auto in = test::whiteNoise(0.5, 9000 * channels, 21);
    const auto out = run(p, in, 480);
    for (std::size_t n = 0; n < out.size() / channels; ++n) {
        for (std::size_t c = 0; c < channels; ++c) {
            const float expected = n >= latency ? in[(n - latency) * channels + c] : 0.0f;
            ASSERT_EQ(out[n * channels + c], expected) << "frame " << n;
        }
    }
}

TEST_P(ProcessorFormatTest, FlatChainIsTransparentForInt16WithinDither) {
    const auto [fs, channels] = GetParam();
    Processor p;
    ASSERT_TRUE(p.prepare(fs, channels));
    const std::uint32_t latency = p.latencyFrames();

    const auto tone = test::sine(997.0, fs, 0.5, 9000, channels);
    std::vector<std::int16_t> in16(tone.size());
    TpdfDither inputDither(3);
    floatToInt16(tone.data(), in16.data(), tone.size(), inputDither);

    std::vector<float> buf(in16.size());
    int16ToFloat(in16.data(), buf.data(), in16.size());
    buf = run(p, buf);
    std::vector<std::int16_t> out16(buf.size());
    TpdfDither outputDither(4);
    floatToInt16(buf.data(), out16.data(), buf.size(), outputDither);

    for (std::size_t n = latency; n < out16.size() / channels; ++n) {
        for (std::size_t c = 0; c < channels; ++c) {
            const int diff = out16[n * channels + c] - in16[(n - latency) * channels + c];
            ASSERT_LE(std::abs(diff), 1) << "frame " << n;
        }
    }
}

INSTANTIATE_TEST_SUITE_P(RatesAndChannels, ProcessorFormatTest,
                         ::testing::Combine(::testing::Values(44100.0, 48000.0, 96000.0),
                                            ::testing::Values(std::size_t{1}, std::size_t{2})));

TEST(Processor, ResultDoesNotDependOnHostBlockSize) {
    ChainParams params = chainOf({band(FilterType::Peaking, 1000.0, 9.0, 1.0),
                                  band(FilterType::LowShelf, 80.0, 6.0, 0.7)});
    const auto in = test::whiteNoise(0.9, 2 * 5000, 8);
    std::vector<std::vector<float>> outputs;
    for (std::size_t block : {1, 7, 256, 333, 5000}) {
        Processor p;
        p.setParams(params);
        ASSERT_TRUE(p.prepare(48000.0, 2));
        outputs.push_back(run(p, in, block));
    }
    for (std::size_t i = 1; i < outputs.size(); ++i) ASSERT_EQ(outputs[i], outputs[0]);
}

// ---- Frequency response through the whole chain ---------------------------------------------

TEST(Processor, ChainResponseMatchesEqDesignAndPreamp) {
    const double fs = 48000.0;
    ChainParams params = chainOf({band(FilterType::HighPass, 30.0, 0.0, 0.7071),
                                  band(FilterType::Peaking, 250.0, 5.0, 1.5),
                                  band(FilterType::Peaking, 3000.0, -6.0, 3.0),
                                  band(FilterType::HighShelf, 8000.0, 4.0, 0.7)});
    params.preampDb = -3.0;
    Processor p;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(fs, 1));

    constexpr double kAmp = 1e-3;  // far below the limiter ceiling
    std::vector<double> h;
    std::size_t n = 0;
    h = test::impulseResponse([&](double x) {
        float s = static_cast<float>(x * kAmp);
        p.process(&s, &s, 1);
        ++n;
        return static_cast<double>(s) / kAmp;
    }, std::size_t{1} << 18);

    const EqDesign design = designEq(params.eq, fs);
    for (double f : test::logFrequencies(20.0, 20000.0, 30)) {
        const double expectedDb = eqMagnitudeDb(design, f, fs) - 3.0;
        if (expectedDb < -40.0) continue;
        EXPECT_NEAR(gainToDb(std::abs(test::dftAt(h, f, fs))), expectedDb, 0.01) << "f=" << f;
    }
}

TEST(Processor, AutoPreampCompensatesTheEqPeak) {
    const double fs = 48000.0;
    Processor p;
    ASSERT_TRUE(p.prepare(fs, 2));

    ChainParams boost = chainOf({band(FilterType::Peaking, 1000.0, 12.0, 1.0)}, true);
    p.setParams(boost);
    EXPECT_NEAR(p.effectivePreampDb(), -12.0, 1e-6);

    boost.preampDb = 2.0;
    p.setParams(boost);
    EXPECT_NEAR(p.effectivePreampDb(), -10.0, 1e-6);

    ChainParams cut = chainOf({band(FilterType::Peaking, 1000.0, -12.0, 1.0)}, true);
    cut.preampDb = 1.5;
    p.setParams(cut);
    EXPECT_NEAR(p.effectivePreampDb(), 1.5, 1e-6);

    // A full-scale tone at the boosted frequency comes out at full scale, not +12 dB, so the
    // limiter (ceiling 0 dBFS here) has nothing to do.
    ChainParams loud = chainOf({band(FilterType::Peaking, 1000.0, 12.0, 1.0)}, true);
    loud.limiter.ceilingDb = 0.0;
    Processor q;
    q.setParams(loud);
    ASSERT_TRUE(q.prepare(fs, 2));
    const auto out = run(q, test::sine(1000.0, fs, 0.99, 48000, 2));
    std::vector<float> tail(out.begin() + 2 * 24000, out.end());
    EXPECT_NEAR(test::maxAbs(tail), 0.99, 0.01);
    EXPECT_GT(q.limiterGainReductionDb(), -0.05f);
}

// ---- No clipping ------------------------------------------------------------------------------

TEST(Processor, NeverClipsWithExtremeBoosts) {
    const double fs = 48000.0;
    for (double ceilingDb : {-1.0, -0.1}) {
        ChainParams params = chainOf({band(FilterType::LowShelf, 60.0, 24.0, 0.7),
                                      band(FilterType::Peaking, 3000.0, 24.0, 1.0),
                                      band(FilterType::HighShelf, 12000.0, 24.0, 0.7)});
        params.preampDb = 6.0;
        params.limiter.ceilingDb = ceilingDb;
        const float ceiling = static_cast<float>(dbToGain(ceilingDb));

        std::vector<std::vector<float>> signals;
        signals.push_back(test::whiteNoise(1.0, 2 * 48000, 9));
        std::vector<float> square(2 * 48000);
        for (std::size_t n = 0; n < square.size() / 2; ++n) {
            const float v = std::sin(2 * kPi * 40.0 * static_cast<double>(n) / fs) >= 0 ? 0.9f : -0.9f;
            square[2 * n] = v;
            square[2 * n + 1] = -v;
        }
        signals.push_back(square);
        std::vector<float> clicks(2 * 48000, 0.0f);
        for (std::size_t n = 0; n < clicks.size(); n += 2 * 1201) clicks[n] = 1.0f;
        signals.push_back(clicks);

        for (const auto& s : signals) {
            Processor p;
            p.setParams(params);
            ASSERT_TRUE(p.prepare(fs, 2));
            const auto out = run(p, s, 192);
            EXPECT_LE(test::maxAbs(out), ceiling);
            EXPECT_EQ(p.limiterSafetyClampCount(), 0u);
        }
    }
}

// ---- Click-free parameter changes -------------------------------------------------------------

// Largest sample-to-sample step of a steady sine of amplitude `a` at `f`: 2·a·sin(π f / fs).
double sineStepBound(double a, double f, double fs) { return 2.0 * a * std::sin(kPi * f / fs); }

TEST(Processor, EqChangesAreClickFree) {
    const double fs = 48000.0, f = 1000.0, a = 0.05;
    for (bool autoPreamp : {false, true}) {
        Processor p;
        ChainParams params = chainOf({band(FilterType::Peaking, f, -12.0, 1.0)}, autoPreamp);
        p.setParams(params);
        ASSERT_TRUE(p.prepare(fs, 1));

        int toggle = 0;
        const auto out = run(p, test::sine(f, fs, a, 96000), 128, [&](std::size_t frame) {
            if (frame % 512 == 0) {  // every ~10.7 ms
                params.eq.bands[0].gainDb = (toggle++ % 2 == 0) ? 12.0 : -12.0;
                p.setParams(params);
            }
        });
        const double gmax = autoPreamp ? 1.0 : dbToGain(12.0);
        EXPECT_LT(test::maxStep(out, 1, 0, 4800), 1.2 * sineStepBound(a, f, fs) * gmax)
            << "autoPreamp=" << autoPreamp;
    }
}

TEST(Processor, ClickMetricDetectsAnUnrampedGainChange) {
    // Sanity check of the metric above: the same ±12 dB toggling, applied as an instant gain
    // step (what a naive implementation would do), fails it clearly.
    const double fs = 48000.0, f = 1000.0, a = 0.05;
    auto out = test::sine(f, fs, a, 96000);
    for (std::size_t n = 0; n < out.size(); ++n) {
        const double g = (n / 512) % 2 == 0 ? dbToGain(12.0) : dbToGain(-12.0);
        out[n] = static_cast<float>(static_cast<double>(out[n]) * g);
    }
    EXPECT_GT(test::maxStep(out, 1, 0, 4800), 3.0 * sineStepBound(a, f, fs) * dbToGain(12.0));
}

TEST(Processor, AbruptDf1CoefficientSwitchIsAlreadyGentle) {
    // Why the EQ uses Direct Form I: its state is real signal history, so even switching
    // coefficients instantly (no crossfade) does not create a discontinuity. The crossfade on
    // top of that smooths the loudness change itself.
    const double fs = 48000.0, f = 1000.0, a = 0.05;
    EqParams lo, hi;
    lo.bands[0] = band(FilterType::Peaking, f, -12.0, 1.0);
    hi.bands[0] = band(FilterType::Peaking, f, 12.0, 1.0);
    lo.count = hi.count = 1;
    const EqDesign dlo = designEq(lo, fs), dhi = designEq(hi, fs);
    ParametricEq eq;
    auto x = test::sine(f, fs, a, 96000);
    std::vector<double> buf(x.begin(), x.end());
    for (std::size_t off = 0; off < buf.size(); off += 512) {
        eq.setDesign((off / 512) % 2 == 0 ? dhi : dlo);
        double* ch[1] = {buf.data() + off};
        eq.process(ch, 1, std::min<std::size_t>(512, buf.size() - off));
    }
    std::vector<float> out(buf.begin(), buf.end());
    EXPECT_LT(test::maxStep(out, 1, 0, 4800), 1.2 * sineStepBound(a, f, fs) * dbToGain(12.0));
}

TEST(Processor, BypassIsClickFreeAndExact) {
    const double fs = 48000.0, f = 1000.0, a = 0.05;
    Processor p;
    ChainParams params = chainOf({band(FilterType::Peaking, f, 12.0, 1.0)});
    p.setParams(params);
    ASSERT_TRUE(p.prepare(fs, 2));

    const auto in = test::sine(f, fs, a, 96000, 2);
    const auto out = run(p, in, 240, [&](std::size_t frame) {
        if (frame > 0 && frame % 2400 == 0) {  // every 50 ms
            params.bypass = !params.bypass;
            p.setParams(params);
        }
    });
    EXPECT_LT(test::maxStep(out, 2, 0, 4800), 1.2 * sineStepBound(a, f, fs) * dbToGain(12.0));

    // Bypass was switched on at frame 93600 (the 39th toggle). Once its 20 ms crossfade is
    // over (frame 94560), the output must equal the latency-aligned input exactly.
    ASSERT_TRUE(params.bypass);
    const std::uint32_t latency = p.latencyFrames();
    for (std::size_t n = 94600; n < 96000; ++n) {
        ASSERT_EQ(out[2 * n], in[2 * (n - latency)]) << n;
    }
}

TEST(Processor, LookaheadChangeFadesInsteadOfClicking) {
    const double fs = 48000.0, f = 200.0, a = 0.5;
    Processor p;
    ChainParams params;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(fs, 1));
    EXPECT_EQ(p.latencyFrames(), 72u);

    const auto out = run(p, test::sine(f, fs, a, 48000), 256, [&](std::size_t frame) {
        if (frame == 256 * 40) {
            params.limiter.lookaheadMs = 5.0;
            p.setParams(params);
        }
    });
    EXPECT_EQ(p.latencyFrames(), 240u);
    // The fade is 2 ms: its slope adds at most a/96 per sample on top of the sine's own.
    EXPECT_LT(test::maxStep(out, 1, 0, 2000), sineStepBound(a, f, fs) + a / 96.0 * 1.1);
}

// ---- Robustness ----------------------------------------------------------------------------

TEST(Processor, RecoversFromNonFiniteInput) {
    const double fs = 48000.0;
    Processor p;
    p.setParams(chainOf({band(FilterType::Peaking, 500.0, 6.0, 1.0)}));
    ASSERT_TRUE(p.prepare(fs, 2));

    auto in = test::sine(440.0, fs, 0.3, 9600, 2);
    in[2 * 3000] = std::numeric_limits<float>::quiet_NaN();
    in[2 * 3000 + 1] = std::numeric_limits<float>::infinity();
    const auto out = run(p, in);
    for (float v : out) ASSERT_TRUE(std::isfinite(v));
    EXPECT_TRUE(p.takeNonFiniteFlag());
    EXPECT_FALSE(p.takeNonFiniteFlag());
    std::vector<float> tail(out.end() - 2 * 2000, out.end());
    EXPECT_GT(test::maxAbs(tail), 0.2);  // processing resumed
}

TEST(Processor, ResetClearsHistory) {
    Processor p;
    p.setParams(chainOf({band(FilterType::LowPass, 50.0, 0.0, 5.0)}));
    ASSERT_TRUE(p.prepare(48000.0, 1));
    (void)run(p, test::whiteNoise(0.9, 4800, 4));
    p.requestReset();
    const auto out = run(p, std::vector<float>(4800, 0.0f));
    for (float v : out) ASSERT_EQ(v, 0.0f);
}

TEST(Processor, ParametersSetBeforePrepareAreApplied) {
    Processor p;
    ChainParams params;
    params.bypass = true;
    params.limiter.lookaheadMs = 3.0;
    p.setParams(params);
    ASSERT_TRUE(p.prepare(48000.0, 1));
    EXPECT_EQ(p.latencyFrames(), 144u);
    const auto in = test::whiteNoise(0.5, 4800, 6);
    const auto out = run(p, in);
    for (std::size_t n = 144; n < out.size(); ++n) ASSERT_EQ(out[n], in[n - 144]);
}

}  // namespace
}  // namespace sp::dsp
