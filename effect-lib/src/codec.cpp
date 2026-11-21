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

namespace {

int decodeMaster(wire::Reader& r, dsp::ChainParams& p) noexcept {
    const auto flags = r.read<std::uint32_t>();
    const auto preampDb = r.read<float>();
    if (!r.ok()) return -EINVAL;
    // Unknown flag bits are ignored: a newer minor version may define them.
    p.bypass = (flags & SP_MASTER_FLAG_BYPASS) != 0;
    p.autoPreamp = (flags & SP_MASTER_FLAG_AUTO_PREAMP) != 0;
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
        r.skip(2);  // reserved
        const auto freq = r.read<float>();
        const auto gain = r.read<float>();
        const auto q = r.read<float>();
        if (!r.ok() || type >= SP_BAND_TYPE_COUNT) return -EINVAL;
        dsp::BandParams& b = eq.bands[i];
        b.type = static_cast<dsp::FilterType>(type);
        b.enabled = enabled != 0;
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

int finish(const wire::Writer& w) noexcept {
    return w.ok() ? static_cast<int>(w.size()) : -EINVAL;
}

}  // namespace

bool isSettable(std::uint32_t id) noexcept {
    return id == SP_PARAM_MASTER || id == SP_PARAM_EQ_STATE || id == SP_PARAM_LIMITER;
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
                w.write(std::uint16_t{0});
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
    w.write(std::uint32_t{SP_CAP_PARAMETRIC_EQ | SP_CAP_LIMITER});
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
