// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Protocol 1.x payloads (protocol/include/soundpower/protocol.h) <-> dsp-core parameter types.
// Pure functions: no allocation, no I/O, no Android headers. Payloads are little-endian and
// are (de)serialized field by field, so buffers need no particular alignment.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "soundpower/protocol.h"
#include "sp/dsp/types.h"

namespace sp::fx {

/// True for the IDs a controller may SET (MASTER, EQ_STATE, LIMITER, TONE, STEREO, COMPRESSOR).
[[nodiscard]] bool isSettable(std::uint32_t id) noexcept;

/// Decodes a SET payload for `id` and updates only the fields that ID covers. Accepts a
/// `value` longer than the payload (trailing bytes are ignored, for forward compatibility).
/// Returns 0, or -EINVAL for unknown or read-only IDs and malformed payloads (too short, band
/// count above 32, unknown band type). On error `params` is left unchanged.
///
/// Values are not range-checked here; callers run dsp::sanitize() on the result.
[[nodiscard]] int decodeParam(std::uint32_t id, std::span<const std::uint8_t> value,
                              dsp::ChainParams& params) noexcept;

/// Encodes the GET payload of a settable ID from `params`. Returns the payload size, or
/// -EINVAL if `id` is not settable or `out` is too small.
[[nodiscard]] int encodeParam(std::uint32_t id, const dsp::ChainParams& params,
                              std::span<std::uint8_t> out) noexcept;

/// INPUT_VOLUME_SESSIONS (1.3): the sessions that get the stream volume at the chain's input.
struct InputVolumeSessions {
    std::array<std::int32_t, SP_MAX_INPUT_VOLUME_SESSIONS> ids{};
    std::uint32_t count = 0;

    [[nodiscard]] bool contains(std::int32_t session) const noexcept {
        for (std::uint32_t i = 0; i < count; ++i) {
            if (ids[i] == session) return true;
        }
        return false;
    }
};

/// Decodes an INPUT_VOLUME_SESSIONS payload (trailing bytes ignored). Returns 0, or -EINVAL for a
/// short payload or more than SP_MAX_INPUT_VOLUME_SESSIONS sessions; `out` is left unchanged then.
[[nodiscard]] int decodeInputVolumeSessions(std::span<const std::uint8_t> value,
                                            InputVolumeSessions& out) noexcept;

/// Encodes an INPUT_VOLUME_SESSIONS payload. Returns its size or -EINVAL if `out` is too small.
[[nodiscard]] int encodeInputVolumeSessions(const InputVolumeSessions& sessions,
                                            std::span<std::uint8_t> out) noexcept;

/// PROTOCOL_INFO payload. Returns its size or -EINVAL if `out` is too small.
[[nodiscard]] int encodeProtocolInfo(std::span<std::uint8_t> out) noexcept;

/// STATUS fields, in wire order (see sp_status_t).
struct Status {
    std::uint32_t sampleRate = 0;
    std::uint32_t channels = 0;
    std::uint32_t format = 0;  ///< SP_FORMAT_*
    std::uint32_t state = 0;   ///< enum sp_instance_state
    float cpuAvgPercent = 0.0f;
    float cpuPeakPercent = 0.0f;
    std::uint32_t instances = 0;
    std::int32_t sessionId = 0;
    std::uint32_t flags = 0;  ///< SP_STATUS_FLAG_*
    float limiterGrDb = 0.0f;
    std::uint64_t framesProcessed = 0;
};

/// STATUS payload. Returns its size or -EINVAL if `out` is too small.
[[nodiscard]] int encodeStatus(const Status& status, std::span<std::uint8_t> out) noexcept;

/// The library version packed as in PROTOCOL_INFO.lib_version.
[[nodiscard]] std::uint32_t packedLibraryVersion() noexcept;

}  // namespace sp::fx
