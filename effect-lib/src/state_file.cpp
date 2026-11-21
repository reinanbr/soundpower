// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "state_file.h"

#include <array>
#include <cstring>

#include "codec.h"
#include "soundpower/protocol.h"
#include "wire.h"

namespace sp::fx {

namespace {

constexpr std::size_t kHeaderSize = 12;
constexpr std::size_t padTo4(std::size_t n) noexcept { return (4 - (n & 3)) & 3; }

}  // namespace

StateFileResult applyStateFile(std::span<const std::uint8_t> file,
                               dsp::ChainParams& params) noexcept {
    StateFileResult result;
    if (file.size() > SP_STATE_FILE_MAX_BYTES) {
        result.error = StateFileError::TooLarge;
        return result;
    }
    if (file.size() < kHeaderSize || std::memcmp(file.data(), SP_STATE_FILE_MAGIC, 4) != 0) {
        result.error = StateFileError::BadHeader;
        return result;
    }

    wire::Reader r(file.subspan(4));
    result.major = r.read<std::uint16_t>();
    result.minor = r.read<std::uint16_t>();
    const auto count = r.read<std::uint32_t>();
    if (result.major != SP_PROTOCOL_VERSION_MAJOR) {
        result.error = StateFileError::MajorMismatch;
        return result;
    }

    dsp::ChainParams next = params;
    for (std::uint32_t i = 0; i < count; ++i) {
        const auto id = r.read<std::uint32_t>();
        const auto size = r.read<std::uint32_t>();
        if (!r.ok()) {
            result.error = StateFileError::Truncated;
            return result;
        }
        if (size > SP_MAX_MESSAGE_BYTES) {
            result.error = StateFileError::BadEntry;
            result.failedId = id;
            return result;
        }
        const auto payload = r.bytes(size);
        r.skip(padTo4(size));
        if (!r.ok()) {
            result.error = StateFileError::Truncated;
            return result;
        }
        if (!isSettable(id)) {
            ++result.skipped;
            continue;
        }
        if (decodeParam(id, payload, next) != 0) {
            result.error = StateFileError::BadEntry;
            result.failedId = id;
            return result;
        }
        ++result.applied;
    }
    if (r.remaining() != 0) {
        result.error = StateFileError::Truncated;
        return result;
    }

    params = next;
    return result;
}

std::vector<std::uint8_t> encodeStateFile(const dsp::ChainParams& params) {
    constexpr std::array<std::uint32_t, 3> kIds = {SP_PARAM_MASTER, SP_PARAM_EQ_STATE,
                                                   SP_PARAM_LIMITER};
    std::vector<std::uint8_t> out(kHeaderSize);
    std::memcpy(out.data(), SP_STATE_FILE_MAGIC, 4);
    const std::uint16_t major = SP_PROTOCOL_VERSION_MAJOR;
    const std::uint16_t minor = SP_PROTOCOL_VERSION_MINOR;
    const auto count = static_cast<std::uint32_t>(kIds.size());
    std::memcpy(out.data() + 4, &major, 2);
    std::memcpy(out.data() + 6, &minor, 2);
    std::memcpy(out.data() + 8, &count, 4);

    std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> payload{};
    for (const std::uint32_t id : kIds) {
        const int size = encodeParam(id, params, payload);
        if (size < 0) continue;  // cannot happen for these IDs
        const auto n = static_cast<std::uint32_t>(size);
        const std::size_t at = out.size();
        out.resize(at + 8 + n + padTo4(n), 0);
        std::memcpy(out.data() + at, &id, 4);
        std::memcpy(out.data() + at + 4, &n, 4);
        std::memcpy(out.data() + at + 8, payload.data(), n);
    }
    return out;
}

const char* toString(StateFileError error) noexcept {
    switch (error) {
        case StateFileError::None: return "ok";
        case StateFileError::TooLarge: return "file too large";
        case StateFileError::BadHeader: return "bad header";
        case StateFileError::MajorMismatch: return "protocol major version mismatch";
        case StateFileError::Truncated: return "truncated or trailing data";
        case StateFileError::BadEntry: return "invalid entry";
    }
    return "unknown";
}

}  // namespace sp::fx
