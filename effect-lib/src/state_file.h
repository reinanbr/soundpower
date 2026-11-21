// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// The persisted state file (docs/protocol.md, "Persisted state file"):
//
//   header:  char magic[4] = "SPST" | u16 major | u16 minor | u32 entry_count
//   entry:   u32 id | u32 size | payload[size] | pad to 4
//
// Entries use the SET_PARAM payloads and are applied in file order.
#pragma once

#include <cstdint>
#include <span>
#include <vector>

#include "sp/dsp/types.h"

namespace sp::fx {

enum class StateFileError : std::uint8_t {
    None,
    TooLarge,       ///< larger than SP_STATE_FILE_MAX_BYTES
    BadHeader,      ///< shorter than the header, or wrong magic
    MajorMismatch,  ///< written for another major protocol version
    Truncated,      ///< an entry runs past the end, or bytes follow the last entry
    BadEntry,       ///< a known ID with an invalid payload, or an oversized entry
};

struct StateFileResult {
    StateFileError error = StateFileError::None;
    std::uint16_t major = 0;
    std::uint16_t minor = 0;
    std::uint32_t applied = 0;  ///< entries decoded into the parameters
    std::uint32_t skipped = 0;  ///< entries with IDs this version does not know (newer minor)
    std::uint32_t failedId = 0; ///< for BadEntry
};

/// Applies a state file to `params`, all or nothing: on any error `params` is unchanged.
/// Entries with unknown IDs are skipped, so a file written by a newer minor version still
/// loads. Values are not range-checked; callers run dsp::sanitize() on the result.
[[nodiscard]] StateFileResult applyStateFile(std::span<const std::uint8_t> file,
                                             dsp::ChainParams& params) noexcept;

/// Serializes MASTER, EQ_STATE and LIMITER as a state file. Allocates; not for real-time use.
[[nodiscard]] std::vector<std::uint8_t> encodeStateFile(const dsp::ChainParams& params);

[[nodiscard]] const char* toString(StateFileError error) noexcept;

}  // namespace sp::fx
