// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Asserts that Instance::process() never allocates, in every format and through enable,
// disable, duplicate hand-over, parameter changes and resets. Lives in its own executable
// because it replaces the global allocation functions.
#include <gtest/gtest.h>

#include <array>
#include <atomic>
#include <cstdlib>
#include <new>
#include <vector>

#include "codec.h"
#include "global_state.h"
#include "instance.h"
#include "soundpower/protocol.h"
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

namespace sp::fx {
namespace {

class CountScope {
public:
    CountScope() { g_counting = true; }
    ~CountScope() { g_counting = false; }
};

TEST(RealTime, InstanceProcessNeverAllocates) {
    g_allocations = 0;
    {
        CountScope scope;
        void* volatile probe = ::operator new(16);
        ::operator delete(probe);
    }
    ASSERT_EQ(g_allocations.load(), 1u);

    GlobalState g;
    Instance* primary = g.createInstance(1, 1);
    Instance* duplicate = g.createInstance(1, 1);
    dsp::ChainParams p;
    p.eq.count = SP_MAX_BANDS;
    for (std::size_t b = 0; b < SP_MAX_BANDS; ++b) {
        p.eq.bands[b] = {dsp::FilterType::Peaking, true, 30.0 * static_cast<double>(b + 1), 3.0, 1.0};
    }
    std::array<std::uint8_t, SP_MAX_MESSAGE_BYTES> payload{};

    const std::vector<float> noise = dsp::test::whiteNoise(0.7, 2 * 1024, 5);
    std::vector<float> fbuf = noise;
    std::vector<std::int16_t> sbuf(2 * 1024, 1234);

    g_allocations = 0;
    for (int iteration = 0; iteration < 240; ++iteration) {
        // Control-side events happen outside the counted region...
        const bool int16 = (iteration / 40) % 2 == 1;
        const bool accumulate = (iteration / 80) % 2 == 1;
        for (Instance* s : {primary, duplicate}) {
            ASSERT_EQ(s->configure({48000, 2, int16 ? SampleFormat::Int16 : SampleFormat::Float, accumulate}), 0);
        }
        switch (iteration % 6) {
            case 0: p.eq.bands[3].gainDb = -p.eq.bands[3].gainDb; break;
            case 1: p.preampDb = p.preampDb == 0.0 ? -4.0 : 0.0; break;
            case 2: p.bypass = !p.bypass; break;
            case 3: primary->setEnabled(iteration % 12 != 3); break;
            case 4: primary->reset(); break;
            default: duplicate->setEnabled(!duplicate->enabled()); break;
        }
        for (const std::uint32_t id : {std::uint32_t{SP_PARAM_EQ_STATE}, std::uint32_t{SP_PARAM_MASTER}}) {
            const int n = encodeParam(id, p, payload);
            ASSERT_EQ(g.setParam(id, std::span(payload.data(), static_cast<std::size_t>(n))), 0);
        }
        if (iteration == 200) {
            g.destroyInstance(primary);  // the duplicate takes over
            primary = g.createInstance(2, 1);
            ASSERT_EQ(primary->configure({48000, 2, int16 ? SampleFormat::Int16 : SampleFormat::Float, accumulate}), 0);
            primary->setEnabled(true);
        }

        // ...while everything the audio thread does is counted.
        CountScope scope;
        for (Instance* s : {primary, duplicate}) {
            if (int16) {
                (void)s->process(sbuf.data(), sbuf.data(), 1024);
            } else {
                fbuf = noise;  // vector copy into existing storage: no allocation
                (void)s->process(fbuf.data(), fbuf.data(), 1024);
            }
        }
    }
    EXPECT_EQ(g_allocations.load(), 0u);
    g.destroyInstance(primary);
    g.destroyInstance(duplicate);
}

}  // namespace
}  // namespace sp::fx
