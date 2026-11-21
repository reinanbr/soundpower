// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Sample format conversion. All functions are real-time safe.
#pragma once

#include <cstddef>
#include <cstdint>

namespace sp::dsp {

/// Triangular-PDF dither source (sum of two uniform variables, ±1 LSB peak), xorshift-based.
class TpdfDither {
public:
    explicit TpdfDither(std::uint32_t seed = 0x9e3779b9u) noexcept : state_(seed ? seed : 1u) {}

    /// Next dither value in LSB units, in (-1, 1).
    float next() noexcept {
        return uniform() - uniform();
    }

private:
    float uniform() noexcept {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return static_cast<float>(state_ >> 8) * (1.0f / 16777216.0f);  // [0, 1)
    }

    std::uint32_t state_;
};

/// int16 → float in [-1, 1).
void int16ToFloat(const std::int16_t* in, float* out, std::size_t samples) noexcept;

/// float → int16 with TPDF dither and clipping. Non-finite input becomes 0.
void floatToInt16(const float* in, std::int16_t* out, std::size_t samples,
                  TpdfDither& dither) noexcept;

/// Clamps float samples to [-1, 1] (and non-finite samples to 0) in place.
void sanitizeFloat(float* samples, std::size_t count) noexcept;

}  // namespace sp::dsp
