// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/limiter.h"

#include <algorithm>
#include <cmath>

#include "sp/dsp/limits.h"
#include "sp/dsp/math.h"

namespace sp::dsp {

namespace {
// Relative tolerance before the safety clamp counts a sample as a guarantee violation.
// Exact arithmetic gives |y| <= ceiling; rounding may exceed it by a few ulps.
constexpr double kClampTolerance = 1e-9;
// The moving-average sum is recomputed exactly this often to stop rounding drift.
constexpr std::uint32_t kBoxRecomputeInterval = 1u << 16;
}  // namespace

std::uint32_t maxLookaheadSamples(double sampleRate) noexcept {
    return static_cast<std::uint32_t>(std::ceil(limits::kLookaheadMaxMs * sampleRate / 1000.0));
}

LimiterDesign designLimiter(const LimiterParams& params, double sampleRate) noexcept {
    LimiterDesign d;
    d.ceiling = dbToGain(params.ceilingDb);
    const double lookahead = std::round(params.lookaheadMs * sampleRate / 1000.0);
    d.lookahead = std::clamp(static_cast<std::uint32_t>(std::max(lookahead, 1.0)), 1u,
                             std::max(1u, maxLookaheadSamples(sampleRate)));
    const double releaseSamples = params.releaseMs * sampleRate / 1000.0;
    d.releaseCoef = std::exp(-1.0 / std::max(releaseSamples, 1.0));
    return d;
}

void Limiter::prepare(double sampleRate, std::size_t channels) {
    channels_ = std::min(channels, kMaxChannels);
    capacity_ = static_cast<std::size_t>(maxLookaheadSamples(sampleRate)) + 1;
    for (auto& d : delay_) d.assign(capacity_, 0.0);
    dequeValue_.assign(capacity_ + 1, 0.0);
    dequeIndex_.assign(capacity_ + 1, 0);
    box_.assign(capacity_, 1.0);
    design_ = designLimiter(LimiterParams{}, sampleRate);
    safetyClamps_ = 0;
    reset();
}

void Limiter::setDesign(const LimiterDesign& design) noexcept {
    LimiterDesign d = design;
    if (capacity_ > 1) {
        d.lookahead = std::clamp<std::uint32_t>(d.lookahead, 1u,
                                                static_cast<std::uint32_t>(capacity_ - 1));
    }
    const bool lookaheadChanged = d.lookahead != design_.lookahead;
    design_ = d;
    if (lookaheadChanged) reset();
}

void Limiter::reset() noexcept {
    for (auto& d : delay_) std::fill(d.begin(), d.end(), 0.0);
    delayPos_ = 0;
    dequeHead_ = 0;
    dequeSize_ = 0;
    release_ = 1.0;
    std::fill(box_.begin(), box_.end(), 1.0);
    boxPos_ = 0;
    boxSum_ = static_cast<double>(design_.lookahead);
    boxSinceRecompute_ = 0;
    frame_ = 0;
    minGain_ = 1.0;
}

void Limiter::recomputeBoxSum() noexcept {
    double sum = 0.0;
    for (std::uint32_t i = 0; i < design_.lookahead; ++i) sum += box_[i];
    boxSum_ = sum;
    boxSinceRecompute_ = 0;
}

double Limiter::takeMinGain() noexcept {
    const double g = minGain_;
    minGain_ = 1.0;
    return g;
}

void Limiter::process(double* const* channels, std::size_t numChannels,
                      std::size_t frames) noexcept {
    if (capacity_ == 0) return;  // not prepared
    const std::size_t nch = std::min({numChannels, channels_, kMaxChannels});
    const std::uint32_t lookahead = design_.lookahead;
    const std::uint64_t window = std::uint64_t{lookahead} + 1;  // sliding-minimum window
    const double ceiling = design_.ceiling;
    const double releaseCoef = design_.releaseCoef;
    const double invLookahead = 1.0 / static_cast<double>(lookahead);
    const double violation = ceiling * (1.0 + kClampTolerance);
    const std::size_t dequeCapacity = dequeValue_.size();

    for (std::size_t n = 0; n < frames; ++n) {
        // 1. Gain needed to bring this frame's (stereo-linked) peak down to the ceiling.
        double peak = 0.0;
        for (std::size_t ch = 0; ch < nch; ++ch) peak = std::max(peak, std::abs(channels[ch][n]));
        const double required = peak > ceiling ? ceiling / peak : 1.0;

        // 2. Minimum of `required` over the last `window` frames.
        while (dequeSize_ > 0) {
            const std::size_t back = (dequeHead_ + dequeSize_ - 1) % dequeCapacity;
            if (dequeValue_[back] < required) break;
            --dequeSize_;
        }
        const std::size_t slot = (dequeHead_ + dequeSize_) % dequeCapacity;
        dequeValue_[slot] = required;
        dequeIndex_[slot] = frame_;
        ++dequeSize_;
        while (dequeIndex_[dequeHead_] + window <= frame_) {
            dequeHead_ = (dequeHead_ + 1) % dequeCapacity;
            --dequeSize_;
        }
        const double held = dequeValue_[dequeHead_];

        // 3. Release: follow decreases instantly, recover exponentially. Never above `held`.
        release_ = held < release_ ? held : held + (release_ - held) * releaseCoef;

        // 4. Moving average over `lookahead` frames: smooth attack that completes in time.
        boxSum_ += release_ - box_[boxPos_];
        box_[boxPos_] = release_;
        if (++boxPos_ == lookahead) boxPos_ = 0;
        if (++boxSinceRecompute_ >= kBoxRecomputeInterval) recomputeBoxSum();
        const double gain = std::min(1.0, boxSum_ * invLookahead);
        minGain_ = std::min(minGain_, gain);

        // 5. Delay the audio by `lookahead` frames and apply the gain.
        const std::size_t readPos = (delayPos_ + capacity_ - lookahead) % capacity_;
        for (std::size_t ch = 0; ch < nch; ++ch) {
            const double delayed = delay_[ch][readPos];
            delay_[ch][delayPos_] = channels[ch][n];
            double y = delayed * gain;
            if (std::abs(y) > ceiling) {
                if (std::abs(y) > violation) ++safetyClamps_;
                y = std::copysign(ceiling, y);
            }
            channels[ch][n] = y;
        }
        delayPos_ = delayPos_ + 1 == capacity_ ? 0 : delayPos_ + 1;
        ++frame_;
    }
}

}  // namespace sp::dsp
