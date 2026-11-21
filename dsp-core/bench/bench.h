// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <cstddef>
#include <cstdio>
#include <string>
#include <vector>

namespace sp::bench {

struct Options {
    double sampleRate = 48000.0;
    std::size_t channels = 2;
    double seconds = 5.0;  ///< audio duration processed per case
};

struct Result {
    std::string name;
    std::size_t bands = 0;
    std::size_t blockFrames = 0;
    double microsPerBlock = 0.0;
    double realtimePercent = 0.0;  ///< CPU time / audio duration, one core
};

/// Times Processor::process() for 0, 10 and 32 bands at several host block sizes, plus a
/// worst case where every block starts a new EQ crossfade.
std::vector<Result> run(const Options& options);

void print(std::FILE* out, const Options& options, const std::vector<Result>& results);

}  // namespace sp::bench
