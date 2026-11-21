// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <complex>

namespace sp::dsp {

/// Second-order section, normalized so that a0 == 1:
///   H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
struct BiquadCoeffs {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;

    friend bool operator==(const BiquadCoeffs&, const BiquadCoeffs&) = default;
};

/// Direct Form I state. Unlike transposed forms, the state is the section's actual input and
/// output history, which stays meaningful when the coefficients change.
struct Df1State {
    double x1 = 0.0;
    double x2 = 0.0;
    double y1 = 0.0;
    double y2 = 0.0;
};

[[nodiscard]] inline double processSample(const BiquadCoeffs& c, Df1State& s, double x) noexcept {
    const double y = c.b0 * x + c.b1 * s.x1 + c.b2 * s.x2 - c.a1 * s.y1 - c.a2 * s.y2;
    s.x2 = s.x1;
    s.x1 = x;
    s.y2 = s.y1;
    s.y1 = y;
    return y;
}

/// Complex frequency response H(e^jw) of the section at `freqHz`.
[[nodiscard]] std::complex<double> response(const BiquadCoeffs& c, double freqHz,
                                            double sampleRate) noexcept;

/// |H(e^jw)| in dB.
[[nodiscard]] double magnitudeDb(const BiquadCoeffs& c, double freqHz, double sampleRate) noexcept;

/// True if both poles are strictly inside the unit circle (stability triangle test).
[[nodiscard]] bool isStable(const BiquadCoeffs& c) noexcept;

}  // namespace sp::dsp
