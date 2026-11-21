// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors

#include "sp/dsp/stereo.h"

namespace sp::dsp {

StereoDesign designStereo(const StereoParams& params) noexcept {
    const double w = params.mono ? 0.0 : params.width;
    const double gl = params.balance > 0.0 ? 1.0 - params.balance : 1.0;
    const double gr = params.balance < 0.0 ? 1.0 + params.balance : 1.0;
    const double same = 0.5 * (1.0 + w);
    const double cross = 0.5 * (1.0 - w);
    return StereoDesign{gl * same, gl * cross, gr * cross, gr * same};
}

void StereoStage::setDesign(const StereoDesign& design, bool instant) noexcept {
    const std::array<double, 4> target = {design.ll, design.lr, design.rl, design.rr};
    for (std::size_t i = 0; i < 4; ++i) {
        if (instant) {
            m_[i].reset(target[i]);
        } else {
            m_[i].setTarget(target[i], rampFrames_);
        }
    }
    identity_ = design.identity();
}

void StereoStage::process(double* const* channels, std::size_t numChannels,
                          std::size_t frames) noexcept {
    if (numChannels < 2) return;
    const bool ramping = m_[0].ramping() || m_[1].ramping() || m_[2].ramping() || m_[3].ramping();
    if (!ramping && identity_) return;
    double* l = channels[0];
    double* r = channels[1];
    if (!ramping) {
        const double ll = m_[0].current(), lr = m_[1].current();
        const double rl = m_[2].current(), rr = m_[3].current();
        for (std::size_t n = 0; n < frames; ++n) {
            const double x = l[n], y = r[n];
            l[n] = ll * x + lr * y;
            r[n] = rl * x + rr * y;
        }
        return;
    }
    for (std::size_t n = 0; n < frames; ++n) {
        const double ll = m_[0].next(), lr = m_[1].next();
        const double rl = m_[2].next(), rr = m_[3].next();
        const double x = l[n], y = r[n];
        l[n] = ll * x + lr * y;
        r[n] = rl * x + rr * y;
    }
}

}  // namespace sp::dsp
