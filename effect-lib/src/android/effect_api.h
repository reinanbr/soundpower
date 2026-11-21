// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// The Android effect library interface (AELI) of libsoundpower.so. Internal header: the only
// symbol the shared library exports is AELI itself (see exports.map); the functions below are
// for the host harness and the fuzzer, which create instances against their own GlobalState.
#pragma once

#include <hardware/audio_effect.h>

#include <cstdint>

namespace sp::fx {
class GlobalState;
}

namespace sp::fx::android {

/// The effect descriptor returned by get_descriptor().
[[nodiscard]] const effect_descriptor_t& descriptor() noexcept;

/// create_effect(), against `global` instead of the process-wide state.
std::int32_t createEffect(GlobalState& global, const effect_uuid_t* uuid, std::int32_t sessionId,
                          std::int32_t ioId, effect_handle_t* handle);

/// release_effect().
std::int32_t releaseEffect(effect_handle_t handle);

/// The process-wide state used by the exported library (reads SP_STATE_FILE).
[[nodiscard]] GlobalState& processState();

}  // namespace sp::fx::android

extern "C" {
// Defined in effect_api.cpp; looked up with dlsym("AELI") by the effects factory.
extern audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM;
}
