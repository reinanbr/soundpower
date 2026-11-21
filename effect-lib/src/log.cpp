// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "log.h"

#include <cstdarg>

#include "soundpower/protocol.h"

#if defined(__ANDROID__)
#include <android/log.h>
#else
#include <cstdio>
#include <cstdlib>
#endif

namespace sp::fx::log {

namespace {

#if defined(__ANDROID__)
SP_PRINTF_FORMAT(2, 0) void emit(int priority, const char* fmt, std::va_list args) {
    __android_log_vprint(priority, SP_LOG_TAG, fmt, args);
}
constexpr int kInfo = ANDROID_LOG_INFO;
constexpr int kWarn = ANDROID_LOG_WARN;
constexpr int kError = ANDROID_LOG_ERROR;
#else
SP_PRINTF_FORMAT(2, 0) void emit(int priority, const char* fmt, std::va_list args) {
    static const bool enabled = std::getenv("SP_LOG") != nullptr;
    if (!enabled) return;
    static const char* const kLevels[] = {"I", "W", "E"};
    std::fprintf(stderr, "%s/" SP_LOG_TAG ": ", kLevels[priority]);
    std::vfprintf(stderr, fmt, args);
    std::fputc('\n', stderr);
}
constexpr int kInfo = 0;
constexpr int kWarn = 1;
constexpr int kError = 2;
#endif

}  // namespace

void info(const char* fmt, ...) {
    std::va_list args;
    va_start(args, fmt);
    emit(kInfo, fmt, args);
    va_end(args);
}

void warn(const char* fmt, ...) {
    std::va_list args;
    va_start(args, fmt);
    emit(kWarn, fmt, args);
    va_end(args);
}

void error(const char* fmt, ...) {
    std::va_list args;
    va_start(args, fmt);
    emit(kError, fmt, args);
    va_end(args);
}

}  // namespace sp::fx::log
