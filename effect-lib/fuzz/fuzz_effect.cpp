// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// libFuzzer target: interprets the input as a sequence of effect API calls (raw and structured
// commands, configuration changes, process() calls with hostile samples, instance churn) on two
// instances sharing one GlobalState. Every buffer is allocated at exactly the size passed to the
// effect, so AddressSanitizer catches any out-of-bounds access.
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

#include "android/effect_api.h"
#include "global_state.h"
#include "soundpower/protocol.h"

namespace {

class Input {
public:
    Input(const std::uint8_t* data, std::size_t size) : data_(data), size_(size) {}

    [[nodiscard]] bool empty() const { return size_ == 0; }

    std::uint8_t u8() {
        if (size_ == 0) return 0;
        --size_;
        return *data_++;
    }
    std::uint16_t u16() { return static_cast<std::uint16_t>(u8() | (u8() << 8)); }
    std::uint32_t u32() { return static_cast<std::uint32_t>(u16() | (std::uint32_t{u16()} << 16)); }

    std::vector<std::uint8_t> bytes(std::size_t n) {
        n = std::min(n, size_);
        std::vector<std::uint8_t> out(data_, data_ + n);
        data_ += n;
        size_ -= n;
        return out;
    }

private:
    const std::uint8_t* data_;
    std::size_t size_;
};

constexpr std::uint32_t kCommands[] = {
    EFFECT_CMD_INIT,       EFFECT_CMD_SET_CONFIG, EFFECT_CMD_RESET,      EFFECT_CMD_ENABLE,
    EFFECT_CMD_DISABLE,    EFFECT_CMD_SET_PARAM,  EFFECT_CMD_GET_PARAM,  EFFECT_CMD_SET_DEVICE,
    EFFECT_CMD_SET_VOLUME, EFFECT_CMD_GET_CONFIG, EFFECT_CMD_OFFLOAD,    EFFECT_CMD_DUMP,
    EFFECT_CMD_SET_PARAM_DEFERRED};

constexpr std::uint32_t kParamIds[] = {SP_PARAM_PROTOCOL_INFO, SP_PARAM_STATUS, SP_PARAM_SPECTRUM,
                                       SP_PARAM_MASTER,        SP_PARAM_EQ_STATE, SP_PARAM_LIMITER};

constexpr std::uint32_t kRates[] = {8000, 22050, 44100, 48000, 96000, 192000, 384000, 0, 1};

void trap() { __builtin_trap(); }

/// A raw command with fuzzed code, payload and reply buffer.
void rawCommand(effect_handle_t h, Input& in) {
    const std::uint8_t sel = in.u8();
    const std::uint32_t code = (sel & 0x80) ? in.u32() : kCommands[sel % std::size(kCommands)];
    std::vector<std::uint8_t> cmd = in.bytes(in.u16() % 1200);
    const std::uint32_t replyCap = in.u16() % 1200;
    const std::uint8_t mode = in.u8();
    if (code == EFFECT_CMD_DUMP) return;  // the payload is a file descriptor

    if (mode & 1) {
        // One buffer for command and reply, as AudioEffect::getParameter() does.
        std::vector<std::uint8_t> buf(std::max<std::size_t>(cmd.size(), replyCap));
        std::copy(cmd.begin(), cmd.end(), buf.begin());
        std::uint32_t replySize = static_cast<std::uint32_t>(buf.size());
        (*h)->command(h, code, static_cast<std::uint32_t>(cmd.size()), buf.empty() ? nullptr : buf.data(),
                      &replySize, buf.empty() ? nullptr : buf.data());
        if (replySize > buf.size()) trap();
    } else {
        std::vector<std::uint8_t> reply(replyCap);
        std::uint32_t replySize = replyCap;
        void* replyPtr = (mode & 2) || reply.empty() ? nullptr : reply.data();
        (*h)->command(h, code, static_cast<std::uint32_t>(cmd.size()), cmd.empty() ? nullptr : cmd.data(),
                      (mode & 4) ? nullptr : &replySize, replyPtr);
        if (replySize > replyCap) trap();
    }
}

/// A well-formed SET_PARAM envelope around a fuzzed value.
void setParam(effect_handle_t h, Input& in) {
    const std::uint32_t id = kParamIds[in.u8() % std::size(kParamIds)];
    const std::vector<std::uint8_t> value = in.bytes(in.u16() % 600);
    std::vector<std::uint8_t> cmd(sizeof(effect_param_t) + 4 + value.size());
    const effect_param_t header{0, 4, static_cast<std::uint32_t>(value.size())};
    std::memcpy(cmd.data(), &header, sizeof(header));
    std::memcpy(cmd.data() + sizeof(header), &id, 4);
    std::copy(value.begin(), value.end(), cmd.begin() + sizeof(header) + 4);
    std::int32_t status = 0;
    std::uint32_t replySize = sizeof(status);
    (*h)->command(h, EFFECT_CMD_SET_PARAM, static_cast<std::uint32_t>(cmd.size()), cmd.data(),
                  &replySize, &status);
}

/// A well-formed GET_PARAM with a fuzzed id and value capacity, in one shared buffer.
void getParam(effect_handle_t h, Input& in) {
    const std::uint32_t id = (in.u8() & 1) ? in.u32() : kParamIds[in.u8() % std::size(kParamIds)];
    const std::uint32_t capacity = in.u16() % 1100;
    std::vector<std::uint8_t> buf(sizeof(effect_param_t) + 4 + capacity);
    const effect_param_t header{0, 4, capacity};
    std::memcpy(buf.data(), &header, sizeof(header));
    std::memcpy(buf.data() + sizeof(header), &id, 4);
    std::uint32_t replySize = static_cast<std::uint32_t>(buf.size());
    if ((*h)->command(h, EFFECT_CMD_GET_PARAM, sizeof(effect_param_t) + 4, buf.data(), &replySize,
                      buf.data()) == 0) {
        effect_param_t out;
        std::memcpy(&out, buf.data(), sizeof(out));
        if (replySize > buf.size() || out.vsize > capacity) trap();
    }
}

void setConfig(effect_handle_t h, Input& in) {
    effect_config_t c{};
    const std::uint8_t sel = in.u8();
    for (buffer_config_t* b : {&c.inputCfg, &c.outputCfg}) {
        b->samplingRate = (sel & 1) ? in.u32() : kRates[in.u8() % std::size(kRates)];
        b->channels = (sel & 2) ? in.u32() : ((in.u8() & 1) ? AUDIO_CHANNEL_OUT_STEREO : AUDIO_CHANNEL_OUT_MONO);
        b->format = (sel & 4) ? in.u8()
                              : static_cast<std::uint8_t>((in.u8() & 1) ? AUDIO_FORMAT_PCM_FLOAT : AUDIO_FORMAT_PCM_16_BIT);
        b->accessMode = (sel & 8) ? in.u8() : static_cast<std::uint8_t>(in.u8() % 3);
        b->mask = (sel & 16) ? in.u16() : static_cast<std::uint16_t>(EFFECT_CONFIG_ALL);
    }
    if (!(sel & 32)) {
        c.inputCfg = c.outputCfg;
        c.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    }
    std::int32_t status = 0;
    std::uint32_t replySize = sizeof(status);
    (*h)->command(h, EFFECT_CMD_SET_CONFIG, sizeof(c), &c, &replySize, &status);
}

void process(effect_handle_t h, Input& in) {
    const std::size_t frames = in.u16() % 3000;
    const std::uint8_t mode = in.u8();
    // Room for the largest supported frame (stereo float), whatever the configuration is.
    std::vector<float> a(frames * 2 + 1), b(frames * 2 + 1);
    const std::vector<std::uint8_t> noise = in.bytes(64);
    for (std::size_t i = 0; i < a.size(); ++i) {
        const std::uint8_t v = noise.empty() ? 0 : noise[i % noise.size()];
        switch (v % 16) {
            case 0: a[i] = std::numeric_limits<float>::quiet_NaN(); break;
            case 1: a[i] = std::numeric_limits<float>::infinity(); break;
            case 2: a[i] = 1e30f; break;
            case 3: a[i] = std::numeric_limits<float>::denorm_min(); break;
            default: a[i] = (static_cast<float>(v) - 128.0f) / 64.0f; break;
        }
    }
    audio_buffer_t inBuf{};
    inBuf.frameCount = frames;
    inBuf.f32 = a.data();
    audio_buffer_t outBuf{};
    outBuf.frameCount = (mode & 2) ? frames / 2 : frames;
    outBuf.f32 = (mode & 1) ? a.data() : b.data();
    (*h)->process(h, &inBuf, &outBuf);
}

void simple(effect_handle_t h, Input& in) {
    static constexpr std::uint32_t kSimple[] = {EFFECT_CMD_INIT, EFFECT_CMD_ENABLE, EFFECT_CMD_DISABLE,
                                                EFFECT_CMD_RESET};
    std::int32_t status = 0;
    std::uint32_t replySize = sizeof(status);
    (*h)->command(h, kSimple[in.u8() % 4], 0, nullptr, &replySize, &status);
}

void volumeOrDevice(effect_handle_t h, Input& in) {
    std::uint32_t values[2] = {in.u32(), in.u32()};
    const std::uint32_t code = (in.u8() & 1) ? EFFECT_CMD_SET_VOLUME : EFFECT_CMD_SET_DEVICE;
    std::uint32_t replySize = sizeof(values);
    std::uint32_t reply[2];
    (*h)->command(h, code, sizeof(values), values, &replySize, (in.u8() & 1) ? reply : nullptr);
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    sp::fx::GlobalState global;  // no state file
    const effect_uuid_t& uuid = sp::fx::android::descriptor().uuid;
    effect_handle_t handles[2] = {nullptr, nullptr};
    sp::fx::android::createEffect(global, &uuid, 1, 1, &handles[0]);

    Input in(data, size);
    for (int step = 0; step < 48 && !in.empty(); ++step) {
        const std::uint8_t op = in.u8();
        effect_handle_t& h = handles[op & 1];
        if (h == nullptr) {
            // The second instance shares the session of the first half the time (duplicate guard).
            sp::fx::android::createEffect(global, &uuid, (op & 2) ? 1 : 2, 1, &h);
            continue;
        }
        switch ((op >> 1) % 9) {
            case 0: rawCommand(h, in); break;
            case 1: setParam(h, in); break;
            case 2: getParam(h, in); break;
            case 3: setConfig(h, in); break;
            case 4: process(h, in); break;
            case 5: simple(h, in); break;
            case 6: volumeOrDevice(h, in); break;
            case 7:
                sp::fx::android::releaseEffect(h);
                h = nullptr;
                break;
            default: process(h, in); break;
        }
    }
    for (effect_handle_t h : handles) {
        if (h != nullptr) sp::fx::android::releaseEffect(h);
    }
    return 0;
}
