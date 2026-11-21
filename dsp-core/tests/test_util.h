// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Test helpers. The analytic responses here are deliberately independent of the code under
// test: they evaluate the Audio EQ Cookbook's *analog prototypes* through the bilinear
// frequency mapping, instead of evaluating the digital coefficients.
#pragma once

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <random>
#include <vector>

#include "sp/dsp/math.h"
#include "sp/dsp/types.h"

namespace sp::dsp::test {

/// Analog prototypes H(s) from the Audio EQ Cookbook (s normalized to the centre frequency).
inline std::complex<double> analogPrototype(FilterType type, std::complex<double> s, double A,
                                            double Q) {
    const std::complex<double> s2 = s * s;
    switch (type) {
        case FilterType::LowPass: return 1.0 / (s2 + s / Q + 1.0);
        case FilterType::HighPass: return s2 / (s2 + s / Q + 1.0);
        case FilterType::BandPass: return (s / Q) / (s2 + s / Q + 1.0);
        case FilterType::Notch: return (s2 + 1.0) / (s2 + s / Q + 1.0);
        case FilterType::Peaking: return (s2 + s * (A / Q) + 1.0) / (s2 + s / (A * Q) + 1.0);
        case FilterType::LowShelf: {
            const double k = std::sqrt(A) / Q;
            return A * (s2 + k * s + A) / (A * s2 + k * s + 1.0);
        }
        case FilterType::HighShelf: {
            const double k = std::sqrt(A) / Q;
            return A * (A * s2 + k * s + 1.0) / (s2 + k * s + A);
        }
    }
    return 1.0;
}

/// |H| of the digital filter at `freqHz`, from the analog prototype via the bilinear transform:
/// the digital frequency w maps to the analog frequency tan(w/2) / tan(w0/2).
inline double analyticMagnitude(FilterType type, double fs, double f0, double gainDb, double q,
                                double freqHz) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double omega = std::tan(kPi * freqHz / fs) / std::tan(kPi * f0 / fs);
    return std::abs(analogPrototype(type, {0.0, omega}, A, q));
}

/// Runs `step(x)` on a unit impulse until the response has decayed (energy of the last 4096
/// samples below 1e-30 of the total) or `maxLength` samples.
template <typename Step>
std::vector<double> impulseResponse(Step&& step, std::size_t maxLength = std::size_t{1} << 22) {
    constexpr std::size_t kChunk = 4096;
    std::vector<double> h;
    h.reserve(kChunk * 4);
    double total = 0.0;
    double chunk = 0.0;
    for (std::size_t n = 0; n < maxLength; ++n) {
        const double y = step(n == 0 ? 1.0 : 0.0);
        h.push_back(y);
        total += y * y;
        chunk += y * y;
        if ((n + 1) % kChunk == 0) {
            if (n + 1 >= 2 * kChunk && chunk <= 1e-30 * total) break;
            chunk = 0.0;
        }
    }
    return h;
}

/// DFT of `h` at one frequency (phasor recomputed exactly every 1024 samples).
inline std::complex<double> dftAt(const std::vector<double>& h, double freqHz, double fs) {
    const double cyclesPerSample = freqHz / fs;
    const std::complex<double> rot = std::polar(1.0, -2.0 * kPi * cyclesPerSample);
    std::complex<double> phasor = 1.0;
    std::complex<double> acc = 0.0;
    for (std::size_t n = 0; n < h.size(); ++n) {
        acc += h[n] * phasor;
        if ((n & 1023u) == 1023u) {
            const double turns = std::fmod(cyclesPerSample * static_cast<double>(n + 1), 1.0);
            phasor = std::polar(1.0, -2.0 * kPi * turns);
        } else {
            phasor *= rot;
        }
    }
    return acc;
}

inline std::vector<double> logFrequencies(double lo, double hi, int count) {
    std::vector<double> f;
    for (int i = 0; i < count; ++i) {
        const double t = count == 1 ? 0.0 : static_cast<double>(i) / (count - 1);
        f.push_back(lo * std::pow(hi / lo, t));
    }
    return f;
}

/// Interleaved sine, `channels` identical channels.
inline std::vector<float> sine(double freqHz, double fs, double amplitude, std::size_t frames,
                               std::size_t channels = 1) {
    std::vector<float> s(frames * channels);
    for (std::size_t n = 0; n < frames; ++n) {
        const auto v = static_cast<float>(amplitude * std::sin(2.0 * kPi * freqHz * static_cast<double>(n) / fs));
        for (std::size_t c = 0; c < channels; ++c) s[n * channels + c] = v;
    }
    return s;
}

inline std::vector<float> whiteNoise(double amplitude, std::size_t samples, std::uint32_t seed) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<double> dist(-amplitude, amplitude);
    std::vector<float> s(samples);
    for (auto& v : s) v = static_cast<float>(dist(rng));
    return s;
}

/// Largest |y[n] - y[n-1]| of one channel of an interleaved buffer, from frame `start`.
inline double maxStep(const std::vector<float>& y, std::size_t channels, std::size_t channel,
                      std::size_t start = 1) {
    double m = 0.0;
    const std::size_t frames = y.size() / channels;
    for (std::size_t n = std::max<std::size_t>(start, 1); n < frames; ++n) {
        m = std::max(m, std::abs(static_cast<double>(y[n * channels + channel]) -
                                 static_cast<double>(y[(n - 1) * channels + channel])));
    }
    return m;
}

inline double maxAbs(const std::vector<float>& y) {
    double m = 0.0;
    for (float v : y) m = std::max(m, std::abs(static_cast<double>(v)));
    return m;
}

inline const char* typeName(FilterType t) {
    switch (t) {
        case FilterType::Peaking: return "Peaking";
        case FilterType::LowShelf: return "LowShelf";
        case FilterType::HighShelf: return "HighShelf";
        case FilterType::LowPass: return "LowPass";
        case FilterType::HighPass: return "HighPass";
        case FilterType::Notch: return "Notch";
        case FilterType::BandPass: return "BandPass";
    }
    return "Unknown";
}

inline constexpr FilterType kAllTypes[] = {
    FilterType::Peaking, FilterType::LowShelf, FilterType::HighShelf, FilterType::LowPass,
    FilterType::HighPass, FilterType::Notch,    FilterType::BandPass,
};

}  // namespace sp::dsp::test
