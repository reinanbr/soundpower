// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// sp-cli: offline front end for dsp-core. Processes WAV files through the same Processor the
// Android effect uses, prints EQ responses, generates test signals, and runs the benchmark.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <random>
#include <string>
#include <string_view>
#include <vector>

#include "bench.h"
#include "dr_wav.h"
#include "sp/dsp/format.h"
#include "sp/dsp/limits.h"
#include "sp/dsp/math.h"
#include "sp/dsp/parametric_eq.h"
#include "sp/dsp/processor.h"

namespace {

using namespace sp::dsp;

constexpr const char* kUsage = R"(usage:
  sp-cli process <in.wav> <out.wav> [chain options] [--format s16|f32]
  sp-cli response [chain options] [--fs HZ] [--points N]
  sp-cli gen <sine|sweep|noise|impulse> <out.wav> [--fs HZ] [--seconds S] [--freq HZ]
             [--amp A] [--channels 1|2] [--format s16|f32]
  sp-cli bench [--quick] [--fs HZ] [--channels 1|2]

chain options:
  --band TYPE:FREQ:GAIN:Q[:CH]
                            repeatable, up to 32 bands. TYPE is one of
                            peak, lowshelf, highshelf, lowpass, highpass, notch, bandpass
                            (GAIN is ignored by the last four). CH is the channel the band
                            filters: all (default), left, right, mid, side
  --preamp DB               preamp gain (default 0)
  --no-auto-preamp          do not subtract the EQ curve's peak from the preamp
  --ceiling DB              limiter ceiling (default -1)
  --lookahead MS            limiter lookahead (default 1.5)
  --release MS              limiter release (default 50)
  --bypass                  A/B bypass (outputs the input, latency-aligned)
  --no-limiter              turn the limiter off (its delay stays)
  --bass DB  --bass-freq HZ        bass shelf (default 0 dB at 100 Hz)
  --treble DB  --treble-freq HZ    treble shelf (default 0 dB at 8000 Hz)
  --balance B               -1 (left) .. 1 (right), default 0
  --width W                 stereo width: 0 mono, 1 unchanged (default), 2 wide
  --mono                    mono downmix
  --comp-threshold DB       turns the compressor on (default threshold -18)
  --comp-ratio R  --comp-attack MS  --comp-release MS  --comp-knee DB  --comp-makeup DB
                            compressor settings (defaults 3, 10, 150, 6, 0)

process writes the output time-aligned with the input (the limiter latency is removed) and
in the input's format unless --format is given (16-bit PCM input -> s16, otherwise f32).
)";

[[noreturn]] void fail(const std::string& message) {
    std::fprintf(stderr, "sp-cli: %s\n", message.c_str());
    std::exit(1);
}

std::optional<double> parseNumber(std::string_view s) {
    const std::string copy(s);
    char* end = nullptr;
    const double v = std::strtod(copy.c_str(), &end);
    if (end == copy.c_str() || *end != '\0' || !std::isfinite(v)) return std::nullopt;
    return v;
}

double numberArg(int& i, int argc, char** argv) {
    if (i + 1 >= argc) fail(std::string("missing value for ") + argv[i]);
    const auto v = parseNumber(argv[++i]);
    if (!v) fail(std::string("invalid number for ") + argv[i - 1] + ": " + argv[i]);
    return *v;
}

std::optional<FilterType> parseType(std::string_view s) {
    if (s == "peak" || s == "peaking" || s == "pk") return FilterType::Peaking;
    if (s == "lowshelf" || s == "ls") return FilterType::LowShelf;
    if (s == "highshelf" || s == "hs") return FilterType::HighShelf;
    if (s == "lowpass" || s == "lp") return FilterType::LowPass;
    if (s == "highpass" || s == "hp") return FilterType::HighPass;
    if (s == "notch") return FilterType::Notch;
    if (s == "bandpass" || s == "bp") return FilterType::BandPass;
    return std::nullopt;
}

std::optional<BandChannel> parseChannel(std::string_view s) {
    if (s == "all" || s == "lr") return BandChannel::All;
    if (s == "left" || s == "l") return BandChannel::Left;
    if (s == "right" || s == "r") return BandChannel::Right;
    if (s == "mid" || s == "m") return BandChannel::Mid;
    if (s == "side" || s == "s") return BandChannel::Side;
    return std::nullopt;
}

BandParams parseBand(std::string_view spec) {
    std::vector<std::string_view> parts;
    std::size_t start = 0;
    while (true) {
        const std::size_t colon = spec.find(':', start);
        parts.push_back(spec.substr(start, colon - start));
        if (colon == std::string_view::npos) break;
        start = colon + 1;
    }
    if (parts.size() != 4 && parts.size() != 5) {
        fail("--band expects TYPE:FREQ:GAIN:Q[:CHANNEL], got " + std::string(spec));
    }
    const auto type = parseType(parts[0]);
    const auto freq = parseNumber(parts[1]);
    const auto gain = parseNumber(parts[2]);
    const auto q = parseNumber(parts[3]);
    if (!type) fail("unknown band type: " + std::string(parts[0]));
    if (!freq || !gain || !q || *freq <= 0.0 || *q <= 0.0) {
        fail("invalid band values: " + std::string(spec));
    }
    BandParams b;
    b.type = *type;
    b.enabled = true;
    b.freqHz = *freq;
    b.gainDb = *gain;
    b.q = *q;
    if (parts.size() == 5) {
        const auto channel = parseChannel(parts[4]);
        if (!channel) fail("unknown band channel (all, left, right, mid, side): " + std::string(parts[4]));
        b.channel = *channel;
    }
    return b;
}

enum class OutFormat { Auto, S16, F32 };

struct Options {
    std::vector<std::string> positional;
    ChainParams chain;
    OutFormat format = OutFormat::Auto;
    double fs = 48000.0;
    int points = 128;
    double seconds = 5.0;
    double freq = 1000.0;
    double amp = 0.5;
    std::size_t channels = 2;
    bool quick = false;
};

Options parseOptions(int argc, char** argv, int first) {
    Options o;
    for (int i = first; i < argc; ++i) {
        const std::string_view a = argv[i];
        if (a == "--band") {
            if (i + 1 >= argc) fail("missing value for --band");
            if (o.chain.eq.count >= kMaxBands) fail("at most 32 bands");
            o.chain.eq.bands[o.chain.eq.count++] = parseBand(argv[++i]);
        } else if (a == "--preamp") {
            o.chain.preampDb = numberArg(i, argc, argv);
        } else if (a == "--no-auto-preamp") {
            o.chain.autoPreamp = false;
        } else if (a == "--ceiling") {
            o.chain.limiter.ceilingDb = numberArg(i, argc, argv);
        } else if (a == "--lookahead") {
            o.chain.limiter.lookaheadMs = numberArg(i, argc, argv);
        } else if (a == "--release") {
            o.chain.limiter.releaseMs = numberArg(i, argc, argv);
        } else if (a == "--bypass") {
            o.chain.bypass = true;
        } else if (a == "--no-limiter") {
            o.chain.limiter.enabled = false;
        } else if (a == "--bass") {
            o.chain.tone.bassDb = numberArg(i, argc, argv);
        } else if (a == "--bass-freq") {
            o.chain.tone.bassFreqHz = numberArg(i, argc, argv);
        } else if (a == "--treble") {
            o.chain.tone.trebleDb = numberArg(i, argc, argv);
        } else if (a == "--treble-freq") {
            o.chain.tone.trebleFreqHz = numberArg(i, argc, argv);
        } else if (a == "--balance") {
            o.chain.stereo.balance = numberArg(i, argc, argv);
        } else if (a == "--width") {
            o.chain.stereo.width = numberArg(i, argc, argv);
        } else if (a == "--mono") {
            o.chain.stereo.mono = true;
        } else if (a == "--comp-threshold") {
            o.chain.compressor.enabled = true;
            o.chain.compressor.thresholdDb = numberArg(i, argc, argv);
        } else if (a == "--comp-ratio") {
            o.chain.compressor.ratio = numberArg(i, argc, argv);
        } else if (a == "--comp-attack") {
            o.chain.compressor.attackMs = numberArg(i, argc, argv);
        } else if (a == "--comp-release") {
            o.chain.compressor.releaseMs = numberArg(i, argc, argv);
        } else if (a == "--comp-knee") {
            o.chain.compressor.kneeDb = numberArg(i, argc, argv);
        } else if (a == "--comp-makeup") {
            o.chain.compressor.makeupDb = numberArg(i, argc, argv);
        } else if (a == "--format") {
            if (i + 1 >= argc) fail("missing value for --format");
            const std::string_view f = argv[++i];
            if (f == "s16") {
                o.format = OutFormat::S16;
            } else if (f == "f32") {
                o.format = OutFormat::F32;
            } else {
                fail("--format must be s16 or f32");
            }
        } else if (a == "--fs") {
            o.fs = numberArg(i, argc, argv);
        } else if (a == "--points") {
            o.points = static_cast<int>(numberArg(i, argc, argv));
        } else if (a == "--seconds") {
            o.seconds = numberArg(i, argc, argv);
        } else if (a == "--freq") {
            o.freq = numberArg(i, argc, argv);
        } else if (a == "--amp") {
            o.amp = numberArg(i, argc, argv);
        } else if (a == "--channels") {
            o.channels = static_cast<std::size_t>(numberArg(i, argc, argv));
        } else if (a == "--quick") {
            o.quick = true;
        } else if (a.starts_with("--")) {
            fail("unknown option " + std::string(a));
        } else {
            o.positional.emplace_back(a);
        }
    }
    return o;
}

void writeWav(const std::string& path, const std::vector<float>& interleaved,
              std::size_t channels, double fs, bool s16) {
    drwav_data_format fmt{};
    fmt.container = drwav_container_riff;
    fmt.format = s16 ? DR_WAVE_FORMAT_PCM : DR_WAVE_FORMAT_IEEE_FLOAT;
    fmt.channels = static_cast<drwav_uint32>(channels);
    fmt.sampleRate = static_cast<drwav_uint32>(std::lround(fs));
    fmt.bitsPerSample = s16 ? 16 : 32;

    drwav wav;
    if (!drwav_init_file_write(&wav, path.c_str(), &fmt, nullptr)) fail("cannot write " + path);
    const auto frames = static_cast<drwav_uint64>(interleaved.size() / channels);
    drwav_uint64 written = 0;
    if (s16) {
        std::vector<std::int16_t> pcm(interleaved.size());
        TpdfDither dither;
        floatToInt16(interleaved.data(), pcm.data(), pcm.size(), dither);
        written = drwav_write_pcm_frames(&wav, frames, pcm.data());
    } else {
        written = drwav_write_pcm_frames(&wav, frames, interleaved.data());
    }
    drwav_uninit(&wav);
    if (written != frames) fail("short write to " + path);
}

double peakDb(const std::vector<float>& x) {
    float peak = 0.0f;
    for (float v : x) peak = std::max(peak, std::abs(v));
    return gainToDb(static_cast<double>(peak));
}

int cmdProcess(const Options& o) {
    if (o.positional.size() != 2) fail("process needs <in.wav> <out.wav>");
    const std::string& inPath = o.positional[0];
    const std::string& outPath = o.positional[1];

    drwav wav;
    if (!drwav_init_file(&wav, inPath.c_str(), nullptr)) fail("cannot read " + inPath);
    const std::size_t channels = wav.channels;
    const double fs = wav.sampleRate;
    const bool inputIsS16 = wav.translatedFormatTag == DR_WAVE_FORMAT_PCM && wav.bitsPerSample == 16;
    if (channels < 1 || channels > kMaxChannels) {
        drwav_uninit(&wav);
        fail("only mono and stereo files are supported");
    }
    std::vector<float> audio(static_cast<std::size_t>(wav.totalPCMFrameCount) * channels);
    const drwav_uint64 read = drwav_read_pcm_frames_f32(&wav, wav.totalPCMFrameCount, audio.data());
    drwav_uninit(&wav);
    audio.resize(static_cast<std::size_t>(read) * channels);

    Processor proc;
    proc.setParams(o.chain);
    if (!proc.prepare(fs, channels)) fail("unsupported sample rate");
    const std::size_t latency = proc.latencyFrames();
    const std::size_t frames = audio.size() / channels;

    // Append `latency` frames of silence, process, then drop the first `latency` frames.
    std::vector<float> buf(audio);
    buf.resize((frames + latency) * channels, 0.0f);
    float worstGr = 0.0f;
    constexpr std::size_t kChunk = 1024;
    for (std::size_t off = 0; off < frames + latency; off += kChunk) {
        const std::size_t n = std::min(kChunk, frames + latency - off);
        proc.process(buf.data() + off * channels, buf.data() + off * channels, n);
        worstGr = std::min(worstGr, proc.limiterGainReductionDb());
    }
    std::vector<float> out(buf.begin() + static_cast<std::ptrdiff_t>(latency * channels), buf.end());

    const bool s16 = o.format == OutFormat::S16 || (o.format == OutFormat::Auto && inputIsS16);
    writeWav(outPath, out, channels, fs, s16);

    std::printf("in:  %s  (%zu ch, %.0f Hz, %zu frames, peak %.2f dBFS)\n", inPath.c_str(),
                channels, fs, frames, peakDb(audio));
    std::printf("out: %s  (%s, peak %.2f dBFS)\n", outPath.c_str(), s16 ? "s16" : "f32",
                peakDb(out));
    std::printf("bands %zu, effective preamp %.2f dB, limiter latency %zu frames, max gain "
                "reduction %.2f dB\n",
                o.chain.eq.count, proc.effectivePreampDb(), latency,
                static_cast<double>(worstGr));
    if (proc.takeNonFiniteFlag()) std::printf("warning: non-finite samples were replaced by silence\n");
    return 0;
}

int cmdResponse(const Options& o) {
    if (!o.positional.empty()) fail("response takes no positional arguments");
    if (o.fs < 8000.0) fail("--fs must be at least 8000");
    const ChainParams p = sanitize(o.chain, o.fs);
    const EqDesign design = designEq(p.eq, p.tone, o.fs);
    double preamp = p.preampDb;
    if (p.autoPreamp) preamp -= std::max(0.0, eqPeakGainDb(design, o.fs));

    const int points = std::max(o.points, 2);
    const double hi = std::min(20000.0, limits::kFreqMaxFsRatio * o.fs);
    std::printf("# fs=%.0f bands=%zu effective_preamp_db=%.4f\n", o.fs, p.eq.count, preamp);
    // eq_db covers the bands on every channel; the last four columns add each channel's own bands.
    std::printf("freq_hz,eq_db,total_db,left_db,right_db,mid_db,side_db\n");
    for (int i = 0; i < points; ++i) {
        const double f = 20.0 * std::pow(hi / 20.0, static_cast<double>(i) / (points - 1));
        const double eq = eqMagnitudeDb(design, f, o.fs);
        std::printf("%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n", f, eq, eq + preamp,
                    eqMagnitudeDb(design, f, o.fs, BandChannel::Left),
                    eqMagnitudeDb(design, f, o.fs, BandChannel::Right),
                    eqMagnitudeDb(design, f, o.fs, BandChannel::Mid),
                    eqMagnitudeDb(design, f, o.fs, BandChannel::Side));
    }
    return 0;
}

int cmdGen(const Options& o) {
    if (o.positional.size() != 2) fail("gen needs <kind> <out.wav>");
    const std::string& kind = o.positional[0];
    if (o.channels < 1 || o.channels > 2) fail("--channels must be 1 or 2");
    if (o.fs < 8000.0 || o.seconds <= 0.0) fail("invalid --fs or --seconds");
    const auto frames = static_cast<std::size_t>(o.seconds * o.fs);
    std::vector<float> x(frames * o.channels, 0.0f);
    std::mt19937 rng(1);
    std::uniform_real_distribution<double> u(-1.0, 1.0);
    const double f1 = 20.0, f2 = std::min(20000.0, 0.45 * o.fs);
    for (std::size_t n = 0; n < frames; ++n) {
        const double t = static_cast<double>(n) / o.fs;
        double v = 0.0;
        if (kind == "sine") {
            v = std::sin(2.0 * kPi * o.freq * t);
        } else if (kind == "sweep") {  // exponential sweep f1 -> f2
            const double k = std::log(f2 / f1);
            v = std::sin(2.0 * kPi * f1 * o.seconds / k * (std::exp(t / o.seconds * k) - 1.0));
        } else if (kind == "noise") {
            v = u(rng);
        } else if (kind == "impulse") {
            v = n == 0 ? 1.0 : 0.0;
        } else {
            fail("unknown signal kind: " + kind);
        }
        for (std::size_t c = 0; c < o.channels; ++c) x[n * o.channels + c] = static_cast<float>(o.amp * v);
    }
    writeWav(o.positional[1], x, o.channels, o.fs, o.format == OutFormat::S16);
    std::printf("wrote %s (%s, %zu ch, %.0f Hz, %.2f s)\n", o.positional[1].c_str(), kind.c_str(),
                o.channels, o.fs, o.seconds);
    return 0;
}

int cmdBench(const Options& o) {
    sp::bench::Options b;
    b.sampleRate = o.fs;
    b.channels = o.channels;
    b.seconds = o.quick ? 0.5 : 5.0;
    if (b.channels < 1 || b.channels > 2) fail("--channels must be 1 or 2");
    sp::bench::print(stdout, b, sp::bench::run(b));
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2 || std::strcmp(argv[1], "--help") == 0 || std::strcmp(argv[1], "-h") == 0) {
        std::fputs(kUsage, argc < 2 ? stderr : stdout);
        return argc < 2 ? 2 : 0;
    }
    const std::string_view cmd = argv[1];
    const Options o = parseOptions(argc, argv, 2);
    if (cmd == "process") return cmdProcess(o);
    if (cmd == "response") return cmdResponse(o);
    if (cmd == "gen") return cmdGen(o);
    if (cmd == "bench") return cmdBench(o);
    std::fprintf(stderr, "sp-cli: unknown command '%s'\n\n%s", argv[1], kUsage);
    return 2;
}
