// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// GlobalState: SET/GET semantics, clamping, the duplicate-session guard, the instance limit and
// the persisted state file.
#include <gtest/gtest.h>

#include <unistd.h>

#include <array>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#include "codec.h"
#include "fx_test_util.h"
#include "global_state.h"
#include "instance.h"
#include "soundpower/protocol.h"
#include "sp/dsp/limits.h"
#include "state_file.h"

namespace sp::fx {
namespace {

using test::golden;

std::vector<std::uint8_t> get(GlobalState& g, Instance& who, std::uint32_t id) {
    std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> buf{};
    const int n = g.getParam(who, id, buf);
    EXPECT_GE(n, 0) << "GET 0x" << std::hex << id;
    return {buf.begin(), buf.begin() + std::max(n, 0)};
}

void writeFile(const std::string& path, const std::vector<std::uint8_t>& bytes) {
    std::FILE* f = std::fopen(path.c_str(), "wb");
    ASSERT_NE(f, nullptr) << path;
    ASSERT_EQ(std::fwrite(bytes.data(), 1, bytes.size(), f), bytes.size());
    std::fclose(f);
}

struct Scoped {
    GlobalState& g;
    Instance* i;
    Scoped(GlobalState& global, std::int32_t session, std::int32_t io = 1)
        : g(global), i(global.createInstance(session, io)) {}
    ~Scoped() { g.destroyInstance(i); }
    Instance& operator*() const { return *i; }
    Instance* operator->() const { return i; }
};

TEST(GlobalState, StartsWithProtocolDefaults) {
    GlobalState g;
    Scoped a(g, 1);
    EXPECT_EQ(get(g, *a, SP_PARAM_MASTER), golden("master_default"));
    EXPECT_EQ(get(g, *a, SP_PARAM_EQ_STATE), golden("eq_state_empty"));
    EXPECT_EQ(get(g, *a, SP_PARAM_LIMITER), golden("limiter_default"));
}

TEST(GlobalState, SetThenGetRoundTrips) {
    GlobalState g;
    Scoped a(g, 1);
    for (const auto& [id, name] : {std::pair{SP_PARAM_MASTER, "master_bypass"},
                                   std::pair{SP_PARAM_EQ_STATE, "eq_state_3band"},
                                   std::pair{SP_PARAM_LIMITER, "limiter_custom"}}) {
        ASSERT_EQ(g.setParam(id, golden(name)), 0) << name;
        EXPECT_EQ(get(g, *a, id), golden(name)) << name;
    }
}

TEST(GlobalState, OutOfRangeValuesAreClampedNotRejected) {
    GlobalState g;
    Scoped a(g, 1);
    dsp::ChainParams p;
    p.preampDb = 100.0;
    p.eq.count = 2;
    p.eq.bands[0] = {dsp::FilterType::Peaking, true, 5.0, -99.0, 0.0};
    p.eq.bands[1] = {dsp::FilterType::Peaking, true, 1e9, std::numeric_limits<double>::quiet_NaN(), 100.0};
    p.limiter = {std::numeric_limits<double>::infinity(), 0.0, 1e9};
    std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> buf{};
    for (const std::uint32_t id : {std::uint32_t{SP_PARAM_MASTER}, std::uint32_t{SP_PARAM_EQ_STATE},
                                   std::uint32_t{SP_PARAM_LIMITER}}) {
        const int n = encodeParam(id, p, buf);
        ASSERT_EQ(g.setParam(id, std::span(buf.data(), static_cast<std::size_t>(n))), 0);
    }

    const dsp::ChainParams q = g.params();
    EXPECT_EQ(q.preampDb, 30.0);
    EXPECT_EQ(q.eq.bands[0].freqHz, 10.0);
    EXPECT_EQ(q.eq.bands[0].gainDb, -30.0);
    EXPECT_DOUBLE_EQ(q.eq.bands[0].q, 0.05);
    EXPECT_DOUBLE_EQ(q.eq.bands[1].freqHz, 0.49 * 384000.0);  // per-instance Nyquist clamp later
    EXPECT_EQ(q.eq.bands[1].gainDb, 0.0);                     // NaN → default
    EXPECT_EQ(q.eq.bands[1].q, 40.0);
    EXPECT_EQ(q.limiter.ceilingDb, -1.0);  // non-finite → default
    EXPECT_EQ(q.limiter.lookaheadMs, 0.5);
    EXPECT_EQ(q.limiter.releaseMs, 1000.0);

    // GET reports the clamped values.
    dsp::ChainParams r;
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, get(g, *a, SP_PARAM_MASTER), r), 0);
    EXPECT_EQ(r.preampDb, 30.0);
}

TEST(GlobalState, RejectsUnknownReadOnlyAndMalformed) {
    GlobalState g;
    Scoped a(g, 1);
    ASSERT_EQ(g.setParam(SP_PARAM_MASTER, golden("master_bypass")), 0);
    const std::vector<std::uint8_t> payload(64, 0);
    EXPECT_EQ(g.setParam(SP_PARAM_PROTOCOL_INFO, payload), -EINVAL);
    EXPECT_EQ(g.setParam(SP_PARAM_STATUS, payload), -EINVAL);
    EXPECT_EQ(g.setParam(0x4242, payload), -EINVAL);
    EXPECT_EQ(g.setParam(SP_PARAM_MASTER, std::span(payload.data(), 7)), -EINVAL);
    EXPECT_EQ(get(g, *a, SP_PARAM_MASTER), golden("master_bypass"));  // unchanged

    std::array<std::uint8_t, 64> out{};
    EXPECT_EQ(g.getParam(*a, 0x4242, out), -EINVAL);
    EXPECT_EQ(g.getParam(*a, SP_PARAM_SPECTRUM, out), -EINVAL);  // phase 6
    EXPECT_EQ(g.getParam(*a, SP_PARAM_STATUS, std::span(out.data(), SP_STATUS_SIZE - 1)), -EINVAL);
    EXPECT_EQ(g.getParam(*a, SP_PARAM_PROTOCOL_INFO, std::span(out.data(), 15)), -EINVAL);
    ASSERT_EQ(g.setParam(SP_PARAM_EQ_STATE, golden("eq_state_3band")), 0);
    EXPECT_EQ(g.getParam(*a, SP_PARAM_EQ_STATE, std::span(out.data(), 51)), -EINVAL);
    EXPECT_EQ(g.getParam(*a, SP_PARAM_EQ_STATE, std::span(out.data(), 52)), 52);
}

TEST(GlobalState, StatusCountsInstancesAndReportsBypass) {
    GlobalState g;
    Scoped a(g, 1);
    Scoped b(g, 2);
    EXPECT_EQ(g.status(*a).instances, 2u);
    EXPECT_EQ(g.status(*a).flags & SP_STATUS_FLAG_BYPASSED, 0u);
    ASSERT_EQ(g.setParam(SP_PARAM_MASTER, golden("master_bypass")), 0);
    EXPECT_NE(g.status(*b).flags & SP_STATUS_FLAG_BYPASSED, 0u);
}

TEST(GlobalState, DuplicateSessionGuard) {
    GlobalState g;
    Instance* first = g.createInstance(5, 1);
    Instance* second = g.createInstance(5, 1);  // same session on the same output
    Instance* third = g.createInstance(5, 2);   // same session id on another output: independent
    Instance* fourth = g.createInstance(5, 1);
    EXPECT_FALSE(first->duplicate());
    EXPECT_TRUE(second->duplicate());
    EXPECT_FALSE(third->duplicate());
    EXPECT_TRUE(fourth->duplicate());
    EXPECT_NE(g.status(*second).flags & SP_STATUS_FLAG_DUPLICATE_SESSION, 0u);

    // A duplicate passes audio through even while enabled, and keeps reporting success.
    ASSERT_EQ(g.setParam(SP_PARAM_MASTER, golden("master_bypass")), 0);  // preamp -6.5, bypass on
    const std::vector<std::uint8_t> loud = {0, 0, 0, 0, 0, 0, 0xa0, 0xc1};  // preamp -20, no bypass
    ASSERT_EQ(g.setParam(SP_PARAM_MASTER, loud), 0);
    ASSERT_EQ(second->configure({48000, 1, SampleFormat::Float, false}), 0);
    second->setEnabled(true);
    std::vector<float> buf(4800, 0.5f);
    EXPECT_EQ(second->process(buf.data(), buf.data(), buf.size()), 0);
    for (const float v : buf) ASSERT_EQ(v, 0.5f);

    // When the active instance goes away, the oldest duplicate takes over and fades in.
    g.destroyInstance(first);
    EXPECT_FALSE(second->duplicate());
    EXPECT_TRUE(fourth->duplicate());
    EXPECT_EQ(second->process(buf.data(), buf.data(), buf.size()), 0);
    EXPECT_NEAR(buf.back(), 0.05f, 1e-4f);  // -20 dB

    // Destroying a duplicate promotes nobody.
    g.destroyInstance(fourth);
    EXPECT_FALSE(second->duplicate());
    g.destroyInstance(second);
    g.destroyInstance(third);
    EXPECT_EQ(g.instanceCount(), 0u);
}

TEST(GlobalState, InstanceLimit) {
    GlobalState g;
    std::vector<Instance*> all;
    for (int i = 0; i < SP_MAX_INSTANCES; ++i) {
        all.push_back(g.createInstance(i, 1));
        ASSERT_NE(all.back(), nullptr) << i;
    }
    EXPECT_EQ(g.createInstance(1000, 1), nullptr);
    g.destroyInstance(all.back());
    all.pop_back();
    all.push_back(g.createInstance(1000, 1));
    EXPECT_NE(all.back(), nullptr);
    for (Instance* i : all) g.destroyInstance(i);
    EXPECT_EQ(g.instanceCount(), 0u);
}

TEST(GlobalState, LoadsTheStateFileWhenItChanges) {
    const std::string path = test::tempPath("state.bin");
    std::remove(path.c_str());
    GlobalState g(path);
    Scoped a(g, 1);

    EXPECT_FALSE(g.reloadStateFileIfChanged());  // no file yet: defaults stay
    EXPECT_EQ(get(g, *a, SP_PARAM_MASTER), golden("master_default"));

    writeFile(path, golden("state_file_example"));
    EXPECT_TRUE(g.reloadStateFileIfChanged());
    test::expectSameParams(g.params(), dsp::sanitize(test::exampleParams(), GlobalState::kHighestSampleRate));
    EXPECT_FALSE(g.reloadStateFileIfChanged());  // unchanged

    // A live SET is not undone by an unchanged file...
    ASSERT_EQ(g.setParam(SP_PARAM_LIMITER, golden("limiter_default")), 0);
    EXPECT_FALSE(g.reloadStateFileIfChanged());
    EXPECT_EQ(get(g, *a, SP_PARAM_LIMITER), golden("limiter_default"));

    // ...but a rewritten file is applied.
    dsp::ChainParams p = test::exampleParams();
    p.eq.count = 1;
    writeFile(path, encodeStateFile(p));
    EXPECT_TRUE(g.reloadStateFileIfChanged());
    EXPECT_EQ(g.params().eq.count, 1u);
    EXPECT_EQ(get(g, *a, SP_PARAM_LIMITER), golden("limiter_custom"));

    // A corrupt file is ignored (and not re-parsed until it changes again).
    std::vector<std::uint8_t> bad = encodeStateFile(dsp::ChainParams{});
    bad[0] = 'X';
    writeFile(path, bad);
    EXPECT_FALSE(g.reloadStateFileIfChanged());
    EXPECT_EQ(g.params().eq.count, 1u);
    EXPECT_FALSE(g.reloadStateFileIfChanged());

    // Deleting the file keeps the current parameters.
    std::remove(path.c_str());
    EXPECT_FALSE(g.reloadStateFileIfChanged());
    EXPECT_EQ(g.params().eq.count, 1u);
}

TEST(GlobalState, NoStateFilePathMeansNoFile) {
    GlobalState g;
    EXPECT_FALSE(g.reloadStateFileIfChanged());
}

}  // namespace
}  // namespace sp::fx
