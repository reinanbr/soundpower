// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Host harness for the Android effect API: drives libsoundpower through the same command
// sequences AudioFlinger and the effects factory use, plus malformed input an untrusted app
// could send. The last test dlopen()s the real shared library, as the effects factory does.
#include <gtest/gtest.h>

#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "android/effect_api.h"
#include "codec.h"
#include "fx_test_util.h"
#include "global_state.h"
#include "soundpower/protocol.h"
#include "test_util.h"

namespace sp::fx {
namespace {

using test::golden;

std::string uuidString(const effect_uuid_t& u) {
    char s[40];
    std::snprintf(s, sizeof(s), "%08x-%04x-%04x-%04x-%02x%02x%02x%02x%02x%02x", u.timeLow, u.timeMid,
                  u.timeHiAndVersion, u.clockSeq, u.node[0], u.node[1], u.node[2], u.node[3],
                  u.node[4], u.node[5]);
    return s;
}

effect_config_t makeConfig(std::uint32_t rate = 48000, std::uint32_t channels = AUDIO_CHANNEL_OUT_STEREO,
                           audio_format_t format = AUDIO_FORMAT_PCM_FLOAT,
                           effect_buffer_access_e access = EFFECT_BUFFER_ACCESS_WRITE) {
    effect_config_t c{};
    for (buffer_config_t* b : {&c.inputCfg, &c.outputCfg}) {
        b->samplingRate = rate;
        b->channels = channels;
        b->format = static_cast<std::uint8_t>(format);
        b->mask = EFFECT_CONFIG_ALL;
    }
    c.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    c.outputCfg.accessMode = static_cast<std::uint8_t>(access);
    return c;
}

std::vector<std::uint8_t> paramCommand(std::uint32_t id, const std::vector<std::uint8_t>& value) {
    std::vector<std::uint8_t> cmd(sizeof(effect_param_t) + 4 + value.size());
    const effect_param_t header{0, 4, static_cast<std::uint32_t>(value.size())};
    std::memcpy(cmd.data(), &header, sizeof(header));
    std::memcpy(cmd.data() + sizeof(header), &id, 4);
    if (!value.empty()) std::memcpy(cmd.data() + sizeof(header) + 4, value.data(), value.size());
    return cmd;
}

/// One effect handle plus helpers that send commands the way AudioFlinger does.
struct Effect {
    effect_handle_t h = nullptr;

    std::int32_t send(std::uint32_t code, std::vector<std::uint8_t> data, std::int32_t* status) {
        std::int32_t reply = 12345;
        std::uint32_t replySize = sizeof(reply);
        const std::int32_t r = (*h)->command(h, code, static_cast<std::uint32_t>(data.size()),
                                             data.empty() ? nullptr : data.data(), &replySize, &reply);
        if (r == 0) {
            EXPECT_EQ(replySize, sizeof(reply));
            if (status != nullptr) *status = reply;
        }
        return r;
    }

    /// Returns the command's reply status (or its return code if it failed outright).
    std::int32_t call(std::uint32_t code, std::vector<std::uint8_t> data = {}) {
        std::int32_t status = 0;
        const std::int32_t r = send(code, std::move(data), &status);
        return r != 0 ? r : status;
    }

    std::int32_t setConfig(const effect_config_t& c) {
        std::vector<std::uint8_t> data(sizeof(c));
        std::memcpy(data.data(), &c, sizeof(c));
        return call(EFFECT_CMD_SET_CONFIG, data);
    }

    effect_config_t getConfig() {
        effect_config_t c{};
        std::uint32_t size = sizeof(c);
        EXPECT_EQ((*h)->command(h, EFFECT_CMD_GET_CONFIG, 0, nullptr, &size, &c), 0);
        EXPECT_EQ(size, sizeof(c));
        return c;
    }

    std::int32_t setParam(std::uint32_t id, const std::vector<std::uint8_t>& value) {
        return call(EFFECT_CMD_SET_PARAM, paramCommand(id, value));
    }

    /// Like AudioEffect::getParameter(): one buffer is both command and reply.
    std::int32_t getParam(std::uint32_t id, std::vector<std::uint8_t>& value, std::uint32_t capacity = 256) {
        std::vector<std::uint8_t> buf(sizeof(effect_param_t) + 4 + capacity, 0xee);
        const effect_param_t header{0, 4, capacity};
        std::memcpy(buf.data(), &header, sizeof(header));
        std::memcpy(buf.data() + sizeof(header), &id, 4);
        std::uint32_t replySize = static_cast<std::uint32_t>(buf.size());
        const std::int32_t r = (*h)->command(h, EFFECT_CMD_GET_PARAM, sizeof(effect_param_t) + 4,
                                             buf.data(), &replySize, buf.data());
        if (r != 0) return r;
        effect_param_t out;
        std::memcpy(&out, buf.data(), sizeof(out));
        EXPECT_EQ(out.psize, 4u);
        EXPECT_EQ(replySize, sizeof(effect_param_t) + 4 + out.vsize);
        std::uint32_t echoedId;
        std::memcpy(&echoedId, buf.data() + sizeof(out), 4);
        EXPECT_EQ(echoedId, id);
        value.assign(buf.begin() + sizeof(out) + 4, buf.begin() + sizeof(out) + 4 + out.vsize);
        return out.status;
    }

    std::int32_t process(std::vector<float>& in, std::vector<float>& out, std::size_t frames) {
        audio_buffer_t a{};
        a.frameCount = frames;
        a.f32 = in.data();
        audio_buffer_t b{};
        b.frameCount = frames;
        b.f32 = out.data();
        return (*h)->process(h, &a, &b);
    }
};

class EffectApi : public ::testing::Test {
protected:
    Effect create(std::int32_t session, std::int32_t io = 13) {
        Effect e;
        EXPECT_EQ(android::createEffect(global, &android::descriptor().uuid, session, io, &e.h), 0);
        handles.push_back(e.h);
        return e;
    }
    void release(Effect& e) {
        handles.erase(std::remove(handles.begin(), handles.end(), e.h), handles.end());
        EXPECT_EQ(android::releaseEffect(e.h), 0);
        e.h = nullptr;
    }
    /// What AudioFlinger does after creating an effect: INIT, SET_CONFIG, ENABLE.
    Effect createRunning(std::int32_t session, const effect_config_t& config = makeConfig()) {
        Effect e = create(session);
        EXPECT_EQ(e.call(EFFECT_CMD_INIT), 0);
        EXPECT_EQ(e.setConfig(config), 0);
        EXPECT_EQ(e.call(EFFECT_CMD_ENABLE), 0);
        return e;
    }
    void TearDown() override {
        for (effect_handle_t h : handles) android::releaseEffect(h);
        EXPECT_EQ(global.instanceCount(), 0u);
    }

    GlobalState global;
    std::vector<effect_handle_t> handles;
};

// ---- Library interface --------------------------------------------------------------------

TEST(EffectLibrary, ExportsAValidLibraryDescriptor) {
    const audio_effect_library_t& lib = AUDIO_EFFECT_LIBRARY_INFO_SYM;
    EXPECT_EQ(lib.tag, static_cast<std::uint32_t>(AUDIO_EFFECT_LIBRARY_TAG));
    EXPECT_EQ(EFFECT_API_VERSION_MAJOR(lib.version), 3u);
    EXPECT_STREQ(lib.name, SP_EFFECT_NAME);
    EXPECT_STREQ(lib.implementor, SP_EFFECT_IMPLEMENTOR);
    EXPECT_NE(lib.create_effect, nullptr);
    EXPECT_NE(lib.release_effect, nullptr);
    EXPECT_NE(lib.get_descriptor, nullptr);
}

TEST(EffectLibrary, DescriptorMatchesTheProtocol) {
    effect_descriptor_t d{};
    const effect_uuid_t impl = SP_EFFECT_IMPL_UUID_INIT;
    ASSERT_EQ(AUDIO_EFFECT_LIBRARY_INFO_SYM.get_descriptor(&impl, &d), 0);
    EXPECT_EQ(uuidString(d.uuid), SP_EFFECT_IMPL_UUID_STR);
    EXPECT_EQ(uuidString(d.type), SP_EFFECT_TYPE_UUID_STR);
    EXPECT_EQ(d.apiVersion, static_cast<std::uint32_t>(EFFECT_CONTROL_API_VERSION));
    EXPECT_EQ(d.flags & EFFECT_FLAG_TYPE_MASK, static_cast<std::uint32_t>(EFFECT_FLAG_TYPE_INSERT));
    EXPECT_EQ(d.flags & EFFECT_FLAG_INSERT_MASK, static_cast<std::uint32_t>(EFFECT_FLAG_INSERT_LAST));
    EXPECT_EQ(d.flags & EFFECT_FLAG_VOLUME_MASK, static_cast<std::uint32_t>(EFFECT_FLAG_VOLUME_IND));
    EXPECT_EQ(d.flags & EFFECT_FLAG_DEVICE_MASK, static_cast<std::uint32_t>(EFFECT_FLAG_DEVICE_IND));
    EXPECT_EQ(d.flags & EFFECT_FLAG_HW_ACC_MASK, 0u);  // no offload
    EXPECT_STREQ(d.name, SP_EFFECT_NAME);

    const effect_uuid_t type = SP_EFFECT_TYPE_UUID_INIT;
    EXPECT_EQ(AUDIO_EFFECT_LIBRARY_INFO_SYM.get_descriptor(&type, &d), -EINVAL);
    EXPECT_EQ(AUDIO_EFFECT_LIBRARY_INFO_SYM.get_descriptor(nullptr, &d), -EINVAL);
    EXPECT_EQ(AUDIO_EFFECT_LIBRARY_INFO_SYM.get_descriptor(&impl, nullptr), -EINVAL);
}

TEST_F(EffectApi, CreateRejectsBadArguments) {
    effect_handle_t h = nullptr;
    const effect_uuid_t type = SP_EFFECT_TYPE_UUID_INIT;
    EXPECT_EQ(android::createEffect(global, nullptr, 1, 1, &h), -EINVAL);
    EXPECT_EQ(android::createEffect(global, &android::descriptor().uuid, 1, 1, nullptr), -EINVAL);
    EXPECT_EQ(android::createEffect(global, &type, 1, 1, &h), -ENOENT);
    EXPECT_EQ(android::releaseEffect(nullptr), -EINVAL);
    EXPECT_EQ(global.instanceCount(), 0u);
}

TEST_F(EffectApi, InterfaceDescriptorMatchesLibraryDescriptor) {
    Effect e = create(1);
    effect_descriptor_t d{};
    ASSERT_EQ((*e.h)->get_descriptor(e.h, &d), 0);
    EXPECT_EQ(std::memcmp(&d, &android::descriptor(), sizeof(d)), 0);
    EXPECT_EQ((*e.h)->get_descriptor(e.h, nullptr), -EINVAL);
    EXPECT_EQ((*e.h)->process_reverse, nullptr);
}

// ---- The AudioFlinger lifecycle -------------------------------------------------------------

TEST_F(EffectApi, AudioFlingerLifecycle) {
    Effect stream = createRunning(77);
    std::uint32_t device = AUDIO_DEVICE_OUT_WIRED_HEADPHONE;
    std::vector<std::uint8_t> dev(4);
    std::memcpy(dev.data(), &device, 4);
    std::uint32_t zero = 0;
    EXPECT_EQ((*stream.h)->command(stream.h, EFFECT_CMD_SET_DEVICE, 4, dev.data(), &zero, nullptr), 0);
    std::array<std::uint32_t, 2> volume = {1u << 24, 1u << 23};
    EXPECT_EQ((*stream.h)->command(stream.h, EFFECT_CMD_SET_VOLUME, 8, volume.data(), nullptr, nullptr), 0);

    const effect_config_t c = stream.getConfig();
    EXPECT_EQ(c.outputCfg.samplingRate, 48000u);
    EXPECT_EQ(c.outputCfg.channels, static_cast<std::uint32_t>(AUDIO_CHANNEL_OUT_STEREO));
    EXPECT_EQ(c.outputCfg.format, AUDIO_FORMAT_PCM_FLOAT);

    // Before any parameters, the effect is transparent apart from the limiter's latency.
    std::vector<float> in = dsp::test::sine(1000.0, 48000.0, 0.25, 4800, 2);
    std::vector<float> out(in.size());
    ASSERT_EQ(stream.process(in, out, 4800), 0);

    // The app's control instance lives on its own session; its parameters reach the stream.
    Effect control = createRunning(1000);
    ASSERT_EQ(control.setParam(SP_PARAM_MASTER, {0, 0, 0, 0, 0, 0, 0xa0, 0xc1}), 0);  // -20 dB
    ASSERT_EQ(stream.process(in, out, 4800), 0);
    EXPECT_NEAR(out[out.size() - 2], in[in.size() - 2 - 2 * 72] * 0.1f, 1e-4f);  // 72 = latency

    // STATUS answers for the instance asked.
    std::vector<std::uint8_t> value;
    ASSERT_EQ(stream.getParam(SP_PARAM_STATUS, value), 0);
    ASSERT_EQ(value.size(), SP_STATUS_SIZE);
    sp_status_t st;
    std::memcpy(&st, value.data(), sizeof(st));
    EXPECT_EQ(st.session_id, 77);
    EXPECT_EQ(st.state, static_cast<std::uint32_t>(SP_STATE_ACTIVE));
    EXPECT_EQ(st.instances, 2u);
    EXPECT_EQ(st.frames_processed, 9600u);
    EXPECT_EQ(st.format, SP_FORMAT_PCM_FLOAT);

    // DISABLE: the effect fades out and asks to stop within AudioFlinger's 50 ms budget.
    ASSERT_EQ(stream.call(EFFECT_CMD_DISABLE), 0);
    std::size_t framesUntilNoData = 0;
    for (int block = 0; block < 100; ++block) {
        const std::int32_t r = stream.process(in, out, 240);
        framesUntilNoData += 240;
        if (r == -ENODATA) break;
        ASSERT_EQ(r, 0);
    }
    EXPECT_LE(framesUntilNoData, 2400u);
    release(stream);
    release(control);
}

TEST_F(EffectApi, Int16StreamsWork) {
    Effect e = createRunning(5, makeConfig(44100, AUDIO_CHANNEL_OUT_MONO, AUDIO_FORMAT_PCM_16_BIT));
    ASSERT_EQ(e.setParam(SP_PARAM_MASTER, {0, 0, 0, 0, 0, 0, 0xc0, 0xc0}), 0);  // -6 dB
    std::vector<std::int16_t> pcm(4410, 16000);
    audio_buffer_t b{};
    b.frameCount = pcm.size();
    b.s16 = pcm.data();
    ASSERT_EQ((*e.h)->process(e.h, &b, &b), 0);
    EXPECT_NEAR(pcm.back(), 16000 / 2, 300);  // 0.501 × 16000 ± dither and rounding
}

// ---- GET_PARAM ------------------------------------------------------------------------------

TEST_F(EffectApi, GetParamWithASharedCommandAndReplyBuffer) {
    Effect e = create(3);
    std::vector<std::uint8_t> v;
    ASSERT_EQ(e.getParam(SP_PARAM_PROTOCOL_INFO, v), 0);
    ASSERT_EQ(v.size(), SP_PROTOCOL_INFO_SIZE);
    sp_protocol_info_t info;
    std::memcpy(&info, v.data(), sizeof(info));
    EXPECT_EQ(info.major, SP_PROTOCOL_VERSION_MAJOR);

    ASSERT_EQ(e.getParam(SP_PARAM_MASTER, v), 0);
    EXPECT_EQ(v, golden("master_default"));
    ASSERT_EQ(e.setParam(SP_PARAM_EQ_STATE, golden("eq_state_3band")), 0);
    ASSERT_EQ(e.getParam(SP_PARAM_EQ_STATE, v), 0);
    EXPECT_EQ(v, golden("eq_state_3band"));

    EXPECT_EQ(e.getParam(0x4242, v), -EINVAL);
    EXPECT_TRUE(v.empty());
    EXPECT_EQ(e.getParam(SP_PARAM_EQ_STATE, v, 51), -EINVAL);  // the caller's value is too small
    EXPECT_EQ(e.getParam(SP_PARAM_EQ_STATE, v, 52), 0);
}

TEST_F(EffectApi, GetParamRejectsMalformedCommands) {
    Effect e = create(3);
    std::vector<std::uint8_t> buf(64, 0);
    std::uint32_t replySize = static_cast<std::uint32_t>(buf.size());
    auto get = [&](std::uint32_t cmdSize, std::uint32_t psize, std::uint32_t vsize, std::uint32_t reply) {
        const effect_param_t header{0, psize, vsize};
        std::memcpy(buf.data(), &header, sizeof(header));
        replySize = reply;
        return (*e.h)->command(e.h, EFFECT_CMD_GET_PARAM, cmdSize, buf.data(), &replySize, buf.data());
    };
    EXPECT_EQ(get(11, 4, 16, 64), -EINVAL);         // shorter than the header
    EXPECT_EQ(get(12, 4, 16, 64), -EINVAL);         // psize says 4 bytes follow, none do
    EXPECT_EQ(get(16, 0, 16, 64), -EINVAL);         // empty parameter
    EXPECT_EQ(get(16, 4, 16, 15), -EINVAL);         // reply can't hold the echoed parameter
    EXPECT_EQ(get(16, 0x7fffffff, 16, 64), -EINVAL); // absurd psize
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_GET_PARAM, 16, buf.data(), nullptr, buf.data()), -EINVAL);

    // psize 8 (indexed form) is well-formed but has no v1 parameters.
    ASSERT_EQ(get(20, 8, 16, 64), 0);
    effect_param_t out;
    std::memcpy(&out, buf.data(), sizeof(out));
    EXPECT_EQ(out.status, -EINVAL);
    EXPECT_EQ(out.vsize, 0u);
}

// ---- SET_PARAM ------------------------------------------------------------------------------

TEST_F(EffectApi, SetParamValidatesSizes) {
    Effect e = create(4);
    std::vector<std::uint8_t> cmd = paramCommand(SP_PARAM_MASTER, golden("master_bypass"));
    std::int32_t status = 0;
    ASSERT_EQ(e.send(EFFECT_CMD_SET_PARAM, cmd, &status), 0);
    EXPECT_EQ(status, 0);

    // vsize claims more bytes than the command has.
    std::vector<std::uint8_t> lying = cmd;
    lying[8] = 9;
    EXPECT_EQ(e.send(EFFECT_CMD_SET_PARAM, lying, &status), -EINVAL);
    // A truncated command.
    EXPECT_EQ(e.send(EFFECT_CMD_SET_PARAM, std::vector<std::uint8_t>(cmd.begin(), cmd.end() - 1), &status), -EINVAL);
    EXPECT_EQ(e.send(EFFECT_CMD_SET_PARAM, std::vector<std::uint8_t>(cmd.begin(), cmd.begin() + 11), &status), -EINVAL);
    // psize 8 is reserved for indexed parameters; none exist in v1.
    std::vector<std::uint8_t> indexed(sizeof(effect_param_t) + 8 + 8, 0);
    const effect_param_t header{0, 8, 8};
    std::memcpy(indexed.data(), &header, sizeof(header));
    ASSERT_EQ(e.send(EFFECT_CMD_SET_PARAM, indexed, &status), 0);
    EXPECT_EQ(status, -EINVAL);
    // A read-only parameter.
    EXPECT_EQ(e.setParam(SP_PARAM_STATUS, std::vector<std::uint8_t>(48, 0)), -EINVAL);
    // No reply buffer, or one too small for the status.
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_PARAM, static_cast<std::uint32_t>(cmd.size()), cmd.data(), nullptr, nullptr), -EINVAL);
    std::uint16_t tiny = 0;
    std::uint32_t tinySize = sizeof(tiny);
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_PARAM, static_cast<std::uint32_t>(cmd.size()), cmd.data(), &tinySize, &tiny), -EINVAL);

    std::vector<std::uint8_t> v;
    ASSERT_EQ(e.getParam(SP_PARAM_MASTER, v), 0);
    EXPECT_EQ(v, golden("master_bypass"));  // only the valid SET took effect
}

// ---- SET_CONFIG ----------------------------------------------------------------------------

TEST_F(EffectApi, SetConfigAcceptsOnlySupportedFormats) {
    Effect e = create(6);
    EXPECT_EQ(e.setConfig(makeConfig(48000, AUDIO_CHANNEL_OUT_5POINT1)), -EINVAL);
    EXPECT_EQ(e.setConfig(makeConfig(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_24_BIT_PACKED)), -EINVAL);
    EXPECT_EQ(e.setConfig(makeConfig(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_FLOAT,
                                     EFFECT_BUFFER_ACCESS_READ)),
              -EINVAL);
    effect_config_t mismatch = makeConfig();
    mismatch.inputCfg.samplingRate = 44100;
    EXPECT_EQ(e.setConfig(mismatch), -EINVAL);
    EXPECT_EQ(e.setConfig(makeConfig(1000)), -EINVAL);

    // Rejected configurations leave the previous one in place.
    EXPECT_EQ(e.getConfig().outputCfg.samplingRate, 48000u);
    ASSERT_EQ(e.setConfig(makeConfig(96000, AUDIO_CHANNEL_OUT_MONO, AUDIO_FORMAT_PCM_16_BIT,
                                     EFFECT_BUFFER_ACCESS_ACCUMULATE)),
              0);
    const effect_config_t c = e.getConfig();
    EXPECT_EQ(c.outputCfg.samplingRate, 96000u);
    EXPECT_EQ(c.outputCfg.channels, static_cast<std::uint32_t>(AUDIO_CHANNEL_OUT_MONO));
    EXPECT_EQ(c.outputCfg.accessMode, EFFECT_BUFFER_ACCESS_ACCUMULATE);

    std::vector<std::uint8_t> shortCmd(sizeof(effect_config_t) - 1);
    EXPECT_EQ(e.call(EFFECT_CMD_SET_CONFIG, shortCmd), -EINVAL);
}

TEST_F(EffectApi, SetConfigHonorsTheFieldMask) {
    Effect e = create(7);
    ASSERT_EQ(e.setConfig(makeConfig(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_16_BIT)), 0);
    effect_config_t rateOnly{};
    rateOnly.inputCfg.samplingRate = rateOnly.outputCfg.samplingRate = 44100;
    rateOnly.inputCfg.mask = rateOnly.outputCfg.mask = EFFECT_CONFIG_SMP_RATE;
    ASSERT_EQ(e.setConfig(rateOnly), 0);
    const effect_config_t c = e.getConfig();
    EXPECT_EQ(c.outputCfg.samplingRate, 44100u);
    EXPECT_EQ(c.outputCfg.format, AUDIO_FORMAT_PCM_16_BIT);  // kept
    EXPECT_EQ(c.outputCfg.channels, static_cast<std::uint32_t>(AUDIO_CHANNEL_OUT_STEREO));

    // A zero mask means "all fields" (callers that predate the mask).
    effect_config_t all = makeConfig(32000);
    all.inputCfg.mask = all.outputCfg.mask = 0;
    ASSERT_EQ(e.setConfig(all), 0);
    EXPECT_EQ(e.getConfig().outputCfg.format, AUDIO_FORMAT_PCM_FLOAT);
}

// ---- Other commands --------------------------------------------------------------------------

TEST_F(EffectApi, CommandsCheckTheirReplyBuffers) {
    Effect e = create(8);
    for (const std::uint32_t code : {std::uint32_t{EFFECT_CMD_INIT}, std::uint32_t{EFFECT_CMD_ENABLE},
                                     std::uint32_t{EFFECT_CMD_DISABLE}}) {
        EXPECT_EQ((*e.h)->command(e.h, code, 0, nullptr, nullptr, nullptr), -EINVAL) << code;
    }
    std::uint32_t small = 2;
    effect_config_t c{};
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_GET_CONFIG, 0, nullptr, &small, &c), -EINVAL);
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_RESET, 0, nullptr, nullptr, nullptr), 0);
}

TEST_F(EffectApi, UnknownCommandsAreRejected) {
    Effect e = create(9);
    for (const std::uint32_t code :
         {std::uint32_t{EFFECT_CMD_SET_PARAM_DEFERRED}, std::uint32_t{EFFECT_CMD_SET_PARAM_COMMIT},
          std::uint32_t{EFFECT_CMD_SET_AUDIO_MODE}, std::uint32_t{EFFECT_CMD_SET_INPUT_DEVICE},
          std::uint32_t{EFFECT_CMD_SET_CONFIG_REVERSE}, std::uint32_t{EFFECT_CMD_GET_FEATURE_CONFIG},
          std::uint32_t{EFFECT_CMD_FIRST_PROPRIETARY}, 0xdeadbeefu}) {
        std::array<std::uint8_t, 64> data{};
        std::int32_t reply = 0;
        std::uint32_t replySize = sizeof(reply);
        EXPECT_EQ((*e.h)->command(e.h, code, 64, data.data(), &replySize, &reply), -EINVAL) << code;
    }
}

TEST_F(EffectApi, VolumeDeviceAndOffload) {
    Effect e = create(10);
    std::array<std::uint32_t, 2> volume = {1u << 24, 1u << 22};
    std::array<std::uint32_t, 2> reply = {0, 0};
    std::uint32_t replySize = sizeof(reply);
    ASSERT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_VOLUME, 8, volume.data(), &replySize, reply.data()), 0);
    EXPECT_EQ(reply, volume);  // nothing applied here: the caller applies the full volume
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_VOLUME, 4, volume.data(), nullptr, nullptr), 0);
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_VOLUME, 0, nullptr, nullptr, nullptr), -EINVAL);
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_DEVICE, 2, volume.data(), nullptr, nullptr), -EINVAL);

    effect_offload_param_t offload{true, 5};
    std::vector<std::uint8_t> data(sizeof(offload));
    std::memcpy(data.data(), &offload, sizeof(offload));
    EXPECT_EQ(e.call(EFFECT_CMD_OFFLOAD, data), -EINVAL);  // not offloadable
    offload.isOffload = false;
    std::memcpy(data.data(), &offload, sizeof(offload));
    EXPECT_EQ(e.call(EFFECT_CMD_OFFLOAD, data), 0);
    data[0] = 3;  // not a valid bool: found by the fuzzer, must be read as "offload"
    EXPECT_EQ(e.call(EFFECT_CMD_OFFLOAD, data), -EINVAL);
}

// ---- Direct volume control ------------------------------------------------------------------

/// Peak of the last `frames` stereo frames.
double tailPeak(const std::vector<float>& y, std::size_t frames) {
    double peak = 0.0;
    for (std::size_t i = y.size() - frames * 2; i < y.size(); ++i) {
        peak = std::max(peak, static_cast<double>(std::fabs(y[i])));
    }
    return peak;
}

class DirectVolume : public EffectApi {
protected:
    // MASTER: auto-preamp on, direct volume control on, preamp 0 dB. Set before the first
    // instance exists, like a state file read when the library loads.
    void SetUp() override {
        const std::vector<std::uint8_t> master = {0x0a, 0, 0, 0, 0, 0, 0, 0};
        ASSERT_EQ(global.setParam(SP_PARAM_MASTER, master), 0);
    }

    /// What AudioFlinger sends the chain's volume controller. Returns what the mixer must
    /// still apply.
    static std::array<std::uint32_t, 2> controllerVolume(Effect& e, std::uint32_t left,
                                                         std::uint32_t right) {
        std::array<std::uint32_t, 2> volume = {left, right};
        std::array<std::uint32_t, 2> reply = {0, 0};
        std::uint32_t replySize = sizeof(reply);
        EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_VOLUME, 8, volume.data(), &replySize,
                                  reply.data()),
                  0);
        EXPECT_EQ(replySize, sizeof(reply));
        return reply;
    }

    /// `frames` frames of a 1 kHz sine at `amplitude`, through `e` in 480-frame buffers.
    static std::vector<float> play(Effect& e, double amplitude, std::size_t frames = 4800) {
        std::vector<float> in = dsp::test::sine(1000.0, 48000.0, amplitude, frames, 2);
        std::vector<float> out(in.size());
        for (std::size_t off = 0; off < frames; off += 480) {
            std::vector<float> a(in.begin() + off * 2, in.begin() + (off + 480) * 2), b(a.size());
            EXPECT_EQ(e.process(a, b, 480), 0);
            std::copy(b.begin(), b.end(), out.begin() + off * 2);
        }
        return out;
    }

    static std::uint32_t statusFlags(Effect& e) {
        std::vector<std::uint8_t> v;
        EXPECT_EQ(e.getParam(SP_PARAM_STATUS, v), 0);
        sp_status_t st{};
        std::memcpy(&st, v.data(), std::min(v.size(), sizeof(st)));
        return st.flags;
    }
};

constexpr std::uint32_t kQuarter = 1u << 22;  // 0.25 (-12 dB) in 8.24
constexpr std::array<std::uint32_t, 2> kUnity = {1u << 24, 1u << 24};

TEST_F(DirectVolume, TheDescriptorClaimsVolumeControl) {
    Effect e = create(30);
    effect_descriptor_t d{};
    ASSERT_EQ((*e.h)->get_descriptor(e.h, &d), 0);
    EXPECT_EQ(d.flags & EFFECT_FLAG_VOLUME_MASK,
              static_cast<std::uint32_t>(EFFECT_FLAG_VOLUME_CTRL));
    EXPECT_EQ(std::memcmp(&d, &android::descriptor(true), sizeof(d)), 0);
    // Only the volume flags differ from the default descriptor.
    EXPECT_EQ(android::descriptor(true).flags & ~EFFECT_FLAG_VOLUME_MASK,
              android::descriptor(false).flags & ~EFFECT_FLAG_VOLUME_MASK);
}

TEST_F(DirectVolume, TheControllerTakesTheVolumeOver) {
    Effect e = createRunning(31);
    EXPECT_EQ(controllerVolume(e, kQuarter, kQuarter), kUnity);  // the mixer applies nothing
    const std::vector<float> out = play(e, 0.4);
    EXPECT_NEAR(tailPeak(out, 960), 0.1, 1e-3);
    EXPECT_LE(dsp::test::maxAbs(out), 0.1 + 1e-3);  // no full-scale start
}

TEST_F(DirectVolume, AnEffectThatIsNotTheControllerLeavesTheVolumeToTheMixer) {
    Effect e = createRunning(32);
    std::array<std::uint32_t, 2> volume = {kQuarter, kQuarter};
    ASSERT_EQ((*e.h)->command(e.h, EFFECT_CMD_SET_VOLUME, 8, volume.data(), nullptr, nullptr), 0);
    EXPECT_NEAR(tailPeak(play(e, 0.4), 960), 0.4, 1e-3);
    EXPECT_EQ(statusFlags(e) & SP_STATUS_FLAG_VOLUME_APPLIED, 0u);
}

TEST_F(DirectVolume, DisablingKeepsTheVolumeToTheLastBuffer) {
    // AudioFlinger treats the effect as the controller until process() returns -ENODATA, so
    // the fade-out and that last buffer must still carry the volume.
    Effect e = createRunning(33);
    (void)controllerVolume(e, kQuarter, kQuarter);
    (void)play(e, 0.4);
    ASSERT_EQ(e.call(EFFECT_CMD_DISABLE), 0);
    std::vector<float> in = dsp::test::sine(1000.0, 48000.0, 0.4, 480, 2);
    std::vector<float> out(in.size());
    int status = 0;
    for (int i = 0; i < 10 && status == 0; ++i) {
        status = e.process(in, out, 480);
        EXPECT_LE(dsp::test::maxAbs(out), 0.1 + 1e-3) << "buffer " << i;
    }
    EXPECT_EQ(status, -ENODATA);
}

TEST_F(DirectVolume, AutoPreampHeadroomComesBackAtLowVolume) {
    // +12 dB at 1 kHz with auto-preamp: at -12 dB volume the boost is heard in full.
    Effect e = createRunning(34);
    std::vector<std::uint8_t> eq(4 + 16, 0);
    eq[0] = 1;                                  // one band
    eq[4] = SP_BAND_PEAKING;                    // type
    eq[5] = 1;                                  // enabled
    const float freq = 1000.0f, gain = 12.0f, q = 1.0f;
    std::memcpy(eq.data() + 8, &freq, 4);
    std::memcpy(eq.data() + 12, &gain, 4);
    std::memcpy(eq.data() + 16, &q, 4);
    ASSERT_EQ(e.setParam(SP_PARAM_EQ_STATE, eq), 0);
    (void)controllerVolume(e, kQuarter, kQuarter);
    EXPECT_NEAR(tailPeak(play(e, 0.1, 9600), 960), 0.1, 2e-3);  // 0.1 · 0.25 · 4
}

TEST_F(DirectVolume, StatusReportsTheClaimAndTheAppliedVolume) {
    Effect e = createRunning(35);
    EXPECT_EQ(statusFlags(e) & (SP_STATUS_FLAG_VOLUME_CTRL | SP_STATUS_FLAG_VOLUME_APPLIED),
              SP_STATUS_FLAG_VOLUME_CTRL);
    (void)controllerVolume(e, kQuarter, kQuarter);
    EXPECT_NE(statusFlags(e) & SP_STATUS_FLAG_VOLUME_APPLIED, 0u);
    ASSERT_EQ(e.call(EFFECT_CMD_DISABLE), 0);
    EXPECT_EQ(statusFlags(e) & SP_STATUS_FLAG_VOLUME_APPLIED, 0u);
}

TEST_F(DirectVolume, APassingThroughDuplicateStillAppliesTheVolume) {
    const Effect first = createRunning(36);
    Effect second = createRunning(36);  // duplicate of `first`: passes audio through
    ASSERT_NE(first.h, nullptr);
    (void)controllerVolume(second, 1u << 23, 1u << 23);  // 0.5
    std::vector<float> in = dsp::test::sine(1000.0, 48000.0, 0.5, 960, 2);
    std::vector<float> out(in.size());
    ASSERT_EQ(second.process(in, out, 960), 0);
    for (std::size_t i = 0; i < in.size(); ++i) ASSERT_EQ(out[i], in[i] * 0.5f) << i;
}

TEST_F(DirectVolume, TheClaimDoesNotFollowLaterChanges) {
    Effect e = create(37);
    ASSERT_EQ(e.setParam(SP_PARAM_MASTER, {0x02, 0, 0, 0, 0, 0, 0, 0}), 0);  // direct volume off
    EXPECT_TRUE(global.claimsVolumeControl());
    std::vector<std::uint8_t> v;
    ASSERT_EQ(e.getParam(SP_PARAM_MASTER, v), 0);
    EXPECT_EQ(v[0], 0x02);  // the stored setting changed; the claim waits for a reload
}

TEST_F(DirectVolume, DumpShowsTheVolume) {
    Effect e = createRunning(38);
    (void)controllerVolume(e, kQuarter, kQuarter);
    (void)play(e, 0.4, 960);
    int fds[2];
    ASSERT_EQ(::pipe(fds), 0);
    std::vector<std::uint8_t> cmd(4);
    std::memcpy(cmd.data(), &fds[1], 4);
    ASSERT_EQ((*e.h)->command(e.h, EFFECT_CMD_DUMP, 4, cmd.data(), nullptr, nullptr), 0);
    ::close(fds[1]);
    char text[1024] = {};
    ASSERT_GT(::read(fds[0], text, sizeof(text) - 1), 0);
    ::close(fds[0]);
    EXPECT_NE(std::strstr(text, "flags 0x18"), nullptr) << text;  // claimed and applied
    EXPECT_NE(std::strstr(text, "volume -12.0/-12.0 dB applied (stage -12.0 dB), "
                                "peak in -8.0 out -20.0 dBFS, volume stage output"),
              nullptr)
        << text;
}

/// The effect's DUMP line.
std::string dumpLine(Effect& e) {
    int fds[2];
    EXPECT_EQ(::pipe(fds), 0);
    std::vector<std::uint8_t> cmd(4);
    std::memcpy(cmd.data(), &fds[1], 4);
    EXPECT_EQ((*e.h)->command(e.h, EFFECT_CMD_DUMP, 4, cmd.data(), nullptr, nullptr), 0);
    ::close(fds[1]);
    char text[1024] = {};
    EXPECT_GT(::read(fds[0], text, sizeof(text) - 1), 0);
    ::close(fds[0]);
    return text;
}

TEST_F(DirectVolume, ASessionLeftOutGetsTheVolumeAtTheInput) {
    Effect e = createRunning(39);
    Effect other = createRunning(40);
    // Session 39 is left out; the list also names a session with no instance yet.
    const std::vector<std::uint8_t> sessions = {2, 0, 0, 0, 39, 0, 0, 0, 41, 0, 0, 0};
    ASSERT_EQ(e.setParam(SP_PARAM_INPUT_VOLUME_SESSIONS, sessions), 0);
    std::vector<std::uint8_t> v;
    ASSERT_EQ(e.getParam(SP_PARAM_INPUT_VOLUME_SESSIONS, v), 0);
    EXPECT_EQ(v, sessions);
    // The mixer is still told to apply nothing: the reply never changes, so AudioFlinger need
    // not ask again when a session moves in or out of the list.
    EXPECT_EQ(controllerVolume(e, kQuarter, kQuarter), kUnity);
    EXPECT_EQ(controllerVolume(other, kQuarter, kQuarter), kUnity);
    EXPECT_NEAR(tailPeak(play(e, 0.4), 960), 0.1, 1e-3);
    EXPECT_NE(dumpLine(e).find("volume stage input"), std::string::npos) << dumpLine(e);
    EXPECT_NE(dumpLine(other).find("volume stage output"), std::string::npos);
    Effect later = create(41);
    EXPECT_NE(dumpLine(later).find("volume stage input"), std::string::npos);

    // An empty list puts every session back; the stored parameters never changed.
    ASSERT_EQ(e.setParam(SP_PARAM_INPUT_VOLUME_SESSIONS, {0, 0, 0, 0}), 0);
    EXPECT_NE(dumpLine(e).find("volume stage output"), std::string::npos);
    EXPECT_NE(dumpLine(later).find("volume stage output"), std::string::npos);
    ASSERT_EQ(e.getParam(SP_PARAM_MASTER, v), 0);
    EXPECT_EQ(v[0], 0x0a);
    // Malformed: rejected, the list stays as it was.
    EXPECT_EQ(e.setParam(SP_PARAM_INPUT_VOLUME_SESSIONS, {1, 0, 0, 0}), -EINVAL);
}

TEST_F(EffectApi, ProcessValidatesBuffers) {
    Effect e = createRunning(11);
    std::vector<float> a(512), b(512);
    audio_buffer_t in{};
    in.frameCount = 256;
    in.f32 = a.data();
    audio_buffer_t out = in;
    out.f32 = b.data();
    EXPECT_EQ((*e.h)->process(e.h, nullptr, &out), -EINVAL);
    EXPECT_EQ((*e.h)->process(e.h, &in, nullptr), -EINVAL);
    out.frameCount = 128;
    EXPECT_EQ((*e.h)->process(e.h, &in, &out), -EINVAL);
    out.frameCount = 256;
    out.f32 = nullptr;
    EXPECT_EQ((*e.h)->process(e.h, &in, &out), -EINVAL);
    EXPECT_EQ((*e.h)->process(nullptr, &in, &in), -EINVAL);
    EXPECT_EQ((*e.h)->process(e.h, &in, &in), 0);
}

TEST_F(EffectApi, DumpWritesAStatusLine) {
    Effect e = createRunning(12);
    int fds[2];
    ASSERT_EQ(::pipe(fds), 0);
    std::vector<std::uint8_t> cmd(4);
    std::memcpy(cmd.data(), &fds[1], 4);
    ASSERT_EQ((*e.h)->command(e.h, EFFECT_CMD_DUMP, 4, cmd.data(), nullptr, nullptr), 0);
    ::close(fds[1]);
    char text[512] = {};
    const ssize_t n = ::read(fds[0], text, sizeof(text) - 1);
    ::close(fds[0]);
    ASSERT_GT(n, 0);
    EXPECT_NE(std::strstr(text, "SoundPower"), nullptr) << text;
    EXPECT_NE(std::strstr(text, "session 12"), nullptr) << text;
    EXPECT_NE(std::strstr(text, "active"), nullptr) << text;
    // Without direct volume control the mixer applies the volume; nothing played yet.
    EXPECT_NE(std::strstr(text, "volume 0.0/0.0 dB by mixer (stage 0.0 dB), "
                                "peak in -999.0 out -999.0 dBFS"),
              nullptr)
        << text;
}

TEST_F(EffectApi, SecondInstanceOnTheSameStreamPassesThrough) {
    Effect first = createRunning(20);
    Effect second = createRunning(20);
    ASSERT_EQ(first.setParam(SP_PARAM_MASTER, {0, 0, 0, 0, 0, 0, 0xa0, 0xc1}), 0);  // -20 dB
    std::vector<float> in(2 * 960, 0.5f), out(in.size());
    ASSERT_EQ(second.process(in, out, 960), 0);
    EXPECT_EQ(out, in);
    std::vector<std::uint8_t> v;
    ASSERT_EQ(second.getParam(SP_PARAM_STATUS, v), 0);
    sp_status_t st;
    std::memcpy(&st, v.data(), sizeof(st));
    EXPECT_NE(st.flags & SP_STATUS_FLAG_DUPLICATE_SESSION, 0u);
}

// ---- The real shared library ----------------------------------------------------------------

TEST(SharedLibrary, LoadsLikeTheEffectsFactory) {
    std::string path;
    if (const char* env = std::getenv("SP_EFFECT_LIB")) {
        path = env;
    } else if (::access("./libsoundpower.so", R_OK) == 0) {
        path = "./libsoundpower.so";  // pushed next to the test binary on a device
    } else {
        GTEST_SKIP() << "set SP_EFFECT_LIB to the built libsoundpower.so";
    }

    void* lib = ::dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    ASSERT_NE(lib, nullptr) << ::dlerror();
    auto* info = static_cast<audio_effect_library_t*>(::dlsym(lib, AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR));
    ASSERT_NE(info, nullptr) << ::dlerror();
    EXPECT_EQ(info->tag, static_cast<std::uint32_t>(AUDIO_EFFECT_LIBRARY_TAG));
    EXPECT_EQ(EFFECT_API_VERSION_MAJOR(info->version), 3u);

    const effect_uuid_t impl = SP_EFFECT_IMPL_UUID_INIT;
    effect_descriptor_t d{};
    ASSERT_EQ(info->get_descriptor(&impl, &d), 0);
    EXPECT_STREQ(d.name, SP_EFFECT_NAME);

    Effect e;
    ASSERT_EQ(info->create_effect(&impl, 4242, 3, &e.h), 0);
    EXPECT_EQ(e.call(EFFECT_CMD_INIT), 0);
    EXPECT_EQ(e.setConfig(makeConfig()), 0);
    EXPECT_EQ(e.call(EFFECT_CMD_ENABLE), 0);
    std::vector<float> in = dsp::test::sine(1000.0, 48000.0, 0.5, 960, 2);
    std::vector<float> out(in.size());
    EXPECT_EQ(e.process(in, out, 960), 0);
    EXPECT_EQ(e.call(EFFECT_CMD_DISABLE), 0);
    std::int32_t r = 0;
    for (int i = 0; i < 20 && r == 0; ++i) r = e.process(in, out, 240);
    EXPECT_EQ(r, -ENODATA);
    EXPECT_EQ(info->release_effect(e.h), 0);
    // Not dlclose()d: the audio HAL never unloads effect libraries either.
}

}  // namespace
}  // namespace sp::fx
