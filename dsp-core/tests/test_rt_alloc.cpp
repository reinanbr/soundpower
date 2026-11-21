// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Asserts that Processor::process() never allocates, under every kind of event it handles:
// EQ changes, preamp ramps, bypass, lookahead changes, resets and non-finite input.
// Lives in its own executable because it replaces the global allocation functions.
#include <gtest/gtest.h>

#include <atomic>
#include <cstdlib>
#include <limits>
#include <new>
#include <vector>

#include "sp/dsp/processor.h"
#include "test_util.h"

namespace {
thread_local bool g_counting = false;
std::atomic<std::size_t> g_allocations{0};

void* countedAllocOrNull(std::size_t size, std::size_t alignment) noexcept {
    if (g_counting) g_allocations.fetch_add(1, std::memory_order_relaxed);
    if (size == 0) size = 1;
    return alignment > alignof(std::max_align_t)
               ? std::aligned_alloc(alignment, (size + alignment - 1) / alignment * alignment)
               : std::malloc(size);
}

void* countedAlloc(std::size_t size, std::size_t alignment) {
    void* p = countedAllocOrNull(size, alignment);
    if (p == nullptr) throw std::bad_alloc();
    return p;
}
}  // namespace

// Every replaceable allocation function is replaced (including nothrow forms), so that all
// memory is malloc-backed and every delete matches.
void* operator new(std::size_t size) { return countedAlloc(size, 0); }
void* operator new[](std::size_t size) { return countedAlloc(size, 0); }
void* operator new(std::size_t size, std::align_val_t a) {
    return countedAlloc(size, static_cast<std::size_t>(a));
}
void* operator new[](std::size_t size, std::align_val_t a) {
    return countedAlloc(size, static_cast<std::size_t>(a));
}
void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
    return countedAllocOrNull(size, 0);
}
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept {
    return countedAllocOrNull(size, 0);
}
void* operator new(std::size_t size, std::align_val_t a, const std::nothrow_t&) noexcept {
    return countedAllocOrNull(size, static_cast<std::size_t>(a));
}
void* operator new[](std::size_t size, std::align_val_t a, const std::nothrow_t&) noexcept {
    return countedAllocOrNull(size, static_cast<std::size_t>(a));
}
void operator delete(void* p, const std::nothrow_t&) noexcept { std::free(p); }
void operator delete[](void* p, const std::nothrow_t&) noexcept { std::free(p); }
void operator delete(void* p, std::align_val_t, const std::nothrow_t&) noexcept { std::free(p); }
void operator delete[](void* p, std::align_val_t, const std::nothrow_t&) noexcept { std::free(p); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }
void operator delete(void* p, std::align_val_t) noexcept { std::free(p); }
void operator delete[](void* p, std::align_val_t) noexcept { std::free(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { std::free(p); }

namespace sp::dsp {
namespace {

class CountScope {
public:
    CountScope() { g_counting = true; }
    ~CountScope() { g_counting = false; }
};

TEST(RealTime, ProcessNeverAllocates) {
    // Sanity check that the counter works at all. A direct operator new call through a
    // volatile sink, because compilers may elide a plain new/delete pair.
    g_allocations = 0;
    {
        CountScope scope;
        void* volatile probe = ::operator new(16);
        ::operator delete(probe);
    }
    ASSERT_EQ(g_allocations.load(), 1u);

    Processor p;
    ChainParams params;
    params.eq.count = kMaxBands;
    for (std::size_t i = 0; i < kMaxBands; ++i) {
        params.eq.bands[i].enabled = true;
        params.eq.bands[i].freqHz = 30.0 * static_cast<double>(i + 1);
        params.eq.bands[i].gainDb = 3.0;
    }
    p.setParams(params);
    ASSERT_TRUE(p.prepare(48000.0, 2));

    std::vector<float> buf = test::whiteNoise(1.0, 2 * 1024, 77);
    g_allocations = 0;
    for (int iteration = 0; iteration < 200; ++iteration) {
        // Control-side events happen outside the counted region...
        switch (iteration % 6) {
            case 0: params.eq.bands[3].gainDb = -params.eq.bands[3].gainDb; break;
            case 1: params.preampDb = params.preampDb == 0.0 ? -4.0 : 0.0; break;
            case 2: params.bypass = !params.bypass; break;
            case 3: params.limiter.lookaheadMs = params.limiter.lookaheadMs == 1.5 ? 3.0 : 1.5; break;
            case 4: p.requestReset(); break;
            default: buf[17] = std::numeric_limits<float>::quiet_NaN(); break;
        }
        p.setParams(params);
        // ...while everything the audio thread does is counted.
        CountScope scope;
        p.process(buf.data(), buf.data(), buf.size() / 2);
        (void)p.limiterGainReductionDb();
        (void)p.takeNonFiniteFlag();
    }
    EXPECT_EQ(g_allocations.load(), 0u);
}

}  // namespace
}  // namespace sp::dsp
