// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Logging for control paths only. Never call these from process(): liblog can block.
// On Android the messages go to logcat with tag "SoundPower". On the host they go to stderr
// when the SP_LOG environment variable is set, and are dropped otherwise.
#pragma once

namespace sp::fx::log {

#if defined(__GNUC__) || defined(__clang__)
#define SP_PRINTF_FORMAT(fmt, args) __attribute__((format(printf, fmt, args)))
#else
#define SP_PRINTF_FORMAT(fmt, args)
#endif

void info(const char* fmt, ...) SP_PRINTF_FORMAT(1, 2);
void warn(const char* fmt, ...) SP_PRINTF_FORMAT(1, 2);
void error(const char* fmt, ...) SP_PRINTF_FORMAT(1, 2);

}  // namespace sp::fx::log
