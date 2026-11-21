// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/rbj.h"

#include <cmath>

#include "sp/dsp/math.h"

namespace sp::dsp {

namespace {

BiquadCoeffs normalize(double b0, double b1, double b2, double a0, double a1, double a2) noexcept {
    const double inv = 1.0 / a0;
    return BiquadCoeffs{b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv};
}

}  // namespace

bool usesGain(FilterType type) noexcept {
    return type == FilterType::Peaking || type == FilterType::LowShelf ||
           type == FilterType::HighShelf;
}

bool isIdentityBand(FilterType type, double gainDb) noexcept {
    return usesGain(type) && std::abs(gainDb) < 1e-6;
}

BiquadCoeffs designBiquad(FilterType type, double sampleRate, double freqHz, double gainDb,
                          double q) noexcept {
    const double w0 = 2.0 * kPi * freqHz / sampleRate;
    const double cosW0 = std::cos(w0);
    const double sinW0 = std::sin(w0);
    const double alpha = sinW0 / (2.0 * q);
    const double a = std::pow(10.0, gainDb / 40.0);

    switch (type) {
        case FilterType::LowPass:
            return normalize((1.0 - cosW0) / 2.0, 1.0 - cosW0, (1.0 - cosW0) / 2.0,  //
                             1.0 + alpha, -2.0 * cosW0, 1.0 - alpha);
        case FilterType::HighPass:
            return normalize((1.0 + cosW0) / 2.0, -(1.0 + cosW0), (1.0 + cosW0) / 2.0,  //
                             1.0 + alpha, -2.0 * cosW0, 1.0 - alpha);
        case FilterType::BandPass:  // constant 0 dB peak gain
            return normalize(alpha, 0.0, -alpha,  //
                             1.0 + alpha, -2.0 * cosW0, 1.0 - alpha);
        case FilterType::Notch:
            return normalize(1.0, -2.0 * cosW0, 1.0,  //
                             1.0 + alpha, -2.0 * cosW0, 1.0 - alpha);
        case FilterType::Peaking:
            return normalize(1.0 + alpha * a, -2.0 * cosW0, 1.0 - alpha * a,  //
                             1.0 + alpha / a, -2.0 * cosW0, 1.0 - alpha / a);
        case FilterType::LowShelf: {
            const double k = 2.0 * std::sqrt(a) * alpha;
            return normalize(a * ((a + 1.0) - (a - 1.0) * cosW0 + k),
                             2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0),
                             a * ((a + 1.0) - (a - 1.0) * cosW0 - k),
                             (a + 1.0) + (a - 1.0) * cosW0 + k,
                             -2.0 * ((a - 1.0) + (a + 1.0) * cosW0),
                             (a + 1.0) + (a - 1.0) * cosW0 - k);
        }
        case FilterType::HighShelf: {
            const double k = 2.0 * std::sqrt(a) * alpha;
            return normalize(a * ((a + 1.0) + (a - 1.0) * cosW0 + k),
                             -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0),
                             a * ((a + 1.0) + (a - 1.0) * cosW0 - k),
                             (a + 1.0) - (a - 1.0) * cosW0 + k,
                             2.0 * ((a - 1.0) - (a + 1.0) * cosW0),
                             (a + 1.0) - (a - 1.0) * cosW0 - k);
        }
    }
    return BiquadCoeffs{};  // unknown type: identity
}

}  // namespace sp::dsp
