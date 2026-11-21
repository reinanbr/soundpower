// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <cstdint>

#if defined(__SSE__) || defined(__x86_64__) || defined(_M_X64)
#include <xmmintrin.h>
#define SP_DENORMAL_SSE 1
#endif

namespace sp::dsp {

/// Enables flush-to-zero (and denormals-are-zero where available) for the current thread
/// while in scope, and restores the previous mode afterwards. Recursive filters decaying
/// toward silence otherwise produce denormals, which are very slow on many CPUs.
class ScopedFlushDenormals {
public:
    ScopedFlushDenormals() noexcept {
#if defined(__aarch64__)
        std::uint64_t fpcr;
        __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
        saved_ = fpcr;
        fpcr |= (std::uint64_t{1} << 24);  // FZ
        __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__) && defined(__ARM_FP)
        std::uint32_t fpscr;
        __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
        saved_ = fpscr;
        fpscr |= (std::uint32_t{1} << 24);  // FZ
        __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr));
#elif defined(SP_DENORMAL_SSE)
        saved_ = _mm_getcsr();
        _mm_setcsr(static_cast<unsigned>(saved_) | 0x8040u);  // FTZ | DAZ
#endif
    }

    ~ScopedFlushDenormals() {
#if defined(__aarch64__)
        const std::uint64_t fpcr = saved_;
        __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__) && defined(__ARM_FP)
        const auto fpscr = static_cast<std::uint32_t>(saved_);
        __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr));
#elif defined(SP_DENORMAL_SSE)
        _mm_setcsr(static_cast<unsigned>(saved_));
#endif
    }

    ScopedFlushDenormals(const ScopedFlushDenormals&) = delete;
    ScopedFlushDenormals& operator=(const ScopedFlushDenormals&) = delete;

private:
    [[maybe_unused]] std::uint64_t saved_ = 0;
};

}  // namespace sp::dsp
