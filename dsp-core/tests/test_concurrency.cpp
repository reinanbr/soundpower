// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Concurrency tests. They are most useful under the host-tsan preset, where ThreadSanitizer
// checks that parameter hand-off between the control and audio threads is race-free.
#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <random>
#include <thread>
#include <vector>

#include "sp/dsp/math.h"
#include "sp/dsp/processor.h"
#include "sp/dsp/triple_buffer.h"
#include "test_util.h"

namespace sp::dsp {
namespace {

struct Payload {
    std::uint64_t serial;
    std::uint64_t copies[15];  // all equal to serial in a consistent value
};

TEST(TripleBuffer, ReaderSeesNothingUntilWriterPublishes) {
    TripleBuffer<int> tb(5);
    EXPECT_EQ(tb.consume(), nullptr);
    EXPECT_EQ(tb.current(), 5);
    tb.write(1);
    tb.write(2);
    const int* v = tb.consume();
    ASSERT_NE(v, nullptr);
    EXPECT_EQ(*v, 2);  // intermediate values are skipped
    EXPECT_EQ(tb.consume(), nullptr);
    EXPECT_EQ(tb.current(), 2);
    tb.write(3);
    ASSERT_NE(tb.consume(), nullptr);
    EXPECT_EQ(tb.current(), 3);
}

TEST(TripleBuffer, ConcurrentReaderOnlySeesCompleteIncreasingValues) {
    TripleBuffer<Payload> tb;
    std::atomic<bool> done{false};
    constexpr std::uint64_t kWrites = 200'000;

    std::thread writer([&] {
        for (std::uint64_t i = 1; i <= kWrites; ++i) {
            Payload p;
            p.serial = i;
            for (auto& c : p.copies) c = i;
            tb.write(p);
        }
        done.store(true, std::memory_order_release);
    });

    std::uint64_t last = 0;
    std::uint64_t reads = 0;
    // Once `done` is observed, the final value is visible, so that iteration's consume()
    // returns it (unless it was already consumed).
    for (bool finished = false; !finished;) {
        finished = done.load(std::memory_order_acquire);
        if (const Payload* p = tb.consume()) {
            for (auto c : p->copies) ASSERT_EQ(c, p->serial);
            ASSERT_GT(p->serial, last);
            last = p->serial;
            ++reads;
        }
    }
    writer.join();
    EXPECT_EQ(last, kWrites);
    EXPECT_GT(reads, 0u);
}

TEST(ProcessorConcurrency, ParameterUpdatesDuringProcessingAreSafe) {
    const double fs = 48000.0;
    Processor p;
    ASSERT_TRUE(p.prepare(fs, 2));
    std::atomic<bool> stop{false};

    std::thread control([&] {
        std::mt19937 rng(1);
        std::uniform_real_distribution<double> gain(-12.0, 12.0);
        std::uniform_real_distribution<double> freq(30.0, 15000.0);
        std::uniform_int_distribution<int> coin(0, 9);
        ChainParams params;
        params.eq.count = 10;
        while (!stop.load(std::memory_order_relaxed)) {
            for (std::size_t i = 0; i < params.eq.count; ++i) {
                params.eq.bands[i].enabled = true;
                params.eq.bands[i].gainDb = gain(rng);
                params.eq.bands[i].freqHz = freq(rng);
            }
            params.bypass = coin(rng) == 0;
            params.autoPreamp = coin(rng) < 8;
            if (coin(rng) == 0) params.limiter.lookaheadMs = coin(rng) < 5 ? 1.5 : 4.0;
            p.setParams(params);
            if (coin(rng) == 0) p.requestReset();
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    });

    std::vector<float> buf;
    std::mt19937 rng(2);
    std::uniform_real_distribution<float> u(-1.0f, 1.0f);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
    std::size_t blocks = 0;
    while (std::chrono::steady_clock::now() < deadline) {
        buf.resize(2 * 192);
        for (auto& v : buf) v = u(rng);
        p.process(buf.data(), buf.data(), 192);
        for (float v : buf) {
            ASSERT_TRUE(std::isfinite(v));
            // Bypass passes the (unlimited) dry signal, which is at most 1.0 here.
            ASSERT_LE(std::abs(v), 1.0f);
        }
        ++blocks;
        // Meter reads from another thread are fine too.
        (void)p.limiterGainReductionDb();
    }
    stop.store(true);
    control.join();
    EXPECT_GT(blocks, 100u);
}

}  // namespace
}  // namespace sp::dsp
