// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "bench.h"

#include <chrono>
#include <cmath>
#include <random>

#include "sp/dsp/processor.h"

namespace sp::bench {

namespace {

using dsp::ChainParams;
using dsp::FilterType;
using dsp::Processor;

ChainParams paramsWithBands(std::size_t bands, double sampleRate) {
    ChainParams p;
    p.eq.count = bands;
    for (std::size_t i = 0; i < bands; ++i) {
        auto& b = p.eq.bands[i];
        b.type = FilterType::Peaking;
        b.enabled = true;
        b.freqHz = 25.0 * std::pow(0.45 * sampleRate / 25.0,
                                   static_cast<double>(i) / static_cast<double>(std::max<std::size_t>(bands - 1, 1)));
        b.gainDb = (i % 2 == 0 ? 3.0 : -3.0);
        b.q = 1.4;
    }
    return p;
}

Result measure(const Options& o, const char* name, std::size_t bands, std::size_t block,
               bool updateEveryBlock) {
    Processor proc;
    ChainParams params = paramsWithBands(bands, o.sampleRate);
    proc.setParams(params);
    proc.prepare(o.sampleRate, o.channels);

    std::vector<float> buf(block * o.channels);
    std::mt19937 rng(1);
    std::uniform_real_distribution<float> u(-0.5f, 0.5f);
    for (auto& v : buf) v = u(rng);

    const auto totalFrames = static_cast<std::size_t>(o.seconds * o.sampleRate);
    const std::size_t blocks = std::max<std::size_t>(totalFrames / block, 1);
    const std::size_t warmup = std::max<std::size_t>(blocks / 20, 1);

    double elapsed = 0.0;
    for (std::size_t i = 0; i < warmup + blocks; ++i) {
        if (updateEveryBlock && bands > 0) {
            params.eq.bands[i % bands].gainDb = -params.eq.bands[i % bands].gainDb;
            proc.setParams(params);  // control side, not timed
        }
        const auto t0 = std::chrono::steady_clock::now();
        proc.process(buf.data(), buf.data(), block);
        const auto t1 = std::chrono::steady_clock::now();
        if (i >= warmup) elapsed += std::chrono::duration<double>(t1 - t0).count();
        // Keep the signal from decaying into silence (and the optimizer honest).
        buf[0] = u(rng);
    }

    Result r;
    r.name = name;
    r.bands = bands;
    r.blockFrames = block;
    r.microsPerBlock = elapsed / static_cast<double>(blocks) * 1e6;
    const double audioSeconds = static_cast<double>(blocks * block) / o.sampleRate;
    r.realtimePercent = elapsed / audioSeconds * 100.0;
    return r;
}

}  // namespace

std::vector<Result> run(const Options& o) {
    std::vector<Result> results;
    for (std::size_t bands : {std::size_t{0}, std::size_t{10}, std::size_t{32}}) {
        for (std::size_t block : {std::size_t{128}, std::size_t{256}, std::size_t{1024}}) {
            results.push_back(measure(o, "steady", bands, block, false));
        }
    }
    // Worst case: a new EQ curve every block, so the crossfade (two banks) never stops.
    results.push_back(measure(o, "crossfading", 32, 256, true));
    return results;
}

void print(std::FILE* out, const Options& o, const std::vector<Result>& results) {
    std::fprintf(out, "SoundPower dsp-core benchmark: %.0f Hz, %zu channel(s), %.1f s per case\n",
                 o.sampleRate, o.channels, o.seconds);
    std::fprintf(out, "%-12s %6s %7s %14s %12s\n", "case", "bands", "block", "us/block",
                 "% realtime");
    for (const Result& r : results) {
        std::fprintf(out, "%-12s %6zu %7zu %14.3f %12.4f\n", r.name.c_str(), r.bands,
                     r.blockFrames, r.microsPerBlock, r.realtimePercent);
    }
}

}  // namespace sp::bench
