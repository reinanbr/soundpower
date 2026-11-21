// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/format.h"

#include <algorithm>
#include <cmath>

namespace sp::dsp {

void int16ToFloat(const std::int16_t* in, float* out, std::size_t samples) noexcept {
    constexpr float kScale = 1.0f / 32768.0f;
    for (std::size_t i = 0; i < samples; ++i) out[i] = static_cast<float>(in[i]) * kScale;
}

void floatToInt16(const float* in, std::int16_t* out, std::size_t samples,
                  TpdfDither& dither) noexcept {
    for (std::size_t i = 0; i < samples; ++i) {
        const float x = in[i];
        if (!std::isfinite(x)) {
            out[i] = 0;
            continue;
        }
        const float scaled = std::clamp(x * 32768.0f + dither.next(), -32768.0f, 32767.0f);
        out[i] = static_cast<std::int16_t>(std::lrint(scaled));
    }
}

void sanitizeFloat(float* samples, std::size_t count) noexcept {
    for (std::size_t i = 0; i < count; ++i) {
        const float x = samples[i];
        samples[i] = std::isfinite(x) ? std::clamp(x, -1.0f, 1.0f) : 0.0f;
    }
}

}  // namespace sp::dsp
