// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Bounds-checked little-endian readers and writers over unaligned byte buffers.
#pragma once

#include <bit>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>

namespace sp::fx::wire {

// Android and every host we build on are little-endian, so memcpy is the wire encoding.
static_assert(std::endian::native == std::endian::little, "the protocol is little-endian");

class Reader {
public:
    explicit Reader(std::span<const std::uint8_t> bytes) noexcept : bytes_(bytes) {}

    template <typename T>
    T read() noexcept {
        T value{};
        if (ok_ && bytes_.size() - pos_ >= sizeof(T)) {
            std::memcpy(&value, bytes_.data() + pos_, sizeof(T));
            pos_ += sizeof(T);
        } else {
            ok_ = false;
        }
        return value;
    }

    /// Returns the next `n` bytes, or an empty span (and fails) if fewer remain.
    std::span<const std::uint8_t> bytes(std::size_t n) noexcept {
        if (!ok_ || bytes_.size() - pos_ < n) {
            ok_ = false;
            return {};
        }
        auto out = bytes_.subspan(pos_, n);
        pos_ += n;
        return out;
    }

    void skip(std::size_t n) noexcept { (void)bytes(n); }

    [[nodiscard]] bool ok() const noexcept { return ok_; }
    [[nodiscard]] std::size_t position() const noexcept { return pos_; }
    [[nodiscard]] std::size_t remaining() const noexcept { return ok_ ? bytes_.size() - pos_ : 0; }

private:
    std::span<const std::uint8_t> bytes_;
    std::size_t pos_ = 0;
    bool ok_ = true;
};

class Writer {
public:
    explicit Writer(std::span<std::uint8_t> bytes) noexcept : bytes_(bytes) {}

    template <typename T>
    void write(T value) noexcept {
        if (ok_ && bytes_.size() - pos_ >= sizeof(T)) {
            std::memcpy(bytes_.data() + pos_, &value, sizeof(T));
            pos_ += sizeof(T);
        } else {
            ok_ = false;
        }
    }

    [[nodiscard]] bool ok() const noexcept { return ok_; }
    [[nodiscard]] std::size_t size() const noexcept { return pos_; }

private:
    std::span<std::uint8_t> bytes_;
    std::size_t pos_ = 0;
    bool ok_ = true;
};

}  // namespace sp::fx::wire
