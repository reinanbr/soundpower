// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include <gtest/gtest.h>

#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

#include "codec.h"
#include "fx_test_util.h"
#include "soundpower/protocol.h"

namespace sp::fx {
namespace {

using test::golden;

std::vector<std::uint8_t> encode(std::uint32_t id, const dsp::ChainParams& p) {
    std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> buf{};
    const int n = encodeParam(id, p, buf);
    EXPECT_GE(n, 0);
    return {buf.begin(), buf.begin() + std::max(n, 0)};
}

TEST(Codec, EncodesMatchGoldenVectors) {
    EXPECT_EQ(encode(SP_PARAM_MASTER, dsp::ChainParams{}), golden("master_default"));
    EXPECT_EQ(encode(SP_PARAM_EQ_STATE, dsp::ChainParams{}), golden("eq_state_empty"));
    EXPECT_EQ(encode(SP_PARAM_LIMITER, dsp::ChainParams{}), golden("limiter_default"));
    EXPECT_EQ(encode(SP_PARAM_TONE, dsp::ChainParams{}), golden("tone_default"));
    EXPECT_EQ(encode(SP_PARAM_STEREO, dsp::ChainParams{}), golden("stereo_default"));
    EXPECT_EQ(encode(SP_PARAM_COMPRESSOR, dsp::ChainParams{}), golden("compressor_default"));

    const dsp::ChainParams p = test::exampleParams();
    EXPECT_EQ(encode(SP_PARAM_MASTER, p), golden("master_bypass"));
    EXPECT_EQ(encode(SP_PARAM_EQ_STATE, p), golden("eq_state_3band"));
    EXPECT_EQ(encode(SP_PARAM_LIMITER, p), golden("limiter_custom"));

    const dsp::ChainParams v11 = test::exampleParamsV11();
    EXPECT_EQ(encode(SP_PARAM_MASTER, v11), golden("master_limiter_off"));
    EXPECT_EQ(encode(SP_PARAM_TONE, v11), golden("tone_custom"));
    EXPECT_EQ(encode(SP_PARAM_STEREO, v11), golden("stereo_custom"));
    EXPECT_EQ(encode(SP_PARAM_COMPRESSOR, v11), golden("compressor_custom"));

    dsp::ChainParams v12;
    v12.preampDb = -2.5;
    v12.directVolume = true;
    EXPECT_EQ(encode(SP_PARAM_MASTER, v12), golden("master_direct_volume"));
}

TEST(Codec, DecodesTheDirectVolumeFlag) {
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, golden("master_direct_volume"), p), 0);
    EXPECT_TRUE(p.directVolume);
    EXPECT_TRUE(p.autoPreamp);
    EXPECT_EQ(p.preampDb, -2.5);
    // A 1.1 MASTER has no direct-volume bit: it reads as off.
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, golden("master_limiter_off"), p), 0);
    EXPECT_FALSE(p.directVolume);
}

TEST(Codec, DecodesProtocol11GoldenVectors) {
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, golden("master_limiter_off"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_EQ_STATE, golden("eq_state_3band"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_LIMITER, golden("limiter_custom"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_TONE, golden("tone_custom"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_STEREO, golden("stereo_custom"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_COMPRESSOR, golden("compressor_custom"), p), 0);
    test::expectSameParams(p, test::exampleParamsV11());

    // A 1.0 MASTER (no limiter-off bit) turns the limiter back on.
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, golden("master_default"), p), 0);
    EXPECT_TRUE(p.limiter.enabled);
}

TEST(Codec, BandChannelsRoundTrip) {
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_EQ_STATE, golden("eq_state_channels"), p), 0);
    ASSERT_EQ(p.eq.count, 5u);
    EXPECT_EQ(p.eq.bands[0].channel, dsp::BandChannel::Left);
    EXPECT_EQ(p.eq.bands[1].channel, dsp::BandChannel::Right);
    EXPECT_EQ(p.eq.bands[2].channel, dsp::BandChannel::Side);
    EXPECT_EQ(p.eq.bands[3].channel, dsp::BandChannel::Mid);
    EXPECT_EQ(p.eq.bands[4].channel, dsp::BandChannel::All);
    EXPECT_EQ(p.eq.bands[2].type, dsp::FilterType::HighShelf);
    EXPECT_EQ(p.eq.bands[2].freqHz, 6000.0);
    EXPECT_EQ(encode(SP_PARAM_EQ_STATE, p), golden("eq_state_channels"));
}

TEST(Codec, DecodesGoldenVectors) {
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, golden("master_bypass"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_EQ_STATE, golden("eq_state_3band"), p), 0);
    ASSERT_EQ(decodeParam(SP_PARAM_LIMITER, golden("limiter_custom"), p), 0);
    test::expectSameParams(p, test::exampleParams());
}

TEST(Codec, StatusMatchesGoldenVector) {
    Status s;
    s.sampleRate = 48000;
    s.channels = 2;
    s.format = SP_FORMAT_PCM_FLOAT;
    s.state = SP_STATE_ACTIVE;
    s.cpuAvgPercent = 1.25f;
    s.cpuPeakPercent = 4.5f;
    s.instances = 3;
    s.sessionId = -7;
    s.flags = SP_STATUS_FLAG_BYPASSED | SP_STATUS_FLAG_NONFINITE_INPUT;
    s.limiterGrDb = -2.75f;
    s.framesProcessed = 123456789012ull;
    std::array<std::uint8_t, SP_STATUS_SIZE> buf{};
    ASSERT_EQ(encodeStatus(s, buf), static_cast<int>(SP_STATUS_SIZE));
    EXPECT_EQ(std::vector<std::uint8_t>(buf.begin(), buf.end()), golden("status_example"));
}

TEST(Codec, ProtocolInfo) {
    std::array<std::uint8_t, SP_PROTOCOL_INFO_SIZE> buf{};
    ASSERT_EQ(encodeProtocolInfo(buf), static_cast<int>(SP_PROTOCOL_INFO_SIZE));
    sp_protocol_info_t info;
    std::memcpy(&info, buf.data(), sizeof(info));
    EXPECT_EQ(info.major, SP_PROTOCOL_VERSION_MAJOR);
    EXPECT_EQ(info.minor, SP_PROTOCOL_VERSION_MINOR);
    EXPECT_EQ(info.lib_version, (std::uint32_t{SP_VERSION_MAJOR} << 16) |
                                    (std::uint32_t{SP_VERSION_MINOR} << 8) | SP_VERSION_PATCH);
    EXPECT_EQ(info.max_bands, 32u);
    EXPECT_EQ(info.capabilities, SP_CAP_PARAMETRIC_EQ | SP_CAP_LIMITER | SP_CAP_TONE |
                                     SP_CAP_STEREO | SP_CAP_COMPRESSOR | SP_CAP_LIMITER_SWITCH |
                                         SP_CAP_BAND_CHANNEL | SP_CAP_DIRECT_VOLUME |
                                         SP_CAP_INPUT_VOLUME_SESSIONS);
}

TEST(Codec, InputVolumeSessionsMatchTheGoldenVectors) {
    InputVolumeSessions s;
    ASSERT_EQ(decodeInputVolumeSessions(golden("input_volume_sessions_example"), s), 0);
    ASSERT_EQ(s.count, 3u);
    EXPECT_TRUE(s.contains(1449));
    EXPECT_TRUE(s.contains(33001));
    EXPECT_TRUE(s.contains(7));
    EXPECT_FALSE(s.contains(0));
    std::array<std::uint8_t, SP_INPUT_VOLUME_SESSIONS_SIZE(3)> buf{};
    ASSERT_EQ(encodeInputVolumeSessions(s, buf), static_cast<int>(buf.size()));
    EXPECT_EQ(std::vector<std::uint8_t>(buf.begin(), buf.end()), golden("input_volume_sessions_example"));

    ASSERT_EQ(decodeInputVolumeSessions(golden("input_volume_sessions_empty"), s), 0);
    EXPECT_EQ(s.count, 0u);
}

TEST(Codec, InputVolumeSessionsAreBounded) {
    InputVolumeSessions s;
    s.ids[0] = 5;
    s.count = 1;
    // More than the limit, or fewer IDs than the count says: rejected, nothing changes.
    std::vector<std::uint8_t> tooMany(SP_INPUT_VOLUME_SESSIONS_SIZE(SP_MAX_INPUT_VOLUME_SESSIONS + 1), 0);
    tooMany[0] = SP_MAX_INPUT_VOLUME_SESSIONS + 1;
    EXPECT_EQ(decodeInputVolumeSessions(tooMany, s), -EINVAL);
    std::vector<std::uint8_t> short_ = golden("input_volume_sessions_example");
    short_.resize(short_.size() - 1);
    EXPECT_EQ(decodeInputVolumeSessions(short_, s), -EINVAL);
    EXPECT_EQ(decodeInputVolumeSessions({}, s), -EINVAL);
    ASSERT_EQ(s.count, 1u);
    EXPECT_EQ(s.ids[0], 5);

    // The full list fits a message; duplicates collapse.
    std::vector<std::uint8_t> full(SP_INPUT_VOLUME_SESSIONS_SIZE(SP_MAX_INPUT_VOLUME_SESSIONS), 0);
    full[0] = SP_MAX_INPUT_VOLUME_SESSIONS;
    ASSERT_EQ(decodeInputVolumeSessions(full, s), 0);
    EXPECT_EQ(s.count, 1u);  // 32 times session 0
    std::array<std::uint8_t, 4> small{};
    EXPECT_EQ(encodeInputVolumeSessions(s, small), -EINVAL);
}

TEST(Codec, EncodeFailsWhenTheBufferIsTooSmall) {
    const dsp::ChainParams p = test::exampleParams();
    std::array<std::uint8_t, SP_EQ_STATE_SIZE(3) - 1> small{};
    EXPECT_EQ(encodeParam(SP_PARAM_EQ_STATE, p, small), -EINVAL);
    std::array<std::uint8_t, SP_EQ_STATE_SIZE(3)> exact{};
    EXPECT_EQ(encodeParam(SP_PARAM_EQ_STATE, p, exact), static_cast<int>(SP_EQ_STATE_SIZE(3)));
    std::array<std::uint8_t, SP_STATUS_SIZE - 1> status{};
    EXPECT_EQ(encodeStatus(Status{}, status), -EINVAL);
    std::array<std::uint8_t, 15> info{};
    EXPECT_EQ(encodeProtocolInfo(info), -EINVAL);
}

TEST(Codec, OnlyTheSettableIdsDecode) {
    EXPECT_TRUE(isSettable(SP_PARAM_MASTER));
    EXPECT_TRUE(isSettable(SP_PARAM_EQ_STATE));
    EXPECT_TRUE(isSettable(SP_PARAM_LIMITER));
    EXPECT_TRUE(isSettable(SP_PARAM_TONE));
    EXPECT_TRUE(isSettable(SP_PARAM_STEREO));
    EXPECT_TRUE(isSettable(SP_PARAM_COMPRESSOR));
    std::vector<std::uint8_t> big(64, 0);
    for (const std::uint32_t id : {std::uint32_t{SP_PARAM_PROTOCOL_INFO}, std::uint32_t{SP_PARAM_STATUS},
                                   std::uint32_t{SP_PARAM_SPECTRUM}, 0u, 0x1000u, 0xffffffffu}) {
        SCOPED_TRACE(id);
        EXPECT_FALSE(isSettable(id));
        dsp::ChainParams p;
        EXPECT_EQ(decodeParam(id, big, p), -EINVAL);
        std::array<std::uint8_t, 64> out{};
        EXPECT_EQ(encodeParam(id, p, out), -EINVAL);
    }
}

TEST(Codec, ShortPayloadsAreRejectedAndLeaveParamsUnchanged) {
    const dsp::ChainParams before = test::exampleParams();
    for (const auto& [id, name] : {std::pair{SP_PARAM_MASTER, "master_default"},
                                   std::pair{SP_PARAM_EQ_STATE, "eq_state_3band"},
                                   std::pair{SP_PARAM_LIMITER, "limiter_default"},
                                   std::pair{SP_PARAM_TONE, "tone_custom"},
                                   std::pair{SP_PARAM_STEREO, "stereo_custom"},
                                   std::pair{SP_PARAM_COMPRESSOR, "compressor_custom"}}) {
        SCOPED_TRACE(name);
        const std::vector<std::uint8_t> full = golden(name);
        for (std::size_t n = 0; n < full.size(); ++n) {
            dsp::ChainParams p = before;
            EXPECT_EQ(decodeParam(id, std::span(full.data(), n), p), -EINVAL) << n;
            test::expectSameParams(p, before);
        }
    }
}

TEST(Codec, TrailingBytesAreIgnored) {
    // A newer minor version may append fields.
    std::vector<std::uint8_t> v = golden("master_bypass");
    ASSERT_EQ(v.size(), SP_MASTER_SIZE);
    v.insert(v.end(), {1, 2, 3, 4, 5});
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, v, p), 0);
    EXPECT_TRUE(p.bypass);
    EXPECT_EQ(p.preampDb, -6.5);

    // EQ_STATE: the band count, not the payload size, decides how many bands there are.
    v = golden("eq_state_3band");
    ASSERT_GE(v.size(), 12u);
    v.resize(v.size() + 16, 0xff);
    ASSERT_EQ(decodeParam(SP_PARAM_EQ_STATE, v, p), 0);
    EXPECT_EQ(p.eq.count, 3u);
}

TEST(Codec, UnknownFlagBitsAreIgnored) {
    std::vector<std::uint8_t> v = golden("master_default");
    ASSERT_EQ(v.size(), SP_MASTER_SIZE);
    v[3] = 0x80;  // flags bit 31
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_MASTER, v, p), 0);
    EXPECT_FALSE(p.bypass);
    EXPECT_TRUE(p.autoPreamp);
}

TEST(Codec, RejectsTooManyBandsAndUnknownTypes) {
    std::vector<std::uint8_t> v(SP_EQ_STATE_SIZE(33), 0);
    v[0] = 33;
    dsp::ChainParams p;
    EXPECT_EQ(decodeParam(SP_PARAM_EQ_STATE, v, p), -EINVAL);

    v = golden("eq_state_3band");
    ASSERT_GE(v.size(), 12u);
    v[4 + 16] = SP_BAND_TYPE_COUNT;  // second band's type
    EXPECT_EQ(decodeParam(SP_PARAM_EQ_STATE, v, p), -EINVAL);
    EXPECT_EQ(p.eq.count, 0u);  // unchanged

    v = golden("eq_state_3band");
    v[4 + 2] = SP_BAND_CHANNEL_COUNT;  // first band's channel
    EXPECT_EQ(decodeParam(SP_PARAM_EQ_STATE, v, p), -EINVAL);
    EXPECT_EQ(p.eq.count, 0u);
}

TEST(Codec, ThirtyTwoBandsRoundTrip) {
    dsp::ChainParams p;
    p.eq.count = 32;
    for (std::size_t i = 0; i < 32; ++i) {
        p.eq.bands[i] = {static_cast<dsp::FilterType>(i % 7), i % 3 != 0,
                         20.0 * static_cast<double>(i + 1), static_cast<double>(i) - 16.0, 0.25 + 0.125 * static_cast<double>(i)};
    }
    const std::vector<std::uint8_t> bytes = encode(SP_PARAM_EQ_STATE, p);
    ASSERT_EQ(bytes.size(), SP_EQ_STATE_SIZE(32));
    dsp::ChainParams q;
    ASSERT_EQ(decodeParam(SP_PARAM_EQ_STATE, bytes, q), 0);
    test::expectSameParams(p, q);
}

TEST(Codec, NonFiniteValuesDecodeAndAreLeftToSanitize) {
    std::vector<std::uint8_t> v = golden("limiter_default");
    ASSERT_GE(v.size(), 12u);
    const float nan = std::numeric_limits<float>::quiet_NaN();
    std::memcpy(v.data(), &nan, 4);
    dsp::ChainParams p;
    ASSERT_EQ(decodeParam(SP_PARAM_LIMITER, v, p), 0);
    EXPECT_TRUE(std::isnan(p.limiter.ceilingDb));
}

}  // namespace
}  // namespace sp::fx
