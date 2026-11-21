// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/biquad.h"

#include <cmath>

#include "sp/dsp/math.h"

namespace sp::dsp {

std::complex<double> response(const BiquadCoeffs& c, double freqHz, double sampleRate) noexcept {
    const double w = 2.0 * kPi * freqHz / sampleRate;
    const std::complex<double> z1 = std::polar(1.0, -w);  // z^-1
    const std::complex<double> z2 = z1 * z1;
    const std::complex<double> num = c.b0 + c.b1 * z1 + c.b2 * z2;
    const std::complex<double> den = 1.0 + c.a1 * z1 + c.a2 * z2;
    return num / den;
}

double magnitudeDb(const BiquadCoeffs& c, double freqHz, double sampleRate) noexcept {
    return gainToDb(std::abs(response(c, freqHz, sampleRate)));
}

bool isStable(const BiquadCoeffs& c) noexcept {
    return std::isfinite(c.a1) && std::isfinite(c.a2) && std::abs(c.a2) < 1.0 &&
           std::abs(c.a1) < 1.0 + c.a2;
}

}  // namespace sp::dsp
