// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Stress test for the threading contract: audio threads keep processing while control threads
// change parameters, reconfigure, enable/disable, reset, read status, and create and destroy
// other instances. Meant to run under ThreadSanitizer (host-tsan preset); in other builds it
// still checks that the output stays finite and bounded.
#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <thread>
#include <vector>

#include "codec.h"
#include "global_state.h"
#include "instance.h"
#include "soundpower/protocol.h"
#include "test_util.h"

namespace sp::fx {
namespace {

TEST(Concurrency, ControlAndAudioThreadsDoNotInterfere) {
    GlobalState g;
    constexpr int kAudioThreads = 2;
    Instance* streams[kAudioThreads] = {g.createInstance(1, 1), g.createInstance(2, 1)};
    for (Instance* s : streams) {
        ASSERT_EQ(s->configure({48000, 2, SampleFormat::Float, false}), 0);
        s->setEnabled(true);
    }

    std::atomic<bool> stop{false};
    std::atomic<bool> bad{false};
    std::atomic<long> blocks{0};

    std::vector<std::thread> threads;
    for (int t = 0; t < kAudioThreads; ++t) {
        threads.emplace_back([&, t] {
            std::vector<float> buf = dsp::test::whiteNoise(0.5, 2 * 192, static_cast<std::uint32_t>(t + 1));
            const std::vector<float> src = buf;
            while (!stop.load(std::memory_order_relaxed)) {
                buf = src;
                const int r = streams[t]->process(buf.data(), buf.data(), 192);
                if (r != 0 && r != -ENODATA) bad = true;
                for (const float v : buf) {
                    if (!std::isfinite(v) || std::abs(v) > 1.0f) bad = true;
                }
                blocks.fetch_add(1, std::memory_order_relaxed);
            }
        });
    }

    // Control thread 1: parameters, as the app sends them.
    threads.emplace_back([&] {
        dsp::ChainParams p;
        p.eq.count = 10;
        std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> buf{};
        for (int i = 0; !stop.load(std::memory_order_relaxed); ++i) {
            for (std::size_t b = 0; b < 10; ++b) {
                p.eq.bands[b] = {dsp::FilterType::Peaking, true, 31.25 * std::pow(2.0, static_cast<double>(b)),
                                 static_cast<double>((i + static_cast<int>(b)) % 25) - 12.0, 1.4};
            }
            p.limiter.lookaheadMs = (i % 7 == 0) ? 3.0 : 1.5;
            p.bypass = (i % 11 == 0);
            for (const std::uint32_t id : {std::uint32_t{SP_PARAM_EQ_STATE}, std::uint32_t{SP_PARAM_MASTER},
                                           std::uint32_t{SP_PARAM_LIMITER}}) {
                const int n = encodeParam(id, p, buf);
                if (g.setParam(id, std::span(buf.data(), static_cast<std::size_t>(n))) != 0) bad = true;
            }
            std::array<std::uint8_t, SP_STATUS_SIZE> st{};
            if (g.getParam(*streams[i % kAudioThreads], SP_PARAM_STATUS, st) < 0) bad = true;
        }
    });

    // Control thread 2: format changes, enable/disable, reset.
    threads.emplace_back([&] {
        for (int i = 0; !stop.load(std::memory_order_relaxed); ++i) {
            Instance* s = streams[i % kAudioThreads];
            switch (i % 4) {
                case 0: s->configure({i % 8 < 4 ? 44100u : 48000u, 2, SampleFormat::Float, false}); break;
                case 1: s->setEnabled(i % 3 != 0); break;
                case 2: s->reset(); break;
                default:
                    s->setStreamVolume(static_cast<std::uint32_t>(i), 1u << 24, i % 8 == 3);
                    break;
            }
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    });

    // Control thread 3: other sessions come and go (registry churn, duplicate guard).
    threads.emplace_back([&] {
        while (!stop.load(std::memory_order_relaxed)) {
            Instance* extra = g.createInstance(1, 1);  // duplicate of streams[0]
            Instance* other = g.createInstance(99, 1);
            if (extra != nullptr) extra->configure({48000, 2, SampleFormat::Float, false});
            g.destroyInstance(extra);
            g.destroyInstance(other);
        }
    });

    std::this_thread::sleep_for(std::chrono::milliseconds(1500));
    stop = true;
    for (auto& t : threads) t.join();

    EXPECT_FALSE(bad.load());
    EXPECT_GT(blocks.load(), 100);
    for (Instance* s : streams) {
        EXPECT_FALSE(s->duplicate());
        g.destroyInstance(s);
    }
}

}  // namespace
}  // namespace sp::fx
