// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// AELI glue: translates the Android effect API (hardware/audio_effect.h, system/audio_effect.h)
// into sp::fx::Instance / GlobalState calls. Every command validates its sizes before touching
// a buffer: any app holding MODIFY_AUDIO_SETTINGS can send commands into the audio HAL process.
#include "effect_api.h"

#include <cerrno>
#include <cinttypes>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <span>
#include <type_traits>

#include "../global_state.h"
#include "../instance.h"
#include "../log.h"
#include "soundpower/protocol.h"
#include "sp/dsp/math.h"

namespace sp::fx::android {

static_assert(SP_FORMAT_PCM_16_BIT == AUDIO_FORMAT_PCM_16_BIT);
static_assert(SP_FORMAT_PCM_FLOAT == AUDIO_FORMAT_PCM_FLOAT);

namespace {

// ---- Descriptor ----------------------------------------------------------------------------

// AudioPolicy adds cpuLoad (0.1 MIPS units) and memoryUsage (KB) of every live effect instance
// and refuses new effects past fixed limits sized for 2010-era phones (43 MIPS / 512 KB, shared
// with every other effect on the device). Reporting honest numbers would make instance
// creation fail once a few sessions play, so these stay small on purpose. See docs/risks.md.
constexpr std::uint16_t kCpuLoad = 0;
constexpr std::uint16_t kMemoryUsageKb = 1;

constexpr effect_descriptor_t makeDescriptor(std::uint32_t volumeFlag) {
    return {
        .type = SP_EFFECT_TYPE_UUID_INIT,
        .uuid = SP_EFFECT_IMPL_UUID_INIT,
        .apiVersion = EFFECT_CONTROL_API_VERSION,
        .flags = EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | volumeFlag |
                 EFFECT_FLAG_DEVICE_IND,
        .cpuLoad = kCpuLoad,
        .memoryUsage = kMemoryUsageKb,
        .name = SP_EFFECT_NAME,
        .implementor = SP_EFFECT_IMPLEMENTOR,
    };
}

// Direct volume control off: AudioFlinger applies the stream volume before the chain and tells
// the effect (SET_VOLUME without a reply). On: the effect is the chain's volume controller, the
// mixer feeds it at full scale and the chain applies the volume before its limiter.
constexpr effect_descriptor_t kDescriptor = makeDescriptor(EFFECT_FLAG_VOLUME_IND);
constexpr effect_descriptor_t kDescriptorVolumeCtrl = makeDescriptor(EFFECT_FLAG_VOLUME_CTRL);

bool sameUuid(const effect_uuid_t& a, const effect_uuid_t& b) noexcept {
    return a.timeLow == b.timeLow && a.timeMid == b.timeMid &&
           a.timeHiAndVersion == b.timeHiAndVersion && a.clockSeq == b.clockSeq &&
           std::memcmp(a.node, b.node, sizeof(a.node)) == 0;
}

// ---- Module ----------------------------------------------------------------------------------

struct ModuleState {
    GlobalState* global = nullptr;
    Instance* instance = nullptr;
    std::mutex configMutex;  ///< guards `config`; taken before the global mutex, never after
    effect_config_t config{};
};

/// effect_handle_t points at `itfe`, which must be the first member.
struct EffectModule {
    const effect_interface_s* itfe;
    ModuleState* state;
};
static_assert(std::is_standard_layout_v<EffectModule>);
static_assert(offsetof(EffectModule, itfe) == 0);

extern const effect_interface_s kInterface;

EffectModule* moduleFrom(effect_handle_t self) noexcept {
    if (self == nullptr || *self != &kInterface) return nullptr;
    return reinterpret_cast<EffectModule*>(self);
}

effect_config_t defaultConfig() noexcept {
    effect_config_t c{};
    for (buffer_config_t* b : {&c.inputCfg, &c.outputCfg}) {
        b->samplingRate = 48000;
        b->channels = AUDIO_CHANNEL_OUT_STEREO;
        b->format = static_cast<std::uint8_t>(AUDIO_FORMAT_PCM_FLOAT);
        b->mask = EFFECT_CONFIG_ALL;
    }
    c.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    c.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_WRITE;
    return c;
}

/// Applies the fields of `src` that its mask marks valid (a zero mask means "all": older
/// callers don't fill it in).
void mergeBufferConfig(buffer_config_t& dst, const buffer_config_t& src) noexcept {
    const std::uint16_t mask = src.mask != 0 ? src.mask : static_cast<std::uint16_t>(EFFECT_CONFIG_ALL);
    if (mask & EFFECT_CONFIG_BUFFER) {
        dst.buffer = src.buffer;
        dst.bufferProvider = src.bufferProvider;
    }
    if (mask & EFFECT_CONFIG_SMP_RATE) dst.samplingRate = src.samplingRate;
    if (mask & EFFECT_CONFIG_CHANNELS) dst.channels = src.channels;
    if (mask & EFFECT_CONFIG_FORMAT) dst.format = src.format;
    if (mask & EFFECT_CONFIG_ACC_MODE) dst.accessMode = src.accessMode;
    dst.mask = static_cast<std::uint16_t>(dst.mask | mask);
}

int toStreamConfig(const effect_config_t& c, StreamConfig& out) noexcept {
    const buffer_config_t& in = c.inputCfg;
    const buffer_config_t& o = c.outputCfg;
    if (in.samplingRate != o.samplingRate || in.channels != o.channels || in.format != o.format) {
        return -EINVAL;
    }
    if (o.channels == AUDIO_CHANNEL_OUT_STEREO) {
        out.channels = 2;
    } else if (o.channels == AUDIO_CHANNEL_OUT_MONO) {
        out.channels = 1;
    } else {
        return -EINVAL;  // AudioFlinger falls back to stereo
    }
    if (o.format == AUDIO_FORMAT_PCM_FLOAT) {
        out.format = SampleFormat::Float;
    } else if (o.format == AUDIO_FORMAT_PCM_16_BIT) {
        out.format = SampleFormat::Int16;
    } else {
        return -EINVAL;
    }
    if (o.accessMode == EFFECT_BUFFER_ACCESS_WRITE) {
        out.accumulate = false;
    } else if (o.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE) {
        out.accumulate = true;
    } else {
        return -EINVAL;
    }
    out.sampleRate = o.samplingRate;
    return 0;
}

const char* stateName(std::uint32_t state) noexcept {
    switch (state) {
        case SP_STATE_UNINITIALIZED: return "uninitialized";
        case SP_STATE_INITIALIZED: return "initialized";
        case SP_STATE_ACTIVE: return "active";
        case SP_STATE_DISABLING: return "disabling";
        default: return "?";
    }
}

// ---- Commands ----------------------------------------------------------------------------

constexpr std::uint32_t kParamHeader = sizeof(effect_param_t);  // status, psize, vsize
constexpr std::uint32_t padded4(std::uint32_t n) noexcept { return (n + 3u) & ~3u; }

bool hasIntReply(const std::uint32_t* replySize, const void* reply) noexcept {
    return reply != nullptr && replySize != nullptr && *replySize >= sizeof(std::int32_t);
}

std::int32_t replyStatus(std::int32_t status, std::uint32_t* replySize, void* reply) noexcept {
    std::memcpy(reply, &status, sizeof(status));
    *replySize = sizeof(status);
    return 0;
}

std::int32_t setConfig(ModuleState& s, std::uint32_t cmdSize, const void* cmd,
                       std::uint32_t* replySize, void* reply) {
    if (cmd == nullptr || cmdSize < sizeof(effect_config_t) || !hasIntReply(replySize, reply)) {
        return -EINVAL;
    }
    effect_config_t requested;
    std::memcpy(&requested, cmd, sizeof(requested));

    std::lock_guard lock(s.configMutex);
    effect_config_t merged = s.config;
    mergeBufferConfig(merged.inputCfg, requested.inputCfg);
    mergeBufferConfig(merged.outputCfg, requested.outputCfg);
    StreamConfig stream;
    int status = toStreamConfig(merged, stream);
    if (status == 0) status = s.instance->configure(stream);
    if (status == 0) {
        s.config = merged;
    } else {
        log::warn("session %d: rejected config %u Hz, channels 0x%x, format 0x%x, access %u",
                  s.instance->sessionId(), merged.outputCfg.samplingRate, merged.outputCfg.channels,
                  unsigned{merged.outputCfg.format}, unsigned{merged.outputCfg.accessMode});
    }
    return replyStatus(status, replySize, reply);
}

std::int32_t getConfig(ModuleState& s, std::uint32_t* replySize, void* reply) {
    if (reply == nullptr || replySize == nullptr || *replySize < sizeof(effect_config_t)) {
        return -EINVAL;
    }
    std::lock_guard lock(s.configMutex);
    std::memcpy(reply, &s.config, sizeof(effect_config_t));
    *replySize = sizeof(effect_config_t);
    return 0;
}

std::int32_t setParam(ModuleState& s, std::uint32_t cmdSize, const void* cmd,
                      std::uint32_t* replySize, void* reply) {
    if (cmd == nullptr || cmdSize < kParamHeader || !hasIntReply(replySize, reply)) return -EINVAL;
    effect_param_t header;
    std::memcpy(&header, cmd, kParamHeader);
    if (header.psize > SP_MAX_MESSAGE_BYTES || header.vsize > SP_MAX_MESSAGE_BYTES) return -EINVAL;
    const std::uint32_t valueOffset = padded4(header.psize);
    if (cmdSize < kParamHeader + valueOffset + header.vsize) return -EINVAL;

    const auto* bytes = static_cast<const std::uint8_t*>(cmd);
    int status = -EINVAL;
    if (header.psize == sizeof(std::uint32_t)) {  // v1 has no indexed (psize 8) parameters
        std::uint32_t id;
        std::memcpy(&id, bytes + kParamHeader, sizeof(id));
        status = s.global->setParam(
            id, std::span<const std::uint8_t>(bytes + kParamHeader + valueOffset, header.vsize));
    }
    return replyStatus(status, replySize, reply);
}

// AudioEffect::getParameter() and the HIDL wrapper pass the same buffer as command and reply,
// so everything needed from the command is read before the reply is written.
std::int32_t getParam(ModuleState& s, std::uint32_t cmdSize, const void* cmd,
                      std::uint32_t* replySize, void* reply) {
    if (cmd == nullptr || reply == nullptr || replySize == nullptr || cmdSize < kParamHeader) {
        return -EINVAL;
    }
    effect_param_t header;
    std::memcpy(&header, cmd, kParamHeader);
    if (header.psize == 0 || header.psize > SP_MAX_MESSAGE_BYTES ||
        cmdSize < kParamHeader + header.psize) {
        return -EINVAL;
    }
    const std::uint32_t valueOffset = padded4(header.psize);
    if (*replySize < kParamHeader + valueOffset) return -EINVAL;

    const auto* in = static_cast<const std::uint8_t*>(cmd);
    std::uint32_t id = 0;
    const bool simpleId = header.psize == sizeof(std::uint32_t);
    if (simpleId) std::memcpy(&id, in + kParamHeader, sizeof(id));

    auto* out = static_cast<std::uint8_t*>(reply);
    if (out != in) std::memcpy(out + kParamHeader, in + kParamHeader, header.psize);
    std::memset(out + kParamHeader + header.psize, 0, valueOffset - header.psize);

    const std::uint32_t room = *replySize - kParamHeader - valueOffset;
    const std::uint32_t capacity = header.vsize < room ? header.vsize : room;
    const int n = simpleId ? s.global->getParam(
                                 *s.instance, id,
                                 std::span<std::uint8_t>(out + kParamHeader + valueOffset, capacity))
                           : -EINVAL;

    effect_param_t result = header;
    result.status = n < 0 ? n : 0;
    result.vsize = n < 0 ? 0u : static_cast<std::uint32_t>(n);
    std::memcpy(out, &result, kParamHeader);
    *replySize = kParamHeader + valueOffset + result.vsize;
    return 0;
}

/// dB for the dump line: silence prints as -999.0 instead of -inf, so parsers see a number.
double dumpDb(double db) noexcept { return db > -999.0 ? db : -999.0; }

std::int32_t dump(ModuleState& s, std::uint32_t cmdSize, const void* cmd) {
    if (cmd == nullptr || cmdSize < sizeof(std::uint32_t)) return -EINVAL;
    int fd;
    std::memcpy(&fd, cmd, sizeof(fd));
    const Status st = s.global->status(*s.instance);
    const Instance::VolumeReport v = s.instance->takeVolumeReport();
    // Fields are only ever appended: android-core/root's EffectDump parses this line.
    dprintf(fd,
            "SoundPower %u.%u.%u (protocol %d.%d): session %d io %d, %s, %u Hz, %u ch, "
            "format 0x%x, cpu avg %.2f%% peak %.2f%%, limiter %.2f dB, %" PRIu64
            " frames, flags 0x%x, %u instances, volume %.1f/%.1f dB %s (stage %.1f dB), "
            "peak in %.1f out %.1f dBFS\n",
            unsigned{SP_VERSION_MAJOR}, unsigned{SP_VERSION_MINOR}, unsigned{SP_VERSION_PATCH},
            SP_PROTOCOL_VERSION_MAJOR, SP_PROTOCOL_VERSION_MINOR, st.sessionId,
            s.instance->ioId(), stateName(st.state), st.sampleRate, st.channels, st.format,
            static_cast<double>(st.cpuAvgPercent), static_cast<double>(st.cpuPeakPercent),
            static_cast<double>(st.limiterGrDb), st.framesProcessed, st.flags, st.instances,
            dumpDb(dsp::gainToDb(v.volume.left)), dumpDb(dsp::gainToDb(v.volume.right)),
            v.volume.apply ? "applied" : "by mixer", dumpDb(v.stageGainDb), dumpDb(v.inputPeakDb),
            dumpDb(v.outputPeakDb));
    return 0;
}

std::int32_t command(effect_handle_t self, std::uint32_t cmdCode, std::uint32_t cmdSize,
                     void* cmd, std::uint32_t* replySize, void* reply) {
    EffectModule* m = moduleFrom(self);
    if (m == nullptr) return -EINVAL;
    ModuleState& s = *m->state;

    switch (cmdCode) {
        case EFFECT_CMD_INIT:
            if (!hasIntReply(replySize, reply)) return -EINVAL;
            s.instance->reset();
            return replyStatus(0, replySize, reply);

        case EFFECT_CMD_SET_CONFIG:
            return setConfig(s, cmdSize, cmd, replySize, reply);

        case EFFECT_CMD_GET_CONFIG:
            return getConfig(s, replySize, reply);

        case EFFECT_CMD_RESET:
            s.instance->reset();
            return 0;

        case EFFECT_CMD_ENABLE:
        case EFFECT_CMD_DISABLE:
            if (!hasIntReply(replySize, reply)) return -EINVAL;
            s.instance->setEnabled(cmdCode == EFFECT_CMD_ENABLE);
            return replyStatus(0, replySize, reply);

        case EFFECT_CMD_SET_PARAM:
            return setParam(s, cmdSize, cmd, replySize, reply);

        case EFFECT_CMD_GET_PARAM:
            return getParam(s, cmdSize, cmd, replySize, reply);

        case EFFECT_CMD_SET_DEVICE: {
            if (cmd == nullptr || cmdSize < sizeof(std::uint32_t)) return -EINVAL;
            std::uint32_t device;
            std::memcpy(&device, cmd, sizeof(device));
            s.instance->setDevice(device);
            return 0;
        }

        case EFFECT_CMD_SET_VOLUME: {
            // May come from AudioFlinger's mixer thread: atomics only. AudioFlinger sends it
            // when the volume or the chain's volume controller changes, and passes a reply
            // buffer only to the controller: the last enabled effect with EFFECT_FLAG_VOLUME_CTRL
            // in the chain. The controller answers with the volume the mixer must still apply.
            if (cmd == nullptr || cmdSize < sizeof(std::uint32_t)) return -EINVAL;
            std::uint32_t volume[2];
            std::memcpy(&volume[0], cmd, sizeof(std::uint32_t));
            volume[1] = volume[0];
            if (cmdSize >= 2 * sizeof(std::uint32_t)) {
                std::memcpy(&volume[1], static_cast<const std::uint8_t*>(cmd) + 4, 4);
            }
            const bool controller =
                reply != nullptr && replySize != nullptr && *replySize >= sizeof(volume);
            // Only an effect that claimed volume control takes the volume over. Without the
            // claim a reply is unexpected; answer "apply the full volume yourself".
            const bool apply = controller && s.global->claimsVolumeControl();
            s.instance->setStreamVolume(volume[0], volume[1], apply);
            if (controller) {
                if (apply) volume[0] = volume[1] = 1u << 24;  // unity: the chain applies it
                std::memcpy(reply, volume, sizeof(volume));
                *replySize = sizeof(volume);
            }
            return 0;
        }

        case EFFECT_CMD_OFFLOAD: {
            if (cmd == nullptr || cmdSize < sizeof(effect_offload_param_t) ||
                !hasIntReply(replySize, reply)) {
                return -EINVAL;
            }
            // isOffload is a C bool filled in by an untrusted caller: read it as a byte, since any
            // value other than 0 or 1 would be undefined behavior as a bool.
            static_assert(offsetof(effect_offload_param_t, isOffload) == 0 && sizeof(bool) == 1);
            std::uint8_t isOffload;
            std::memcpy(&isOffload, cmd, 1);
            return replyStatus(isOffload != 0 ? -EINVAL : 0, replySize, reply);
        }

        case EFFECT_CMD_DUMP:
            return dump(s, cmdSize, cmd);

        default:
            return -EINVAL;
    }
}

std::int32_t process(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    EffectModule* m = moduleFrom(self);
    // NULL descriptors would mean "use the buffers from SET_CONFIG". No framework since
    // Android 4 does that, and reading the config here would race with SET_CONFIG.
    if (m == nullptr || in == nullptr || out == nullptr || in->raw == nullptr ||
        out->raw == nullptr || in->frameCount != out->frameCount) {
        return -EINVAL;
    }
    return m->state->instance->process(in->raw, out->raw, in->frameCount);
}

std::int32_t getDescriptor(effect_handle_t self, effect_descriptor_t* descriptor) {
    EffectModule* m = moduleFrom(self);
    if (m == nullptr || descriptor == nullptr) return -EINVAL;
    *descriptor = android::descriptor(m->state->global->claimsVolumeControl());
    return 0;
}

const effect_interface_s kInterface = {
    .process = process,
    .command = command,
    .get_descriptor = getDescriptor,
    .process_reverse = nullptr,
};

// ---- Library entry points ------------------------------------------------------------------

std::int32_t libCreateEffect(const effect_uuid_t* uuid, std::int32_t sessionId, std::int32_t ioId,
                             effect_handle_t* handle) {
    return createEffect(processState(), uuid, sessionId, ioId, handle);
}

std::int32_t libReleaseEffect(effect_handle_t handle) { return releaseEffect(handle); }

std::int32_t libGetDescriptor(const effect_uuid_t* uuid, effect_descriptor_t* descriptor) {
    if (uuid == nullptr || descriptor == nullptr || !sameUuid(*uuid, kDescriptor.uuid)) {
        return -EINVAL;
    }
    // The effects factory calls this when it loads the library and keeps the answer: this is
    // where direct volume control is decided for the life of the process.
    *descriptor = android::descriptor(processState().claimsVolumeControl());
    return 0;
}

}  // namespace

const effect_descriptor_t& descriptor(bool volumeControl) noexcept {
    return volumeControl ? kDescriptorVolumeCtrl : kDescriptor;
}

std::int32_t createEffect(GlobalState& global, const effect_uuid_t* uuid, std::int32_t sessionId,
                          std::int32_t ioId, effect_handle_t* handle) {
    if (uuid == nullptr || handle == nullptr) return -EINVAL;
    if (!sameUuid(*uuid, kDescriptor.uuid)) return -ENOENT;

    global.reloadStateFileIfChanged();
    (void)global.claimsVolumeControl();  // decided before the first SET_VOLUME can arrive
    Instance* instance = global.createInstance(sessionId, ioId);
    if (instance == nullptr) return -ENOMEM;
    auto* state = new (std::nothrow) ModuleState;
    auto* module = new (std::nothrow) EffectModule{&kInterface, state};
    if (state == nullptr || module == nullptr) {
        delete module;
        delete state;
        global.destroyInstance(instance);
        return -ENOMEM;
    }
    state->global = &global;
    state->instance = instance;
    state->config = defaultConfig();
    *handle = reinterpret_cast<effect_handle_t>(module);
    log::info("created instance for session %d, io %d (%zu alive)", sessionId, ioId,
              global.instanceCount());
    return 0;
}

std::int32_t releaseEffect(effect_handle_t handle) {
    EffectModule* m = moduleFrom(handle);
    if (m == nullptr) return -EINVAL;
    ModuleState* s = m->state;
    const std::int32_t session = s->instance->sessionId();
    s->global->destroyInstance(s->instance);
    const std::size_t alive = s->global->instanceCount();
    m->itfe = nullptr;  // a stale handle now fails moduleFrom() instead of reaching freed state
    delete s;
    delete m;
    log::info("released instance for session %d (%zu alive)", session, alive);
    return 0;
}

GlobalState& processState() {
    // Never destroyed: the audio HAL never unloads effect libraries, and a destructor running at
    // process exit could race with threads still inside the library.
    static GlobalState* const state = new GlobalState(SP_STATE_FILE);
    return *state;
}

}  // namespace sp::fx::android

extern "C" {

__attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,  // 3.0: create_effect_3_1 is not used
    .name = SP_EFFECT_NAME,
    .implementor = SP_EFFECT_IMPLEMENTOR,
    .create_effect = sp::fx::android::libCreateEffect,
    .release_effect = sp::fx::android::libReleaseEffect,
    .get_descriptor = sp::fx::android::libGetDescriptor,
    .create_effect_3_1 = nullptr,
};

}  // extern "C"
