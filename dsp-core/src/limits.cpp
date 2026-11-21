// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "sp/dsp/limits.h"

#include <algorithm>
#include <cmath>

namespace sp::dsp {

namespace {

double finiteOr(double value, double fallback) noexcept {
    return std::isfinite(value) ? value : fallback;
}

double clampField(double value, double fallback, double lo, double hi) noexcept {
    return std::clamp(finiteOr(value, fallback), lo, hi);
}

}  // namespace

ChainParams sanitize(const ChainParams& params, double sampleRate) noexcept {
    using namespace limits;
    static const ChainParams kDefaults{};
    static const BandParams kDefaultBand{};

    const double fs = (std::isfinite(sampleRate) && sampleRate > 0.0) ? sampleRate : 48000.0;
    const double freqMax = std::max(kFreqMinHz, kFreqMaxFsRatio * fs);

    ChainParams out = params;
    out.eq.count = std::min(params.eq.count, kMaxBands);
    for (std::size_t i = 0; i < kMaxBands; ++i) {
        BandParams& b = out.eq.bands[i];
        if (static_cast<std::size_t>(b.type) >= kFilterTypeCount) b.type = FilterType::Peaking;
        if (static_cast<std::size_t>(b.channel) >= kBandChannelCount) b.channel = BandChannel::All;
        b.freqHz = clampField(b.freqHz, kDefaultBand.freqHz, kFreqMinHz, freqMax);
        b.gainDb = clampField(b.gainDb, 0.0, kGainMinDb, kGainMaxDb);
        b.q = clampField(b.q, kDefaultBand.q, kQMin, kQMax);
        if (i >= out.eq.count) b.enabled = false;
    }

    out.preampDb = clampField(params.preampDb, kDefaults.preampDb, kPreampMinDb, kPreampMaxDb);

    const ToneParams& t = kDefaults.tone;
    out.tone.bassDb = clampField(params.tone.bassDb, 0.0, kGainMinDb, kGainMaxDb);
    out.tone.bassFreqHz = clampField(params.tone.bassFreqHz, t.bassFreqHz, kFreqMinHz, freqMax);
    out.tone.trebleDb = clampField(params.tone.trebleDb, 0.0, kGainMinDb, kGainMaxDb);
    out.tone.trebleFreqHz =
        clampField(params.tone.trebleFreqHz, t.trebleFreqHz, kFreqMinHz, freqMax);

    out.stereo.balance = clampField(params.stereo.balance, 0.0, kBalanceMin, kBalanceMax);
    out.stereo.width = clampField(params.stereo.width, 1.0, kWidthMin, kWidthMax);

    const CompressorParams& c = kDefaults.compressor;
    CompressorParams& oc = out.compressor;
    oc.thresholdDb = clampField(params.compressor.thresholdDb, c.thresholdDb, kCompThresholdMinDb,
                                kCompThresholdMaxDb);
    oc.ratio = clampField(params.compressor.ratio, c.ratio, kCompRatioMin, kCompRatioMax);
    oc.attackMs =
        clampField(params.compressor.attackMs, c.attackMs, kCompAttackMinMs, kCompAttackMaxMs);
    oc.releaseMs = clampField(params.compressor.releaseMs, c.releaseMs, kCompReleaseMinMs,
                              kCompReleaseMaxMs);
    oc.kneeDb = clampField(params.compressor.kneeDb, c.kneeDb, kCompKneeMinDb, kCompKneeMaxDb);
    oc.makeupDb =
        clampField(params.compressor.makeupDb, c.makeupDb, kCompMakeupMinDb, kCompMakeupMaxDb);

    const LimiterParams& d = kDefaults.limiter;
    out.limiter.ceilingDb =
        clampField(params.limiter.ceilingDb, d.ceilingDb, kCeilingMinDb, kCeilingMaxDb);
    out.limiter.lookaheadMs =
        clampField(params.limiter.lookaheadMs, d.lookaheadMs, kLookaheadMinMs, kLookaheadMaxMs);
    out.limiter.releaseMs =
        clampField(params.limiter.releaseMs, d.releaseMs, kReleaseMinMs, kReleaseMaxMs);
    return out;
}

}  // namespace sp::dsp
