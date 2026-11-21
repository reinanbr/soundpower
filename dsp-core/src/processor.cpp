// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/processor.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "sp/dsp/denormal.h"
#include "sp/dsp/limits.h"
#include "sp/dsp/math.h"

namespace sp::dsp {

namespace {

constexpr double kMinSampleRate = 8000.0;
constexpr double kMaxSampleRate = 384000.0;

constexpr double kPreampRampMs = 10.0;
constexpr double kBypassRampMs = 20.0;
constexpr double kEqCrossfadeMs = 10.0;
constexpr double kFadeMs = 2.0;

std::uint32_t msToFrames(double ms, double sampleRate) {
    return static_cast<std::uint32_t>(std::max(1.0, std::round(ms * sampleRate / 1000.0)));
}

}  // namespace

Processor::Processor() = default;

bool Processor::prepare(double sampleRate, std::size_t channels) {
    prepared_ = false;
    if (!(sampleRate >= kMinSampleRate && sampleRate <= kMaxSampleRate) || channels == 0 ||
        channels > kMaxChannels) {
        sampleRate_ = 0.0;
        channels_ = 0;
        return false;
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    preampRampFrames_ = msToFrames(kPreampRampMs, sampleRate);
    bypassRampFrames_ = msToFrames(kBypassRampMs, sampleRate);
    eqCrossfadeFrames_ = msToFrames(kEqCrossfadeMs, sampleRate);
    fadeFrames_ = msToFrames(kFadeMs, sampleRate);

    limiter_.prepare(sampleRate, channels);
    dryCapacity_ = static_cast<std::size_t>(maxLookaheadSamples(sampleRate)) + 1;
    for (auto& d : dryDelay_) d.assign(dryCapacity_, 0.0);

    // Drop anything published for the previous format; we are the only thread touching
    // `pending_` right now, per the contract.
    (void)pending_.consume();
    resetRequested_.store(false, std::memory_order_relaxed);

    applyParams(design(requested_), /*instant=*/true);
    prepared_ = true;
    return true;
}

void Processor::setParams(const ChainParams& params) {
    requested_ = params;
    if (sampleRate_ <= 0.0) return;  // designed in prepare()
    pending_.write(design(params));
}

RtParams Processor::design(const ChainParams& params) {
    const ChainParams p = sanitize(params, sampleRate_);
    RtParams r;
    r.eq = designEq(p.eq, sampleRate_);
    r.eqSerial = (r.eq == lastDesign_.eq) ? lastDesign_.eqSerial : ++eqSerialCounter_;

    double preampDb = p.preampDb;
    if (p.autoPreamp) preampDb -= std::max(0.0, eqPeakGainDb(r.eq, p.eq, sampleRate_));
    effectivePreampDb_ = preampDb;
    r.preampGain = dbToGain(preampDb);

    r.limiter = designLimiter(p.limiter, sampleRate_);
    r.bypass = p.bypass;
    lastDesign_ = r;
    return r;
}

void Processor::setDryDelay(std::uint32_t frames) noexcept {
    for (auto& d : dryDelay_) std::fill(d.begin(), d.end(), 0.0);
    dryPos_ = 0;
    dryDelayFrames_ = std::min<std::uint32_t>(frames, static_cast<std::uint32_t>(dryCapacity_ - 1));
}

void Processor::resetAudioState() noexcept {
    for (auto& bank : banks_) bank.reset();
    eqTransition_ = false;
    limiter_.reset();
    setDryDelay(limiter_.latency());
    fade_.reset(1.0);
    fadeState_ = Fade::None;
    fadeHoldFrames_ = 0;
}

void Processor::applyParams(const RtParams& params, bool instant) noexcept {
    if (instant) {
        if (&params != &current_) current_ = params;
        banks_[activeBank_].setDesign(current_.eq);
        preamp_.reset(current_.preampGain);
        wet_.reset(current_.bypass ? 0.0 : 1.0);
        limiter_.setDesign(current_.limiter);
        resetAudioState();
        latency_.store(limiter_.latency(), std::memory_order_relaxed);
        return;
    }

    wet_.setTarget(params.bypass ? 0.0 : 1.0, bypassRampFrames_);

    if (params.eqSerial != current_.eqSerial) {
        // The preamp change rides on the EQ crossfade instead of ramping on its own.
        transitionPreampFrom_ = preamp_.current();
        preamp_.reset(params.preampGain);
        ParametricEq& next = banks_[1 - activeBank_];
        next.setDesign(params.eq);
        next.takeHistoryFrom(banks_[activeBank_]);
        eqCrossfade_.reset(0.0);
        eqCrossfade_.setTarget(1.0, eqCrossfadeFrames_);
        eqTransition_ = true;
    } else {
        preamp_.setTarget(params.preampGain, preampRampFrames_);
    }

    if (params.limiter.lookahead != limiter_.design().lookahead) {
        // Latency changes: fade out, swap the delay lines while silent, fade back in.
        pendingLimiter_ = params.limiter;
        fadeState_ = Fade::Out;
        fade_.setTarget(0.0, fadeFrames_);
    } else {
        limiter_.setDesign(params.limiter);
    }

    current_ = params;
}

void Processor::process(const float* in, float* out, std::size_t frames) noexcept {
    if (!prepared_) return;
    ScopedFlushDenormals ftz;
    std::size_t done = 0;
    while (done < frames) {
        const std::size_t n = std::min(kMaxBlockFrames, frames - done);
        processBlock(in + done * channels_, out + done * channels_, n);
        done += n;
    }
}

void Processor::processBlock(const float* in, float* out, std::size_t frames) noexcept {
    const std::size_t nch = channels_;

    // ---- Parameter updates ------------------------------------------------------------------
    if (resetRequested_.exchange(false, std::memory_order_acq_rel)) {
        if (const RtParams* p = pending_.consume()) current_ = *p;
        applyParams(current_, /*instant=*/true);
    } else if (!eqTransition_ && fadeState_ == Fade::None) {
        if (const RtParams* p = pending_.consume()) applyParams(*p, /*instant=*/false);
    }

    // ---- Deinterleave; feed the latency-aligned dry path -------------------------------------
    for (std::size_t f = 0; f < frames; ++f) {
        const std::size_t readPos = (dryPos_ + dryCapacity_ - dryDelayFrames_) % dryCapacity_;
        for (std::size_t ch = 0; ch < nch; ++ch) {
            const double x = static_cast<double>(in[f * nch + ch]);
            wetA_[ch][f] = x;
            dryDelay_[ch][dryPos_] = x;
            dry_[ch][f] = dryDelay_[ch][readPos];
        }
        dryPos_ = dryPos_ + 1 == dryCapacity_ ? 0 : dryPos_ + 1;
    }

    // ---- Preamp + parametric EQ --------------------------------------------------------------
    double* wet[kMaxChannels] = {wetA_[0].data(), wetA_[1].data()};
    if (eqTransition_) {
        // Crossfade (old preamp · old curve) into (new preamp · new curve).
        const double gainOld = transitionPreampFrom_;
        const double gainNew = preamp_.current();
        double* next[kMaxChannels] = {wetB_[0].data(), wetB_[1].data()};
        for (std::size_t ch = 0; ch < nch; ++ch) {
            for (std::size_t f = 0; f < frames; ++f) {
                const double x = wetA_[ch][f];
                wetA_[ch][f] = x * gainOld;
                wetB_[ch][f] = x * gainNew;
            }
        }
        banks_[activeBank_].process(wet, nch, frames);
        banks_[1 - activeBank_].process(next, nch, frames);
        for (std::size_t f = 0; f < frames; ++f) {
            const double t = eqCrossfade_.next();
            for (std::size_t ch = 0; ch < nch; ++ch) {
                wetA_[ch][f] += t * (wetB_[ch][f] - wetA_[ch][f]);
            }
        }
        if (!eqCrossfade_.ramping()) {
            activeBank_ = 1 - activeBank_;
            eqTransition_ = false;
        }
    } else {
        if (preamp_.ramping()) {
            for (std::size_t f = 0; f < frames; ++f) {
                const double g = preamp_.next();
                for (std::size_t ch = 0; ch < nch; ++ch) wetA_[ch][f] *= g;
            }
        } else if (const double g = preamp_.current(); g != 1.0) {
            for (std::size_t ch = 0; ch < nch; ++ch) {
                for (std::size_t f = 0; f < frames; ++f) wetA_[ch][f] *= g;
            }
        }
        banks_[activeBank_].process(wet, nch, frames);
    }

    // ---- Non-finite guard: never let NaN/Inf reach the limiter or the filter state ---------
    bool finite = true;
    for (std::size_t ch = 0; ch < nch && finite; ++ch) {
        for (std::size_t f = 0; f < frames; ++f) {
            if (!std::isfinite(wetA_[ch][f])) {
                finite = false;
                break;
            }
        }
    }
    if (!finite) {
        if (eqTransition_) {
            activeBank_ = 1 - activeBank_;
            eqTransition_ = false;
        }
        for (auto& bank : banks_) bank.reset();
        for (std::size_t ch = 0; ch < nch; ++ch) std::fill_n(wetA_[ch].data(), frames, 0.0);
        nonFinite_.store(true, std::memory_order_relaxed);
    }

    // ---- Limiter (always on) ---------------------------------------------------------------
    limiter_.process(wet, nch, frames);

    // ---- Bypass crossfade, structural fade, interleave -------------------------------------
    for (std::size_t f = 0; f < frames; ++f) {
        const double w = wet_.next();
        double fade = 1.0;
        if (fadeState_ != Fade::None) {
            if (fadeState_ == Fade::Hold) {
                if (fadeHoldFrames_ > 0) {
                    --fadeHoldFrames_;
                    fade = 0.0;
                } else {
                    fadeState_ = Fade::In;
                    fade_.setTarget(1.0, fadeFrames_);
                    fade = fade_.next();
                }
            } else {
                fade = fade_.next();
                if (fadeState_ == Fade::In && !fade_.ramping()) fadeState_ = Fade::None;
            }
        }
        for (std::size_t ch = 0; ch < nch; ++ch) {
            // Exact endpoints: fully wet must not round above the limiter ceiling, and fully
            // dry must be bit-identical to the input.
            const double d = dry_[ch][f];
            const double s = wetA_[ch][f];
            double y = w == 1.0 ? s : (w == 0.0 ? d : d + w * (s - d));
            y *= fade;
            out[f * nch + ch] = std::isfinite(y) ? static_cast<float>(y) : 0.0f;
        }
    }

    if (fadeState_ == Fade::Out && !fade_.ramping()) {
        // Silent now: swap the delay lines, then stay silent until they have refilled.
        limiter_.setDesign(pendingLimiter_);  // resets the limiter's delay line
        setDryDelay(limiter_.latency());
        latency_.store(limiter_.latency(), std::memory_order_relaxed);
        fadeState_ = Fade::Hold;
        fadeHoldFrames_ = limiter_.latency();
    }

    limiterGrDb_.store(static_cast<float>(gainToDb(limiter_.takeMinGain())),
                       std::memory_order_relaxed);
}

}  // namespace sp::dsp
