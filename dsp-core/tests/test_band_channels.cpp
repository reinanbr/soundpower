// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Per-band channel routing: left, right, mid and side bands.

#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <initializer_list>
#include <vector>

#include "sp/dsp/math.h"
#include "sp/dsp/parametric_eq.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

constexpr double kFs = 48000.0;
constexpr std::size_t kFrames = 4096;

BandParams band(FilterType type, double f, double gainDb, double q, BandChannel channel) {
    BandParams b;
    b.type = type;
    b.enabled = true;
    b.freqHz = f;
    b.gainDb = gainDb;
    b.q = q;
    b.channel = channel;
    return b;
}

EqParams eqOf(std::initializer_list<BandParams> bands) {
    EqParams p;
    for (const auto& b : bands) p.bands[p.count++] = b;
    return p;
}

struct Stereo {
    std::vector<double> l, r;
};

Stereo noise(std::uint32_t seed) {
    const auto a = test::whiteNoise(0.5, kFrames, seed);
    const auto b = test::whiteNoise(0.5, kFrames, seed + 1);
    return {{a.begin(), a.end()}, {b.begin(), b.end()}};
}

Stereo runStereo(const EqParams& p, Stereo x, std::size_t block = 256) {
    ParametricEq eq;
    eq.setDesign(designEq(p, kFs));
    for (std::size_t i = 0; i < kFrames; i += block) {
        const std::size_t n = std::min(block, kFrames - i);
        double* ch[2] = {x.l.data() + i, x.r.data() + i};
        eq.process(ch, 2, n);
    }
    return x;
}

/// One band applied to a whole mono signal, as an independent reference.
void applyBand(const BandParams& b, std::vector<double>& x) {
    BandParams all = b;
    all.channel = BandChannel::All;
    ParametricEq eq;
    eq.setDesign(designEq(eqOf({all}), kFs));
    double* ch[1] = {x.data()};
    eq.process(ch, 1, x.size());
}

/// The routed chain computed band by band, converting to mid/side explicitly.
Stereo reference(const EqParams& p, Stereo x) {
    for (std::size_t i = 0; i < p.count; ++i) {
        const BandParams& b = p.bands[i];
        switch (b.channel) {
            case BandChannel::All:
                applyBand(b, x.l);
                applyBand(b, x.r);
                break;
            case BandChannel::Left: applyBand(b, x.l); break;
            case BandChannel::Right: applyBand(b, x.r); break;
            case BandChannel::Mid:
            case BandChannel::Side: {
                std::vector<double> m(kFrames), s(kFrames);
                for (std::size_t n = 0; n < kFrames; ++n) {
                    m[n] = 0.5 * (x.l[n] + x.r[n]);
                    s[n] = 0.5 * (x.l[n] - x.r[n]);
                }
                applyBand(b, b.channel == BandChannel::Mid ? m : s);
                for (std::size_t n = 0; n < kFrames; ++n) {
                    x.l[n] = m[n] + s[n];
                    x.r[n] = m[n] - s[n];
                }
                break;
            }
        }
    }
    return x;
}

void expectClose(const Stereo& a, const Stereo& b, double tol) {
    for (std::size_t n = 0; n < kFrames; ++n) {
        ASSERT_NEAR(a.l[n], b.l[n], tol) << "left, n=" << n;
        ASSERT_NEAR(a.r[n], b.r[n], tol) << "right, n=" << n;
    }
}

TEST(BandChannels, EachRouteMatchesAnExplicitReference) {
    for (BandChannel c : {BandChannel::All, BandChannel::Left, BandChannel::Right, BandChannel::Mid,
                          BandChannel::Side}) {
        const EqParams p = eqOf({band(FilterType::Peaking, 1000.0, 9.0, 1.4, c)});
        const Stereo x = noise(7);
        expectClose(runStereo(p, x), reference(p, x), 1e-12);
    }
}

TEST(BandChannels, MixedChainMatchesAnExplicitReference) {
    // Routes alternate, so the cascade enters and leaves mid/side several times.
    const EqParams p = eqOf({
        band(FilterType::LowShelf, 100.0, 4.0, 0.7, BandChannel::All),
        band(FilterType::Peaking, 1000.0, 6.0, 2.0, BandChannel::Left),
        band(FilterType::HighShelf, 5000.0, -5.0, 0.7, BandChannel::Mid),
        band(FilterType::Peaking, 3000.0, 8.0, 1.0, BandChannel::Side),
        band(FilterType::Peaking, 300.0, -6.0, 3.0, BandChannel::Right),
        band(FilterType::LowPass, 150.0, 0.0, 0.707, BandChannel::Side),
        band(FilterType::Peaking, 8000.0, 2.0, 1.0, BandChannel::All),
    });
    const Stereo x = noise(11);
    expectClose(runStereo(p, x, 37), reference(p, x), 1e-12);
}

TEST(BandChannels, LeftAndRightBandsLeaveTheOtherChannelUntouched) {
    const Stereo x = noise(3);
    const Stereo yl = runStereo(eqOf({band(FilterType::Peaking, 500.0, 12.0, 1.0, BandChannel::Left)}), x);
    const Stereo yr = runStereo(eqOf({band(FilterType::Peaking, 500.0, 12.0, 1.0, BandChannel::Right)}), x);
    for (std::size_t n = 0; n < kFrames; ++n) {
        ASSERT_EQ(yl.r[n], x.r[n]);
        ASSERT_EQ(yr.l[n], x.l[n]);
    }
}

TEST(BandChannels, MidAndSideSeparateCorrelatedAndOppositeContent) {
    const auto s = test::sine(1000.0, kFs, 0.5, kFrames);
    const std::vector<double> sine(s.begin(), s.end());
    std::vector<double> neg(kFrames);
    for (std::size_t n = 0; n < kFrames; ++n) neg[n] = -sine[n];

    const EqParams cutMid = eqOf({band(FilterType::Notch, 1000.0, 0.0, 2.0, BandChannel::Mid)});
    const EqParams cutSide = eqOf({band(FilterType::Notch, 1000.0, 0.0, 2.0, BandChannel::Side)});
    const Stereo centred{sine, sine};    // all mid
    const Stereo opposite{sine, neg};    // all side

    auto tailPeak = [](const std::vector<double>& y) {
        double m = 0.0;
        for (std::size_t n = kFrames / 2; n < kFrames; ++n) m = std::max(m, std::abs(y[n]));
        return m;
    };
    // A mid notch removes a centred tone but leaves an out-of-phase one, and vice versa.
    EXPECT_LT(tailPeak(runStereo(cutMid, centred).l), 1e-3);
    EXPECT_NEAR(tailPeak(runStereo(cutMid, opposite).l), 0.5, 1e-6);
    EXPECT_LT(tailPeak(runStereo(cutSide, opposite).r), 1e-3);
    EXPECT_NEAR(tailPeak(runStereo(cutSide, centred).r), 0.5, 1e-6);
}

TEST(BandChannels, MonoStreamsApplyOnlyAllAndMidBands) {
    const EqParams routed = eqOf({
        band(FilterType::Peaking, 1000.0, 6.0, 1.0, BandChannel::Left),
        band(FilterType::Peaking, 2000.0, 6.0, 1.0, BandChannel::Right),
        band(FilterType::Peaking, 400.0, -6.0, 1.0, BandChannel::Mid),
        band(FilterType::Peaking, 5000.0, 6.0, 1.0, BandChannel::Side),
        band(FilterType::Peaking, 9000.0, 3.0, 1.0, BandChannel::All),
    });
    const EqParams expected = eqOf({
        band(FilterType::Peaking, 400.0, -6.0, 1.0, BandChannel::All),
        band(FilterType::Peaking, 9000.0, 3.0, 1.0, BandChannel::All),
    });
    const auto x = test::whiteNoise(0.5, kFrames, 5);
    auto run = [&](const EqParams& p) {
        std::vector<double> y(x.begin(), x.end());
        ParametricEq eq;
        eq.setDesign(designEq(p, kFs));
        double* ch[1] = {y.data()};
        eq.process(ch, 1, y.size());
        return y;
    };
    const auto a = run(routed);
    const auto b = run(expected);
    for (std::size_t n = 0; n < kFrames; ++n) ASSERT_NEAR(a[n], b[n], 1e-12) << "n=" << n;
}

TEST(BandChannels, PathMagnitudesAddTheRoutedBands) {
    const EqDesign d = designEq(eqOf({
                                    band(FilterType::Peaking, 1000.0, 3.0, 1.0, BandChannel::All),
                                    band(FilterType::Peaking, 1000.0, 6.0, 1.0, BandChannel::Left),
                                    band(FilterType::Peaking, 1000.0, -4.0, 1.0, BandChannel::Side),
                                }),
                                kFs);
    EXPECT_NEAR(eqMagnitudeDb(d, 1000.0, kFs), 3.0, 1e-9);
    EXPECT_NEAR(eqMagnitudeDb(d, 1000.0, kFs, BandChannel::Left), 9.0, 1e-9);
    EXPECT_NEAR(eqMagnitudeDb(d, 1000.0, kFs, BandChannel::Right), 3.0, 1e-9);
    EXPECT_NEAR(eqMagnitudeDb(d, 1000.0, kFs, BandChannel::Mid), 3.0, 1e-9);
    EXPECT_NEAR(eqMagnitudeDb(d, 1000.0, kFs, BandChannel::Side), -1.0, 1e-9);
}

TEST(BandChannels, AutoPreampCountsOneSideOfEachPair) {
    auto peak = [](std::initializer_list<BandParams> bands) {
        return eqPeakGainDb(designEq(eqOf(bands), kFs), kFs);
    };
    // The same boost on left and right is +6 dB on each channel, not +12.
    EXPECT_NEAR(peak({band(FilterType::Peaking, 1000.0, 6.0, 1.0, BandChannel::Left),
                      band(FilterType::Peaking, 1000.0, 6.0, 1.0, BandChannel::Right)}),
                6.0, 1e-6);
    EXPECT_NEAR(peak({band(FilterType::Peaking, 1000.0, 6.0, 1.0, BandChannel::Mid),
                      band(FilterType::Peaking, 1000.0, 3.0, 1.0, BandChannel::Side)}),
                6.0, 1e-6);
    EXPECT_NEAR(peak({band(FilterType::Peaking, 1000.0, 3.0, 1.0, BandChannel::All),
                      band(FilterType::Peaking, 1000.0, 6.0, 1.0, BandChannel::Left),
                      band(FilterType::Peaking, 1000.0, 2.0, 1.0, BandChannel::Mid)}),
                11.0, 1e-6);
}

/// Output of `next` after taking over from `prev`, minus what `prev` would have produced.
double handoverError(const EqParams& prev, const EqParams& next) {
    const Stereo x = noise(21);
    constexpr std::size_t kHalf = kFrames / 2;
    ParametricEq oldBank;
    oldBank.setDesign(designEq(prev, kFs));
    Stereo warm = x;
    double* cw[2] = {warm.l.data(), warm.r.data()};
    oldBank.process(cw, 2, kHalf);

    ParametricEq newBank;
    newBank.setDesign(designEq(next, kFs));
    newBank.takeHistoryFrom(oldBank);

    Stereo a = x, b = x;
    double* ca[2] = {a.l.data() + kHalf, a.r.data() + kHalf};
    double* cb[2] = {b.l.data() + kHalf, b.r.data() + kHalf};
    oldBank.process(ca, 2, kHalf);
    newBank.process(cb, 2, kHalf);
    double err = 0.0;
    for (std::size_t n = kHalf; n < kFrames; ++n) {
        err = std::max({err, std::abs(a.l[n] - b.l[n]), std::abs(a.r[n] - b.r[n])});
    }
    return err;
}

TEST(BandChannels, HandoverAcrossRoutesIsSeamless) {
    const BandParams main = band(FilterType::Peaking, 800.0, 6.0, 1.0, BandChannel::All);
    // Near-identity bands appear or change route: the output must barely move.
    for (BandChannel from : {BandChannel::All, BandChannel::Left, BandChannel::Mid, BandChannel::Side}) {
        for (BandChannel to : {BandChannel::All, BandChannel::Right, BandChannel::Mid, BandChannel::Side}) {
            EqParams prev = eqOf({band(FilterType::Peaking, 5000.0, 0.01, 1.0, from), main});
            EqParams next = eqOf({band(FilterType::Peaking, 5000.0, 0.01, 1.0, to), main});
            EXPECT_LT(handoverError(prev, next), 2e-3) << static_cast<int>(from) << "->" << static_cast<int>(to);
        }
    }
    // A routed band that is the same in both banks continues exactly.
    const EqParams same = eqOf({band(FilterType::Peaking, 300.0, 5.0, 2.0, BandChannel::Side), main,
                                band(FilterType::Peaking, 2000.0, -3.0, 1.0, BandChannel::Left)});
    EXPECT_LT(handoverError(same, same), 1e-12);
}

}  // namespace
}  // namespace sp::dsp
