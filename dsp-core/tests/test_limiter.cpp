// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <cmath>
#include <random>
#include <vector>

#include "sp/dsp/limiter.h"
#include "sp/dsp/math.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

struct Stereo {
    std::vector<double> l, r;
};

Stereo runLimiter(Limiter& lim, Stereo in, std::size_t block = 256) {
    for (std::size_t off = 0; off < in.l.size(); off += block) {
        const std::size_t n = std::min(block, in.l.size() - off);
        double* ch[2] = {in.l.data() + off, in.r.data() + off};
        lim.process(ch, 2, n);
    }
    return in;
}

Stereo hotSignal(int kind, std::size_t frames, double fs) {
    Stereo s{std::vector<double>(frames), std::vector<double>(frames)};
    std::mt19937 rng(static_cast<std::uint32_t>(kind) + 11);
    std::uniform_real_distribution<double> u(-1.0, 1.0);
    for (std::size_t n = 0; n < frames; ++n) {
        const double t = static_cast<double>(n) / fs;
        switch (kind) {
            case 0:  // white noise at about +12 dBFS
                s.l[n] = 4.0 * u(rng);
                s.r[n] = 4.0 * u(rng);
                break;
            case 1:  // quiet noise with +20 dB impulses
                s.l[n] = 0.1 * u(rng) + (n % 997 == 0 ? 10.0 : 0.0);
                s.r[n] = 0.1 * u(rng) - (n % 1499 == 0 ? 10.0 : 0.0);
                break;
            case 2:  // 50 Hz square wave, amplitude 3
                s.l[n] = std::sin(2 * kPi * 50 * t) >= 0 ? 3.0 : -3.0;
                s.r[n] = -s.l[n];
                break;
            default:  // swept sine 20 Hz -> 20 kHz, amplitude 2, one channel only
                s.l[n] = 2.0 * std::sin(2 * kPi * (20.0 * t + 1000.0 * t * t));
                s.r[n] = 0.0;
                break;
        }
    }
    return s;
}

TEST(Limiter, NeverExceedsTheCeiling) {
    for (double fs : {44100.0, 48000.0, 96000.0}) {
        for (double ceilingDb : {0.0, -1.0, -6.0}) {
            for (double lookaheadMs : {0.5, 1.5, 10.0}) {
                for (int kind = 0; kind < 4; ++kind) {
                    Limiter lim;
                    lim.prepare(fs, 2);
                    lim.setDesign(designLimiter({ceilingDb, lookaheadMs, 50.0}, fs));
                    const Stereo out = runLimiter(lim, hotSignal(kind, 48000, fs), 333);
                    const double ceiling = dbToGain(ceilingDb);
                    double peak = 0.0;
                    for (std::size_t n = 0; n < out.l.size(); ++n) {
                        peak = std::max({peak, std::abs(out.l[n]), std::abs(out.r[n])});
                    }
                    EXPECT_LE(peak, ceiling) << "fs=" << fs << " kind=" << kind;
                    // The algorithm itself must guarantee this, not the safety clamp.
                    EXPECT_EQ(lim.safetyClampCount(), 0u)
                        << "fs=" << fs << " ceil=" << ceilingDb << " la=" << lookaheadMs
                        << " kind=" << kind;
                }
            }
        }
    }
}

TEST(Limiter, IsAPureDelayBelowTheCeiling) {
    for (double fs : {44100.0, 48000.0, 96000.0}) {
        Limiter lim;
        lim.prepare(fs, 2);
        lim.setDesign(designLimiter({-1.0, 1.5, 50.0}, fs));
        const std::uint32_t latency = lim.latency();
        EXPECT_EQ(latency, static_cast<std::uint32_t>(std::round(1.5e-3 * fs)));

        const auto noise = test::whiteNoise(0.8, 8192, 5);
        Stereo in{std::vector<double>(noise.begin(), noise.end()),
                  std::vector<double>(noise.rbegin(), noise.rend())};
        const Stereo out = runLimiter(lim, in, 100);
        for (std::size_t n = 0; n < in.l.size(); ++n) {
            const double l = n >= latency ? in.l[n - latency] : 0.0;
            const double r = n >= latency ? in.r[n - latency] : 0.0;
            ASSERT_EQ(out.l[n], l) << n;
            ASSERT_EQ(out.r[n], r) << n;
        }
        EXPECT_DOUBLE_EQ(lim.takeMinGain(), 1.0);
    }
}

TEST(Limiter, IsStereoLinked) {
    const double fs = 48000.0;
    Limiter lim;
    lim.prepare(fs, 2);
    lim.setDesign(designLimiter({-6.0, 1.5, 50.0}, fs));
    const auto tone = test::sine(1000.0, fs, 1.0, 4800, 1);
    Stereo in{std::vector<double>(tone.begin(), tone.end()), std::vector<double>(tone.size())};
    for (std::size_t n = 0; n < in.r.size(); ++n) in.r[n] = 0.1 * in.l[n];  // quiet right channel
    const Stereo out = runLimiter(lim, in);
    // Right is always exactly 0.1 x left, i.e. both channels got the same gain.
    for (std::size_t n = 0; n < out.l.size(); ++n) {
        ASSERT_NEAR(out.r[n], 0.1 * out.l[n], 1e-12) << n;
    }
    EXPECT_LT(lim.takeMinGain(), dbToGain(-5.9));
}

TEST(Limiter, RecoversWithTheReleaseTime) {
    const double fs = 48000.0;
    const double releaseMs = 20.0;
    Limiter lim;
    lim.prepare(fs, 1);
    lim.setDesign(designLimiter({-1.0, 1.5, releaseMs}, fs));
    // 100 ms of hot signal, then a quiet tone.
    std::vector<double> x(static_cast<std::size_t>(fs));
    for (std::size_t n = 0; n < x.size(); ++n) {
        const double s = std::sin(2 * kPi * 500.0 * static_cast<double>(n) / fs);
        x[n] = n < 4800 ? 4.0 * s : 0.5 * s;
    }
    double* ch[1] = {x.data()};
    lim.process(ch, 1, 4800);
    EXPECT_LT(lim.takeMinGain(), 0.3);
    // After 10 release time constants the gain is back to (almost) unity.
    const auto tenTau = static_cast<std::size_t>(10 * releaseMs * fs / 1000);
    double* rest[1] = {x.data() + 4800};
    lim.process(rest, 1, tenTau);
    (void)lim.takeMinGain();
    double* tail[1] = {x.data() + 4800 + tenTau};
    lim.process(tail, 1, 4800);
    EXPECT_GT(lim.takeMinGain(), 0.999);
}

TEST(Limiter, LookaheadChangeUpdatesLatency) {
    const double fs = 48000.0;
    Limiter lim;
    lim.prepare(fs, 2);
    lim.setDesign(designLimiter({-1.0, 1.5, 50.0}, fs));
    EXPECT_EQ(lim.latency(), 72u);
    lim.setDesign(designLimiter({-1.0, 10.0, 50.0}, fs));
    EXPECT_EQ(lim.latency(), 480u);
    lim.setDesign(designLimiter({-1.0, 0.5, 50.0}, fs));
    EXPECT_EQ(lim.latency(), 24u);
}

}  // namespace
}  // namespace sp::dsp
