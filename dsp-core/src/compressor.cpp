// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors

#include "sp/dsp/compressor.h"

#include <algorithm>
#include <cmath>

#include "sp/dsp/math.h"

namespace sp::dsp {
namespace {

// Detector floor: levels below this are treated as silence (-200 dBFS).
constexpr double kSilence = 1e-10;
constexpr double kSilenceDb = -200.0;

// Once off, the gain snaps to exactly 0 dB when it is this close.
constexpr double kIdleDb = 1e-6;

double coefFor(double ms, double sampleRate) noexcept {
    return std::exp(-1.0 / std::max(ms * sampleRate / 1000.0, 1.0));
}

}  // namespace

CompressorDesign designCompressor(const CompressorParams& params, double sampleRate) noexcept {
    CompressorDesign d;
    d.enabled = params.enabled;
    d.thresholdDb = params.thresholdDb;
    d.slope = 1.0 - 1.0 / std::max(params.ratio, 1.0);
    d.kneeDb = std::max(params.kneeDb, 0.0);
    d.makeupDb = params.makeupDb;
    d.attackCoef = coefFor(params.attackMs, sampleRate);
    d.releaseCoef = coefFor(params.releaseMs, sampleRate);
    return d;
}

double compressorReductionDb(const CompressorDesign& d, double levelDb) noexcept {
    const double over = levelDb - d.thresholdDb;
    const double halfKnee = 0.5 * d.kneeDb;
    if (over <= -halfKnee) return 0.0;
    if (over < halfKnee) {
        const double t = over + halfKnee;
        return d.slope * t * t / (2.0 * d.kneeDb);
    }
    return d.slope * over;
}

void Compressor::reset() noexcept { gainDb_ = design_.enabled ? design_.makeupDb : 0.0; }

void Compressor::process(double* const* channels, std::size_t numChannels,
                         std::size_t frames) noexcept {
    const std::size_t nch = std::min(numChannels, kMaxChannels);
    const CompressorDesign& d = design_;
    if (!d.enabled && gainDb_ == 0.0) return;

    for (std::size_t n = 0; n < frames; ++n) {
        double target = 0.0;
        if (d.enabled) {
            double peak = 0.0;
            for (std::size_t ch = 0; ch < nch; ++ch) peak = std::max(peak, std::abs(channels[ch][n]));
            const double levelDb = peak > kSilence ? 20.0 * std::log10(peak) : kSilenceDb;
            target = d.makeupDb - compressorReductionDb(d, levelDb);
        }
        const double coef = target < gainDb_ ? d.attackCoef : d.releaseCoef;
        gainDb_ = target + (gainDb_ - target) * coef;
        if (!d.enabled && std::abs(gainDb_) < kIdleDb) {
            gainDb_ = 0.0;
            return;  // the rest of the block passes through unchanged
        }
        const double g = dbToGain(gainDb_);
        for (std::size_t ch = 0; ch < nch; ++ch) channels[ch][n] *= g;
    }
}

}  // namespace sp::dsp
