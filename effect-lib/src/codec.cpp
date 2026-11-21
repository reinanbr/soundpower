// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "codec.h"

#include <algorithm>
#include <cerrno>

#include "soundpower/protocol.h"
#include "sp/dsp/limits.h"
#include "wire.h"

namespace sp::fx {

// dsp-core keeps its own copy of the protocol's limits (it has no dependency on protocol.h).
// The two must never drift apart.
namespace lim = dsp::limits;
static_assert(dsp::kMaxBands == SP_MAX_BANDS);
static_assert(dsp::kFilterTypeCount == SP_BAND_TYPE_COUNT);
static_assert(static_cast<int>(dsp::FilterType::Peaking) == SP_BAND_PEAKING);
static_assert(static_cast<int>(dsp::FilterType::LowShelf) == SP_BAND_LOW_SHELF);
static_assert(static_cast<int>(dsp::FilterType::HighShelf) == SP_BAND_HIGH_SHELF);
static_assert(static_cast<int>(dsp::FilterType::LowPass) == SP_BAND_LOW_PASS);
static_assert(static_cast<int>(dsp::FilterType::HighPass) == SP_BAND_HIGH_PASS);
static_assert(static_cast<int>(dsp::FilterType::Notch) == SP_BAND_NOTCH);
static_assert(static_cast<int>(dsp::FilterType::BandPass) == SP_BAND_BAND_PASS);
static_assert(dsp::kBandChannelCount == SP_BAND_CHANNEL_COUNT);
static_assert(static_cast<int>(dsp::BandChannel::All) == SP_BAND_CHANNEL_ALL);
static_assert(static_cast<int>(dsp::BandChannel::Left) == SP_BAND_CHANNEL_LEFT);
static_assert(static_cast<int>(dsp::BandChannel::Right) == SP_BAND_CHANNEL_RIGHT);
static_assert(static_cast<int>(dsp::BandChannel::Mid) == SP_BAND_CHANNEL_MID);
static_assert(static_cast<int>(dsp::BandChannel::Side) == SP_BAND_CHANNEL_SIDE);
static_assert(static_cast<float>(lim::kFreqMinHz) == SP_FREQ_MIN_HZ);
static_assert(static_cast<float>(lim::kFreqMaxFsRatio) == SP_FREQ_MAX_FS_RATIO);
static_assert(static_cast<float>(lim::kGainMinDb) == SP_GAIN_MIN_DB);
static_assert(static_cast<float>(lim::kGainMaxDb) == SP_GAIN_MAX_DB);
static_assert(static_cast<float>(lim::kQMin) == SP_Q_MIN);
static_assert(static_cast<float>(lim::kQMax) == SP_Q_MAX);
static_assert(static_cast<float>(lim::kPreampMinDb) == SP_PREAMP_MIN_DB);
static_assert(static_cast<float>(lim::kPreampMaxDb) == SP_PREAMP_MAX_DB);
static_assert(static_cast<float>(lim::kCeilingMinDb) == SP_LIMITER_CEILING_MIN_DB);
static_assert(static_cast<float>(lim::kCeilingMaxDb) == SP_LIMITER_CEILING_MAX_DB);
static_assert(static_cast<float>(lim::kLookaheadMinMs) == SP_LIMITER_LOOKAHEAD_MIN_MS);
static_assert(static_cast<float>(lim::kLookaheadMaxMs) == SP_LIMITER_LOOKAHEAD_MAX_MS);
static_assert(static_cast<float>(lim::kReleaseMinMs) == SP_LIMITER_RELEASE_MIN_MS);
static_assert(static_cast<float>(lim::kReleaseMaxMs) == SP_LIMITER_RELEASE_MAX_MS);
static_assert(static_cast<float>(dsp::LimiterParams{}.ceilingDb) == SP_LIMITER_CEILING_DEFAULT_DB);
static_assert(static_cast<float>(dsp::LimiterParams{}.lookaheadMs) ==
              SP_LIMITER_LOOKAHEAD_DEFAULT_MS);
static_assert(static_cast<float>(dsp::LimiterParams{}.releaseMs) == SP_LIMITER_RELEASE_DEFAULT_MS);
static_assert(static_cast<float>(dsp::ToneParams{}.bassFreqHz) == SP_TONE_BASS_FREQ_DEFAULT_HZ);
static_assert(static_cast<float>(dsp::ToneParams{}.trebleFreqHz) == SP_TONE_TREBLE_FREQ_DEFAULT_HZ);
static_assert(dsp::ToneParams{}.enabled == ((SP_TONE_FLAGS_DEFAULT & SP_TONE_FLAG_ENABLED) != 0));
static_assert(static_cast<float>(dsp::kToneShelfQ) == 0.70710678f);
static_assert(static_cast<float>(lim::kBalanceMin) == SP_BALANCE_MIN);
static_assert(static_cast<float>(lim::kBalanceMax) == SP_BALANCE_MAX);
static_assert(static_cast<float>(lim::kWidthMin) == SP_WIDTH_MIN);
static_assert(static_cast<float>(lim::kWidthMax) == SP_WIDTH_MAX);
static_assert(static_cast<float>(lim::kCompThresholdMinDb) == SP_COMP_THRESHOLD_MIN_DB);
static_assert(static_cast<float>(lim::kCompThresholdMaxDb) == SP_COMP_THRESHOLD_MAX_DB);
static_assert(static_cast<float>(lim::kCompRatioMin) == SP_COMP_RATIO_MIN);
static_assert(static_cast<float>(lim::kCompRatioMax) == SP_COMP_RATIO_MAX);
static_assert(static_cast<float>(lim::kCompAttackMinMs) == SP_COMP_ATTACK_MIN_MS);
static_assert(static_cast<float>(lim::kCompAttackMaxMs) == SP_COMP_ATTACK_MAX_MS);
static_assert(static_cast<float>(lim::kCompReleaseMinMs) == SP_COMP_RELEASE_MIN_MS);
static_assert(static_cast<float>(lim::kCompReleaseMaxMs) == SP_COMP_RELEASE_MAX_MS);
static_assert(static_cast<float>(lim::kCompKneeMinDb) == SP_COMP_KNEE_MIN_DB);
static_assert(static_cast<float>(lim::kCompKneeMaxDb) == SP_COMP_KNEE_MAX_DB);
static_assert(static_cast<float>(lim::kCompMakeupMinDb) == SP_COMP_MAKEUP_MIN_DB);
static_assert(static_cast<float>(lim::kCompMakeupMaxDb) == SP_COMP_MAKEUP_MAX_DB);
static_assert(static_cast<float>(dsp::CompressorParams{}.thresholdDb) == SP_COMP_THRESHOLD_DEFAULT_DB);
static_assert(static_cast<float>(dsp::CompressorParams{}.ratio) == SP_COMP_RATIO_DEFAULT);
static_assert(static_cast<float>(dsp::CompressorParams{}.attackMs) == SP_COMP_ATTACK_DEFAULT_MS);
static_assert(static_cast<float>(dsp::CompressorParams{}.releaseMs) == SP_COMP_RELEASE_DEFAULT_MS);
static_assert(static_cast<float>(dsp::CompressorParams{}.kneeDb) == SP_COMP_KNEE_DEFAULT_DB);
static_assert(static_cast<float>(dsp::CompressorParams{}.makeupDb) == SP_COMP_MAKEUP_DEFAULT_DB);
static_assert(!dsp::CompressorParams{}.enabled);

namespace {

int decodeMaster(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto flags = r.read<std::uint32_t>();
    const auto preampDb = r.read<float>();
    if (!r.ok()) return -EINVAL;
    // Unknown flag bits are ignored: a newer minor version may define them.
    p.bypass = (flags & SP_MASTER_FLAG_BYPASS) != 0;
    p.autoPreamp = (flags & SP_MASTER_FLAG_AUTO_PREAMP) != 0;
    p.limiter.enabled = (flags & SP_MASTER_FLAG_LIMITER_OFF) == 0;
    p.preampDb = static_cast<double>(preampDb);
    return 0;
}

int decodeEqState(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto count = r.read<std::uint32_t>();
    if (!r.ok() || count > SP_MAX_BANDS) return -EINVAL;
    dsp::EqParams eq;  // bands past `count` keep their (disabled) defaults
    eq.count = count;
    for (std::uint32_t i = 0; i < count; ++i) {
        const auto type = r.read<std::uint8_t>();
        const auto enabled = r.read<std::uint8_t>();
        const auto channel = r.read<std::uint8_t>();
        r.skip(1);  // reserved
        const auto freq = r.read<float>();
        const auto gain = r.read<float>();
        const auto q = r.read<float>();
        if (!r.ok() || type >= SP_BAND_TYPE_COUNT || channel >= SP_BAND_CHANNEL_COUNT) return -EINVAL;
        dsp::BandParams& b = eq.bands[i];
        b.type = static_cast<dsp::FilterType>(type);
        b.enabled = enabled != 0;
        b.channel = static_cast<dsp::BandChannel>(channel);
        b.freqHz = static_cast<double>(freq);
        b.gainDb = static_cast<double>(gain);
        b.q = static_cast<double>(q);
    }
    p.eq = eq;
    return 0;
}

int decodeLimiter(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto ceiling = r.read<float>();
    const auto lookahead = r.read<float>();
    const auto release = r.read<float>();
    if (!r.ok()) return -EINVAL;
    p.limiter.ceilingDb = static_cast<double>(ceiling);
    p.limiter.lookaheadMs = static_cast<double>(lookahead);
    p.limiter.releaseMs = static_cast<double>(release);
    return 0;
}

int decodeTone(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto flags = r.read<std::uint32_t>();
    const auto bassDb = r.read<float>();
    const auto bassFreq = r.read<float>();
    const auto trebleDb = r.read<float>();
    const auto trebleFreq = r.read<float>();
    if (!r.ok()) return -EINVAL;
    p.tone.enabled = (flags & SP_TONE_FLAG_ENABLED) != 0;
    p.tone.bassDb = static_cast<double>(bassDb);
    p.tone.bassFreqHz = static_cast<double>(bassFreq);
    p.tone.trebleDb = static_cast<double>(trebleDb);
    p.tone.trebleFreqHz = static_cast<double>(trebleFreq);
    return 0;
}

int decodeStereo(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto flags = r.read<std::uint32_t>();
    const auto balance = r.read<float>();
    const auto width = r.read<float>();
    if (!r.ok()) return -EINVAL;
    p.stereo.mono = (flags & SP_STEREO_FLAG_MONO) != 0;
    p.stereo.balance = static_cast<double>(balance);
    p.stereo.width = static_cast<double>(width);
    return 0;
}

int decodeCompressor(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto flags = r.read<std::uint32_t>();
    const auto threshold = r.read<float>();
    const auto ratio = r.read<float>();
    const auto attack = r.read<float>();
    const auto release = r.read<float>();
    const auto knee = r.read<float>();
    const auto makeup = r.read<float>();
    if (!r.ok()) return -EINVAL;
    dsp::CompressorParams& c = p.compressor;
    c.enabled = (flags & SP_COMPRESSOR_FLAG_ENABLED) != 0;
    c.thresholdDb = static_cast<double>(threshold);
    c.ratio = static_cast<double>(ratio);
    c.attackMs = static_cast<double>(attack);
    c.releaseMs = static_cast<double>(release);
    c.kneeDb = static_cast<double>(knee);
    c.makeupDb = static_cast<double>(makeup);
    return 0;
}

int finish(const wire::Writer& w) noexcept {
    return w.ok() ? static_cast<int>(w.size()) : -EINVAL;
}

}  // namespace

bool isSettable(std::uint32_t id) noexcept {
    switch (id) {
        case SP_PARAM_MASTER:
        case SP_PARAM_EQ_STATE:
        case SP_PARAM_LIMITER:
        case SP_PARAM_TONE:
        case SP_PARAM_STEREO:
        case SP_PARAM_COMPRESSOR: return true;
        default: return false;
    }
}

int decodeParam(std::uint32_t id, std::span<const std::uint8_t> value,
                dsp::ChainParams& params) noexcept {
    wire::Reader r(value);
    dsp::ChainParams next = params;
    int status = -EINVAL;
    switch (id) {
        case SP_PARAM_MASTER: status = decodeMaster(r, next); break;
        case SP_PARAM_EQ_STATE: status = decodeEqState(r, next); break;
        case SP_PARAM_LIMITER: status = decodeLimiter(r, next); break;
        case SP_PARAM_TONE: status = decodeTone(r, next); break;
        case SP_PARAM_STEREO: status = decodeStereo(r, next); break;
        case SP_PARAM_COMPRESSOR: status = decodeCompressor(r, next); break;
        default: break;
    }
    if (status == 0) params = next;
    return status;
}

int encodeParam(std::uint32_t id, const dsp::ChainParams& p, std::span<std::uint8_t> out) noexcept {
    wire::Writer w(out);
    switch (id) {
        case SP_PARAM_MASTER: {
            std::uint32_t flags = 0;
            if (p.bypass) flags |= SP_MASTER_FLAG_BYPASS;
            if (p.autoPreamp) flags |= SP_MASTER_FLAG_AUTO_PREAMP;
            if (!p.limiter.enabled) flags |= SP_MASTER_FLAG_LIMITER_OFF;
            w.write(flags);
            w.write(static_cast<float>(p.preampDb));
            return finish(w);
        }
        case SP_PARAM_EQ_STATE: {
            const auto count = static_cast<std::uint32_t>(std::min(p.eq.count, dsp::kMaxBands));
            w.write(count);
            for (std::uint32_t i = 0; i < count; ++i) {
                const dsp::BandParams& b = p.eq.bands[i];
                w.write(static_cast<std::uint8_t>(b.type));
                w.write(static_cast<std::uint8_t>(b.enabled ? 1 : 0));
                w.write(static_cast<std::uint8_t>(b.channel));
                w.write(std::uint8_t{0});
                w.write(static_cast<float>(b.freqHz));
                w.write(static_cast<float>(b.gainDb));
                w.write(static_cast<float>(b.q));
            }
            return finish(w);
        }
        case SP_PARAM_LIMITER:
            w.write(static_cast<float>(p.limiter.ceilingDb));
            w.write(static_cast<float>(p.limiter.lookaheadMs));
            w.write(static_cast<float>(p.limiter.releaseMs));
            return finish(w);
        case SP_PARAM_TONE:
            w.write(std::uint32_t{p.tone.enabled ? SP_TONE_FLAG_ENABLED : 0u});
            w.write(static_cast<float>(p.tone.bassDb));
            w.write(static_cast<float>(p.tone.bassFreqHz));
            w.write(static_cast<float>(p.tone.trebleDb));
            w.write(static_cast<float>(p.tone.trebleFreqHz));
            return finish(w);
        case SP_PARAM_STEREO:
            w.write(std::uint32_t{p.stereo.mono ? SP_STEREO_FLAG_MONO : 0u});
            w.write(static_cast<float>(p.stereo.balance));
            w.write(static_cast<float>(p.stereo.width));
            return finish(w);
        case SP_PARAM_COMPRESSOR: {
            const dsp::CompressorParams& c = p.compressor;
            w.write(std::uint32_t{c.enabled ? SP_COMPRESSOR_FLAG_ENABLED : 0u});
            w.write(static_cast<float>(c.thresholdDb));
            w.write(static_cast<float>(c.ratio));
            w.write(static_cast<float>(c.attackMs));
            w.write(static_cast<float>(c.releaseMs));
            w.write(static_cast<float>(c.kneeDb));
            w.write(static_cast<float>(c.makeupDb));
            return finish(w);
        }
        default:
            return -EINVAL;
    }
}

std::uint32_t packedLibraryVersion() noexcept {
    return (std::uint32_t{SP_VERSION_MAJOR} << 16) | (std::uint32_t{SP_VERSION_MINOR} << 8) |
           std::uint32_t{SP_VERSION_PATCH};
}

int encodeProtocolInfo(std::span<std::uint8_t> out) noexcept {
    wire::Writer w(out);
    w.write(std::uint16_t{SP_PROTOCOL_VERSION_MAJOR});
    w.write(std::uint16_t{SP_PROTOCOL_VERSION_MINOR});
    w.write(packedLibraryVersion());
    w.write(std::uint32_t{SP_MAX_BANDS});
    w.write(std::uint32_t{SP_CAP_PARAMETRIC_EQ | SP_CAP_LIMITER | SP_CAP_TONE | SP_CAP_STEREO |
                          SP_CAP_COMPRESSOR | SP_CAP_LIMITER_SWITCH | SP_CAP_BAND_CHANNEL});
    return finish(w);
}

int encodeStatus(const Status& s, std::span<std::uint8_t> out) noexcept {
    wire::Writer w(out);
    w.write(s.sampleRate);
    w.write(s.channels);
    w.write(s.format);
    w.write(s.state);
    w.write(s.cpuAvgPercent);
    w.write(s.cpuPeakPercent);
    w.write(s.instances);
    w.write(s.sessionId);
    w.write(s.flags);
    w.write(s.limiterGrDb);
    w.write(s.framesProcessed);
    return finish(w);
}

}  // namespace sp::fx
