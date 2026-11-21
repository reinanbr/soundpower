// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <atomic>
#include <cstdint>
#include <type_traits>

namespace sp::dsp {

/// Wait-free "latest value" exchange between exactly one writer thread and one reader thread.
///
/// The writer publishes complete values; the reader always gets the most recent complete value
/// and never blocks the writer (and vice versa). Intermediate values may be skipped, which is
/// what parameter updates want. Neither side allocates.
template <typename T>
class TripleBuffer {
    static_assert(std::is_trivially_copyable_v<T>, "T is copied with plain assignment");

public:
    TripleBuffer() = default;
    explicit TripleBuffer(const T& initial) : buffers_{initial, initial, initial} {}

    TripleBuffer(const TripleBuffer&) = delete;
    TripleBuffer& operator=(const TripleBuffer&) = delete;

    /// Writer side.
    void write(const T& value) noexcept {
        buffers_[back_] = value;
        const std::uint8_t previous =
            middle_.exchange(static_cast<std::uint8_t>(back_ | kDirtyBit), std::memory_order_acq_rel);
        back_ = static_cast<std::uint8_t>(previous & kIndexMask);
    }

    /// Reader side. Returns the newest value if one was published since the last call, or
    /// nullptr. The pointer stays valid until the next consume() call.
    [[nodiscard]] const T* consume() noexcept {
        if ((middle_.load(std::memory_order_relaxed) & kDirtyBit) == 0) return nullptr;
        const std::uint8_t previous = middle_.exchange(front_, std::memory_order_acq_rel);
        front_ = static_cast<std::uint8_t>(previous & kIndexMask);
        return &buffers_[front_];
    }

    /// Reader side: the value returned by the last successful consume() (or the initial value).
    [[nodiscard]] const T& current() const noexcept { return buffers_[front_]; }

private:
    static constexpr std::uint8_t kIndexMask = 0x3;
    static constexpr std::uint8_t kDirtyBit = 0x4;
    static_assert(std::atomic<std::uint8_t>::is_always_lock_free);

    T buffers_[3]{};
    std::uint8_t front_ = 0;              // owned by the reader
    std::uint8_t back_ = 1;               // owned by the writer
    std::atomic<std::uint8_t> middle_{2}; // shared; index + dirty bit
};

}  // namespace sp::dsp
