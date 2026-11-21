// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Instance: formats, enable/disable fades, -ENODATA, reconfiguration, parameter pushes.
#include <gtest/gtest.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

#include "codec.h"
#include "fx_test_util.h"
#include "global_state.h"
#include "instance.h"
#include "soundpower/protocol.h"
#include "sp/dsp/processor.h"
#include "test_util.h"

namespace sp::fx {
namespace {

constexpr double kFs = 48000.0;
constexpr std::size_t kFadeFrames = 480;  // 10 ms at 48 kHz

StreamConfig floatStereo(std::uint32_t rate = 48000) { return {rate, 2, SampleFormat::Float, false}; }

struct RunResult {
    std::vector<float> out;
    std::vector<int> status;  ///< process() result per block
};

/// Feeds `in` through `inst` in blocks of `block` frames, out of place.
RunResult run(Instance& inst, const std::vector<float>& in, std::size_t channels, std::size_t block) {
    RunResult r;
    r.out.assign(in.size(), 0.0f);
    const std::size_t frames = in.size() / channels;
    for (std::size_t f = 0; f < frames; f += block) {
        const std::size_t n = std::min(block, frames - f);
        r.status.push_back(inst.process(in.data() + f * channels, r.out.data() + f * channels, n));
    }
    return r;
}

/// The same input through a standalone processor with the same parameters.
std::vector<float> reference(const dsp::ChainParams& params, const std::vector<float>& in,
                             double fs, std::size_t channels) {
    dsp::Processor p;
    p.setParams(params);
    EXPECT_TRUE(p.prepare(fs, channels));
    std::vector<float> out(in.size());
    p.process(in.data(), out.data(), in.size() / channels);
    return out;
}

std::vector<std::uint8_t> eqBoostPayload(double gainDb) {
    dsp::ChainParams p;
    p.eq.count = 1;
    p.eq.bands[0] = {dsp::FilterType::Peaking, true, 1000.0, gainDb, 1.0};
    std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> buf{};
    const int n = encodeParam(SP_PARAM_EQ_STATE, p, buf);
    return {buf.begin(), buf.begin() + n};
}

std::vector<std::uint8_t> masterPayload(bool bypass, bool autoPreamp, float preampDb) {
    dsp::ChainParams p;
    p.bypass = bypass;
    p.autoPreamp = autoPreamp;
    p.preampDb = static_cast<double>(preampDb);
    std::array<std::uint8_t, SP_MASTER_SIZE> buf{};
    EXPECT_EQ(encodeParam(SP_PARAM_MASTER, p, buf), static_cast<int>(SP_MASTER_SIZE));
    return {buf.begin(), buf.end()};
}

double rms(const std::vector<float>& x, std::size_t from) {
    double sum = 0.0;
    for (std::size_t i = from; i < x.size(); ++i) sum += static_cast<double>(x[i]) * static_cast<double>(x[i]);
    return std::sqrt(sum / static_cast<double>(x.size() - from));
}

class InstanceTest : public ::testing::Test {
protected:
    Instance* make(std::int32_t session = 1, std::int32_t io = 10) {
        Instance* i = global.createInstance(session, io);
        EXPECT_NE(i, nullptr);
        live.push_back(i);
        return i;
    }
    void destroy(Instance* i) {
        live.erase(std::remove(live.begin(), live.end(), i), live.end());
        global.destroyInstance(i);
    }
    void TearDown() override {
        for (Instance* i : live) global.destroyInstance(i);
    }

    GlobalState global;
    std::vector<Instance*> live;
};

TEST_F(InstanceTest, ProcessBeforeConfigureFails) {
    Instance* inst = make();
    std::array<float, 64> buf{};
    EXPECT_EQ(inst->process(buf.data(), buf.data(), 32), -EINVAL);
    StreamConfig c;
    EXPECT_FALSE(inst->config(c));
    EXPECT_EQ(global.status(*inst).state, static_cast<std::uint32_t>(SP_STATE_UNINITIALIZED));
}

TEST_F(InstanceTest, RejectsUnsupportedFormats) {
    Instance* inst = make();
    EXPECT_EQ(inst->configure({48000, 0, SampleFormat::Float, false}), -EINVAL);
    EXPECT_EQ(inst->configure({48000, 3, SampleFormat::Float, false}), -EINVAL);
    EXPECT_EQ(inst->configure({4000, 2, SampleFormat::Float, false}), -EINVAL);
    EXPECT_EQ(inst->configure({768000, 2, SampleFormat::Float, false}), -EINVAL);
    EXPECT_EQ(inst->configure({48000, 2, static_cast<SampleFormat>(7), false}), -EINVAL);
    for (const std::uint32_t rate : {8000u, 44100u, 48000u, 96000u, 192000u, 384000u}) {
        EXPECT_EQ(inst->configure({rate, 1, SampleFormat::Int16, false}), 0) << rate;
        EXPECT_EQ(inst->configure({rate, 2, SampleFormat::Float, true}), 0) << rate;
    }
}

TEST_F(InstanceTest, DisabledInstancePassesThroughBitExactly) {
    Instance* inst = make();
    const std::vector<float> in = dsp::test::whiteNoise(1.5, 2 * 1000, 3);  // > 0 dBFS on purpose

    ASSERT_EQ(inst->configure(floatStereo()), 0);
    RunResult r = run(*inst, in, 2, 160);
    EXPECT_EQ(r.out, in);
    for (const int s : r.status) EXPECT_EQ(s, -ENODATA);

    std::vector<float> inPlace = in;
    EXPECT_EQ(inst->process(inPlace.data(), inPlace.data(), 1000), -ENODATA);
    EXPECT_EQ(inPlace, in);

    ASSERT_EQ(inst->configure({48000, 2, SampleFormat::Float, true}), 0);
    std::vector<float> acc(in.size(), 0.25f);
    EXPECT_EQ(inst->process(in.data(), acc.data(), 1000), -ENODATA);
    for (std::size_t i = 0; i < in.size(); ++i) ASSERT_EQ(acc[i], 0.25f + in[i]);

    ASSERT_EQ(inst->configure({48000, 2, SampleFormat::Int16, false}), 0);
    std::vector<std::int16_t> pcm(2 * 1000), pcmOut(2 * 1000, 7);
    for (std::size_t i = 0; i < pcm.size(); ++i) pcm[i] = static_cast<std::int16_t>(i * 37 - 30000);
    EXPECT_EQ(inst->process(pcm.data(), pcmOut.data(), 1000), -ENODATA);
    EXPECT_EQ(pcmOut, pcm);

    ASSERT_EQ(inst->configure({48000, 2, SampleFormat::Int16, true}), 0);
    std::vector<std::int16_t> pcmAcc(2 * 1000, 30000);
    EXPECT_EQ(inst->process(pcm.data(), pcmAcc.data(), 1000), -ENODATA);
    for (std::size_t i = 0; i < pcm.size(); ++i) {
        ASSERT_EQ(pcmAcc[i], static_cast<std::int16_t>(std::clamp(30000 + pcm[i], -32768, 32767)));
    }
}

TEST_F(InstanceTest, EnablingFadesInThenMatchesAStandaloneProcessor) {
    ASSERT_EQ(global.setParam(SP_PARAM_EQ_STATE, eqBoostPayload(6.0)), 0);
    ASSERT_EQ(global.setParam(SP_PARAM_MASTER, masterPayload(false, false, 0.0f)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);

    const std::vector<float> in = dsp::test::sine(1000.0, kFs, 0.25, 9600, 2);
    const RunResult r = run(*inst, in, 2, 240);
    for (const int s : r.status) EXPECT_EQ(s, 0);
    const std::vector<float> ref = reference(global.params(), in, kFs, 2);

    for (std::size_t i = 0; i < in.size(); ++i) {
        const double wetDiff = std::abs(static_cast<double>(ref[i]) - static_cast<double>(in[i]));
        const double outDiff = std::abs(static_cast<double>(r.out[i]) - static_cast<double>(in[i]));
        if (i / 2 < kFadeFrames) {
            // During the fade the output lies between the input and the processed signal.
            ASSERT_LE(outDiff, wetDiff + 1e-6) << i;
        } else {
            ASSERT_NEAR(r.out[i], ref[i], 1e-6) << i;
        }
    }
    // No click: the boosted 1 kHz sine (amplitude 0.5) moves at most 0.065 per sample. Jumping
    // between the dry and the (latency-shifted) processed signal would move up to ~0.75.
    EXPECT_LT(dsp::test::maxStep(r.out, 2, 0), 0.08);
}

TEST_F(InstanceTest, DisablingFadesOutThenReturnsNoData) {
    ASSERT_EQ(global.setParam(SP_PARAM_EQ_STATE, eqBoostPayload(6.0)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);
    const std::vector<float> warm = dsp::test::sine(1000.0, kFs, 0.25, 4800, 2);
    (void)run(*inst, warm, 2, 240);

    inst->setEnabled(false);
    const std::vector<float> in = dsp::test::sine(1000.0, kFs, 0.25, 4800, 2);
    const RunResult r = run(*inst, in, 2, 240);

    // 10 ms of fade = two 240-frame blocks returning 0, then -ENODATA. AudioFlinger allows 50 ms.
    ASSERT_EQ(r.status.size(), 20u);
    EXPECT_EQ(r.status[0], 0);
    EXPECT_EQ(r.status[1], -ENODATA);
    for (std::size_t b = 2; b < r.status.size(); ++b) EXPECT_EQ(r.status[b], -ENODATA);
    for (std::size_t i = 2 * kFadeFrames; i < in.size(); ++i) ASSERT_EQ(r.out[i], in[i]) << i;
}

TEST_F(InstanceTest, ReportsDisablingWhileFadingOut) {
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);
    std::vector<float> buf(2 * 960, 0.1f);
    EXPECT_EQ(inst->process(buf.data(), buf.data(), 960), 0);
    EXPECT_EQ(global.status(*inst).state, static_cast<std::uint32_t>(SP_STATE_ACTIVE));
    inst->setEnabled(false);
    EXPECT_EQ(inst->process(buf.data(), buf.data(), 100), 0);
    EXPECT_EQ(global.status(*inst).state, static_cast<std::uint32_t>(SP_STATE_DISABLING));
    EXPECT_EQ(inst->process(buf.data(), buf.data(), 960), -ENODATA);
    EXPECT_EQ(global.status(*inst).state, static_cast<std::uint32_t>(SP_STATE_INITIALIZED));
}

TEST_F(InstanceTest, ReEnablingStartsFromCleanState) {
    ASSERT_EQ(global.setParam(SP_PARAM_EQ_STATE, eqBoostPayload(9.0)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);
    (void)run(*inst, dsp::test::whiteNoise(0.3, 2 * 4800, 1), 2, 256);
    inst->setEnabled(false);
    (void)run(*inst, dsp::test::whiteNoise(0.3, 2 * 4800, 2), 2, 256);

    inst->setEnabled(true);
    const std::vector<float> in = dsp::test::whiteNoise(0.3, 2 * 4800, 4);
    const RunResult r = run(*inst, in, 2, 256);
    const std::vector<float> ref = reference(global.params(), in, kFs, 2);
    for (std::size_t i = 2 * kFadeFrames; i < in.size(); ++i) ASSERT_NEAR(r.out[i], ref[i], 1e-6) << i;
}

TEST_F(InstanceTest, ReapplyingTheSameConfigDoesNotRestart) {
    ASSERT_EQ(global.setParam(SP_PARAM_EQ_STATE, eqBoostPayload(6.0)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);
    const std::vector<float> in = dsp::test::whiteNoise(0.3, 2 * 9600, 9);
    const std::vector<float> ref = reference(global.params(), in, kFs, 2);

    std::vector<float> out(in.size());
    const std::size_t half = 4800;
    ASSERT_EQ(inst->process(in.data(), out.data(), half), 0);
    ASSERT_EQ(inst->configure(floatStereo()), 0);  // AudioFlinger repeats SET_CONFIG at times
    ASSERT_EQ(inst->process(in.data() + 2 * half, out.data() + 2 * half, half), 0);
    for (std::size_t i = 2 * kFadeFrames; i < in.size(); ++i) ASSERT_NEAR(out[i], ref[i], 1e-6) << i;
}

TEST_F(InstanceTest, ANewFormatFadesInFromTheInput) {
    ASSERT_EQ(global.setParam(SP_PARAM_EQ_STATE, eqBoostPayload(12.0)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);
    (void)run(*inst, dsp::test::sine(1000.0, kFs, 0.2, 4800, 2), 2, 240);

    ASSERT_EQ(inst->configure(floatStereo(44100)), 0);
    StreamConfig c;
    ASSERT_TRUE(inst->config(c));
    EXPECT_EQ(c.sampleRate, 44100u);
    const std::vector<float> in = dsp::test::sine(1000.0, 44100.0, 0.2, 441, 2);
    std::vector<float> out(in.size());
    ASSERT_EQ(inst->process(in.data(), out.data(), 441), 0);
    EXPECT_NEAR(out[0], in[0], 1e-6);  // the new engine starts dry
}

TEST_F(InstanceTest, ParameterChangesReachRunningInstancesAtTheirOwnRates) {
    Instance* a = make(1, 10);
    Instance* b = make(2, 11);
    ASSERT_EQ(a->configure(floatStereo(48000)), 0);
    ASSERT_EQ(b->configure({44100, 1, SampleFormat::Float, false}), 0);
    a->setEnabled(true);
    b->setEnabled(true);
    const std::vector<float> inA = dsp::test::sine(440.0, 48000.0, 0.3, 4800, 2);
    const std::vector<float> inB = dsp::test::sine(440.0, 44100.0, 0.3, 4410, 1);
    (void)run(*a, inA, 2, 480);
    (void)run(*b, inB, 1, 441);

    ASSERT_EQ(global.setParam(SP_PARAM_MASTER, masterPayload(false, false, -20.0f)), 0);
    const RunResult ra = run(*a, inA, 2, 480);
    const RunResult rb = run(*b, inB, 1, 441);
    // The preamp ramps over 10 ms; measure after it.
    EXPECT_NEAR(20.0 * std::log10(rms(ra.out, 2 * 2400) / rms(inA, 2 * 2400)), -20.0, 0.05);
    EXPECT_NEAR(20.0 * std::log10(rms(rb.out, 2205) / rms(inB, 2205)), -20.0, 0.05);
}

TEST_F(InstanceTest, Int16OutputRespectsTheLimiterCeiling) {
    ASSERT_EQ(global.setParam(SP_PARAM_EQ_STATE, eqBoostPayload(12.0)), 0);
    ASSERT_EQ(global.setParam(SP_PARAM_MASTER, masterPayload(false, false, 0.0f)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure({48000, 2, SampleFormat::Int16, false}), 0);
    inst->setEnabled(true);

    std::vector<std::int16_t> pcm(2 * 48000);
    for (std::size_t n = 0; n < 48000; ++n) {
        const auto v = static_cast<std::int16_t>(std::lround(29000.0 * std::sin(2.0 * 3.14159265358979 * 1000.0 * static_cast<double>(n) / kFs)));
        pcm[2 * n] = pcm[2 * n + 1] = v;
    }
    std::vector<std::int16_t> out(pcm.size());
    for (std::size_t f = 0; f < 48000; f += 960) {
        ASSERT_EQ(inst->process(pcm.data() + 2 * f, out.data() + 2 * f, 960), 0);
    }
    // Ceiling -1 dBFS = 29204 LSB; allow the ±1 LSB of TPDF dither.
    const auto peak = std::abs(*std::max_element(out.begin(), out.end(), [](auto x, auto y) {
        return std::abs(x) < std::abs(y);
    }));
    EXPECT_LE(peak, 29205);
    EXPECT_GT(peak, 28000);  // and it is actually limiting, not muting
}

TEST_F(InstanceTest, AccumulateAddsTheProcessedSignal) {
    ASSERT_EQ(global.setParam(SP_PARAM_MASTER, masterPayload(false, false, -6.0f)), 0);
    Instance* inst = make();
    ASSERT_EQ(inst->configure({48000, 1, SampleFormat::Float, true}), 0);
    inst->setEnabled(true);
    const std::vector<float> in = dsp::test::sine(500.0, kFs, 0.4, 4800, 1);
    std::vector<float> out(in.size(), 0.5f);
    ASSERT_EQ(inst->process(in.data(), out.data(), in.size()), 0);
    const std::vector<float> ref = reference(global.params(), in, kFs, 1);
    for (std::size_t i = kFadeFrames; i < in.size(); ++i) ASSERT_NEAR(out[i], 0.5f + ref[i], 1e-6);
}

TEST_F(InstanceTest, StatusDescribesTheStream) {
    Instance* inst = make(42, 7);
    ASSERT_EQ(inst->configure({44100, 1, SampleFormat::Int16, false}), 0);
    Status s = global.status(*inst);
    EXPECT_EQ(s.sessionId, 42);
    EXPECT_EQ(s.sampleRate, 44100u);
    EXPECT_EQ(s.channels, 1u);
    EXPECT_EQ(s.format, SP_FORMAT_PCM_16_BIT);
    EXPECT_EQ(s.state, static_cast<std::uint32_t>(SP_STATE_INITIALIZED));
    EXPECT_EQ(s.instances, 1u);

    inst->setEnabled(true);
    std::vector<std::int16_t> pcm(4410, 1000);
    ASSERT_EQ(inst->process(pcm.data(), pcm.data(), pcm.size()), 0);
    s = global.status(*inst);
    EXPECT_EQ(s.state, static_cast<std::uint32_t>(SP_STATE_ACTIVE));
    EXPECT_EQ(s.framesProcessed, 4410u);
    EXPECT_GT(s.cpuAvgPercent, 0.0f);
    EXPECT_GT(s.cpuPeakPercent, 0.0f);
    EXPECT_EQ(global.status(*inst).cpuPeakPercent, 0.0f);  // the peak resets on read
}

TEST_F(InstanceTest, ReportsNonFiniteInput) {
    Instance* inst = make();
    ASSERT_EQ(inst->configure(floatStereo()), 0);
    inst->setEnabled(true);
    std::vector<float> buf(2 * 480, 0.1f);
    buf[5] = std::numeric_limits<float>::infinity();
    ASSERT_EQ(inst->process(buf.data(), buf.data(), 480), 0);
    EXPECT_NE(global.status(*inst).flags & SP_STATUS_FLAG_NONFINITE_INPUT, 0u);
    EXPECT_EQ(global.status(*inst).flags & SP_STATUS_FLAG_NONFINITE_INPUT, 0u);  // cleared
}

}  // namespace
}  // namespace sp::fx
