// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <cmath>
#include <vector>

#include "sp/dsp/limits.h"
#include "sp/dsp/parametric_eq.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

constexpr double kFs = 48000.0;

BandParams band(FilterType type, double f, double gainDb, double q) {
    BandParams b;
    b.type = type;
    b.enabled = true;
    b.freqHz = f;
    b.gainDb = gainDb;
    b.q = q;
    return b;
}

EqParams eqOf(std::initializer_list<BandParams> bands) {
    EqParams p;
    for (const auto& b : bands) p.bands[p.count++] = b;
    return p;
}

std::vector<double> eqImpulse(ParametricEq& eq) {
    return test::impulseResponse([&](double x) {
        double* ch[1] = {&x};
        eq.process(ch, 1, 1);
        return x;
    });
}

double analyticCascade(const EqParams& p, double f) {
    double mag = 1.0;
    for (std::size_t i = 0; i < p.count; ++i) {
        const BandParams& b = p.bands[i];
        if (!b.enabled) continue;
        mag *= test::analyticMagnitude(b.type, kFs, b.freqHz, b.gainDb, b.q, f);
    }
    return mag;
}

void expectCascadeMatches(const EqParams& p) {
    ParametricEq eq;
    eq.setDesign(designEq(p, kFs));
    const auto h = eqImpulse(eq);
    for (double f : test::logFrequencies(20.0, 20000.0, 40)) {
        const double expected = analyticCascade(p, f);
        const double measured = std::abs(test::dftAt(h, f, kFs));
        if (expected > 1e-3) {
            EXPECT_NEAR(gainToDb(measured), gainToDb(expected), 1e-4) << "f=" << f;
        } else {
            EXPECT_NEAR(measured, expected, 1e-7) << "f=" << f;
        }
        EXPECT_NEAR(eqMagnitudeDb(eq.design(), f, kFs), gainToDb(expected),
                    expected > 1e-3 ? 1e-6 : 1e3)
            << "f=" << f;
    }
}

TEST(ParametricEq, CascadeMatchesProductOfAnalyticResponses) {
    expectCascadeMatches(eqOf({
        band(FilterType::HighPass, 25.0, 0.0, 0.7071),
        band(FilterType::LowShelf, 105.0, 5.5, 0.7),
        band(FilterType::Peaking, 300.0, -3.0, 1.2),
        band(FilterType::Peaking, 2500.0, 4.0, 2.5),
        band(FilterType::Notch, 6000.0, 0.0, 8.0),
        band(FilterType::HighShelf, 10000.0, -2.5, 0.7),
    }));
}

TEST(ParametricEq, ThirtyTwoBandsMatchAnalyticProduct) {
    EqParams p;
    const auto freqs = test::logFrequencies(25.0, 16000.0, static_cast<int>(kMaxBands));
    for (std::size_t i = 0; i < kMaxBands; ++i) {
        const double gain = (i % 2 == 0 ? 1.0 : -1.0) * (1.0 + static_cast<double>(i % 5));
        p.bands[i] = band(FilterType::Peaking, freqs[i], gain, 1.5);
    }
    p.count = kMaxBands;
    const EqDesign d = designEq(p, kFs);
    EXPECT_EQ(d.activeCount, kMaxBands);
    expectCascadeMatches(p);
}

TEST(ParametricEq, DisabledAndIdentityBandsAreSkipped) {
    EqParams p = eqOf({
        band(FilterType::Peaking, 1000.0, 0.0, 1.0),   // identity
        band(FilterType::LowShelf, 100.0, 0.0, 0.7),   // identity
        band(FilterType::Peaking, 2000.0, 6.0, 1.0),   // active
    });
    p.bands[3] = band(FilterType::LowPass, 500.0, 0.0, 0.7);
    p.bands[3].enabled = false;
    p.count = 4;
    const EqDesign d = designEq(p, kFs);
    ASSERT_EQ(d.activeCount, 1u);
    EXPECT_EQ(d.activeSlots[0], 2u);
    EXPECT_FALSE(d.active[0]);
    EXPECT_TRUE(d.active[2]);

    // A design with no active bands is an exact pass-through.
    ParametricEq eq;
    eq.setDesign(designEq(eqOf({band(FilterType::Peaking, 1000.0, 0.0, 1.0)}), kFs));
    auto x = test::whiteNoise(1.0, 1000, 7);
    std::vector<double> buf(x.begin(), x.end());
    double* ch[1] = {buf.data()};
    eq.process(ch, 1, buf.size());
    for (std::size_t i = 0; i < buf.size(); ++i) EXPECT_EQ(buf[i], static_cast<double>(x[i]));
}

TEST(ParametricEq, ChannelsAreIndependent) {
    const EqParams p = eqOf({band(FilterType::Peaking, 700.0, 9.0, 2.0),
                             band(FilterType::HighPass, 80.0, 0.0, 0.7071)});
    const EqDesign d = designEq(p, kFs);
    const auto left = test::whiteNoise(0.5, 2048, 1);
    const auto right = test::whiteNoise(0.5, 2048, 2);

    ParametricEq stereo;
    stereo.setDesign(d);
    std::vector<double> l(left.begin(), left.end()), r(right.begin(), right.end());
    double* ch[2] = {l.data(), r.data()};
    stereo.process(ch, 2, l.size());

    for (const auto* src : {&left, &right}) {
        ParametricEq mono;
        mono.setDesign(d);
        std::vector<double> m(src->begin(), src->end());
        double* mc[1] = {m.data()};
        mono.process(mc, 1, m.size());
        const auto& got = src == &left ? l : r;
        for (std::size_t i = 0; i < m.size(); ++i) ASSERT_EQ(got[i], m[i]);
    }
}

TEST(ParametricEq, HistoryHandoverWithSameDesignContinuesBitExactly) {
    const EqParams p = eqOf({band(FilterType::LowShelf, 90.0, 6.0, 0.7),
                             band(FilterType::Peaking, 1200.0, -4.0, 3.0),
                             band(FilterType::HighShelf, 9000.0, 3.0, 0.7)});
    const EqDesign d = designEq(p, kFs);
    const auto x = test::whiteNoise(0.5, 4096, 3);

    // Reference: one bank processes everything.
    ParametricEq ref;
    ref.setDesign(d);
    std::vector<double> a(x.begin(), x.end());
    double* ca[1] = {a.data()};
    ref.process(ca, 1, a.size());

    // Same signal, but a second bank takes over half way.
    ParametricEq first, second;
    first.setDesign(d);
    std::vector<double> b(x.begin(), x.end());
    double* c1[1] = {b.data()};
    first.process(c1, 1, 2048);
    second.setDesign(d);
    second.takeHistoryFrom(first);
    double* c2[1] = {b.data() + 2048};
    second.process(c2, 1, 2048);

    for (std::size_t i = 0; i < a.size(); ++i) ASSERT_EQ(a[i], b[i]) << "i=" << i;
}

TEST(ParametricEq, HistoryHandoverIntoNewSlotStartsAsPassThrough) {
    // Old chain: slot 1 only. New chain: slots 0 and 1, slot 0 being a near-identity filter.
    // The new chain's output right after the handover must be close to the old chain's.
    EqParams oldP;
    oldP.bands[1] = band(FilterType::Peaking, 1000.0, 6.0, 1.0);
    oldP.count = 2;
    EqParams newP = oldP;
    newP.bands[0] = band(FilterType::Peaking, 5000.0, 0.01, 1.0);

    const auto x = test::sine(440.0, kFs, 0.5, 4096);
    constexpr std::size_t kHalf = 2048;

    // Warm up the old bank on the first half of the signal.
    ParametricEq oldBank;
    oldBank.setDesign(designEq(oldP, kFs));
    std::vector<double> warm(x.begin(), x.begin() + kHalf);
    double* cw[1] = {warm.data()};
    oldBank.process(cw, 1, kHalf);

    // Second half through: the old bank continuing, the new bank after a handover, and the
    // new bank started cold.
    auto run = [&](ParametricEq bank) {
        std::vector<double> y(x.begin() + kHalf, x.end());
        double* c[1] = {y.data()};
        bank.process(c, 1, y.size());
        return y;
    };
    ParametricEq handedOver, cold;
    handedOver.setDesign(designEq(newP, kFs));
    handedOver.takeHistoryFrom(oldBank);
    cold.setDesign(designEq(newP, kFs));

    const auto viaOld = run(oldBank);
    const auto viaHandover = run(handedOver);
    const auto viaCold = run(cold);

    double handoverDiff = 0.0, coldDiff = 0.0;
    for (std::size_t i = 0; i < viaOld.size(); ++i) {
        handoverDiff = std::max(handoverDiff, std::abs(viaHandover[i] - viaOld[i]));
        coldDiff = std::max(coldDiff, std::abs(viaCold[i] - viaOld[i]));
    }
    // +0.01 dB at 5 kHz barely changes a 440 Hz sine, so a correct handover is nearly seamless,
    // while a cold start begins from silence.
    EXPECT_LT(handoverDiff, 1e-3);
    EXPECT_GT(coldDiff, 100.0 * handoverDiff);
}

TEST(ParametricEq, PeakGainFindsTheCurveMaximum) {
    EXPECT_DOUBLE_EQ(eqPeakGainDb(designEq(EqParams{}, kFs), EqParams{}, kFs), 0.0);

    const EqParams single = eqOf({band(FilterType::Peaking, 1000.0, 12.0, 1.0)});
    EXPECT_NEAR(eqPeakGainDb(designEq(single, kFs), single, kFs), 12.0, 1e-9);

    const EqParams stacked = eqOf({band(FilterType::Peaking, 1000.0, 6.0, 1.0),
                                   band(FilterType::Peaking, 1000.0, 6.0, 1.0)});
    EXPECT_NEAR(eqPeakGainDb(designEq(stacked, kFs), stacked, kFs), 12.0, 1e-9);

    const EqParams cutOnly = eqOf({band(FilterType::Peaking, 1000.0, -6.0, 1.0)});
    EXPECT_NEAR(eqPeakGainDb(designEq(cutOnly, kFs), cutOnly, kFs), 0.0, 1e-3);

    const EqParams shelf = eqOf({band(FilterType::LowShelf, 100.0, 8.0, 0.7071)});
    EXPECT_NEAR(eqPeakGainDb(designEq(shelf, kFs), shelf, kFs), 8.0, 0.05);
}

}  // namespace
}  // namespace sp::dsp
