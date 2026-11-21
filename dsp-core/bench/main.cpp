// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// sp-bench [--quick] [--fs HZ] [--channels N] [--seconds S]
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "bench.h"

int main(int argc, char** argv) {
    sp::bench::Options o;
    for (int i = 1; i < argc; ++i) {
        const char* a = argv[i];
        const bool hasValue = i + 1 < argc;
        if (std::strcmp(a, "--quick") == 0) {
            o.seconds = 0.5;
        } else if (std::strcmp(a, "--fs") == 0 && hasValue) {
            o.sampleRate = std::atof(argv[++i]);
        } else if (std::strcmp(a, "--channels") == 0 && hasValue) {
            o.channels = static_cast<std::size_t>(std::atoi(argv[++i]));
        } else if (std::strcmp(a, "--seconds") == 0 && hasValue) {
            o.seconds = std::atof(argv[++i]);
        } else {
            std::fprintf(stderr, "usage: sp-bench [--quick] [--fs HZ] [--channels 1|2] [--seconds S]\n");
            return 2;
        }
    }
    if (o.channels < 1 || o.channels > 2 || o.sampleRate < 8000.0 || o.seconds <= 0.0) {
        std::fprintf(stderr, "sp-bench: invalid options\n");
        return 2;
    }
    sp::bench::print(stdout, o, sp::bench::run(o));
    return 0;
}
