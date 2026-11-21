// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// sp-tone: plays a quiet sine wave through AAudio as a media stream, on the normal (mixed, not
// MMAP) output path, so that the music postprocess effects attach to its session. Prints the
// session ID first, then plays. Used by scripts/device-test.sh; runs as the adb shell user.
//
//   sp-tone [--seconds S] [--frequency HZ] [--level DBFS] [--rate HZ] [--format float|s16]
#include <aaudio/AAudio.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string_view>
#include <vector>

namespace {

struct Options {
    double seconds = 3.0;
    double frequency = 1000.0;
    double levelDb = -40.0;  // quiet: this plays on the device's speaker or headphones
    std::int32_t rate = 0;   // 0 = the device's native rate
    bool float32 = true;
};

int usage() {
    std::fputs("usage: sp-tone [--seconds S] [--frequency HZ] [--level DBFS] [--rate HZ] "
               "[--format float|s16]\n",
               stderr);
    return 1;
}

bool parse(int argc, char** argv, Options& o) {
    for (int i = 1; i < argc; ++i) {
        const std::string_view arg = argv[i];
        if (i + 1 >= argc) return false;
        const char* value = argv[++i];
        if (arg == "--seconds") {
            o.seconds = std::atof(value);
        } else if (arg == "--frequency") {
            o.frequency = std::atof(value);
        } else if (arg == "--level") {
            o.levelDb = std::atof(value);
        } else if (arg == "--rate") {
            o.rate = std::atoi(value);
        } else if (arg == "--format") {
            if (std::strcmp(value, "float") == 0) {
                o.float32 = true;
            } else if (std::strcmp(value, "s16") == 0) {
                o.float32 = false;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }
    return o.seconds > 0.0 && o.seconds <= 600.0 && o.frequency > 0.0 && o.levelDb <= 0.0 &&
           o.rate >= 0;
}

}  // namespace

int main(int argc, char** argv) {
    Options o;
    if (!parse(argc, argv, o)) return usage();

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK) {
        std::fprintf(stderr, "sp-tone: AAudio_createStreamBuilder: %s\n", AAudio_convertResultToText(r));
        return 1;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStreamBuilder_setSessionId(builder, AAUDIO_SESSION_ID_ALLOCATE);
    AAudioStreamBuilder_setFormat(builder, o.float32 ? AAUDIO_FORMAT_PCM_FLOAT : AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    if (o.rate > 0) AAudioStreamBuilder_setSampleRate(builder, o.rate);

    AAudioStream* stream = nullptr;
    r = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        std::fprintf(stderr, "sp-tone: AAudioStreamBuilder_openStream: %s\n", AAudio_convertResultToText(r));
        return 1;
    }

    const std::int32_t rate = AAudioStream_getSampleRate(stream);
    const std::int32_t channels = AAudioStream_getChannelCount(stream);
    const bool float32 = AAudioStream_getFormat(stream) == AAUDIO_FORMAT_PCM_FLOAT;
    std::printf("session %d rate %d channels %d format %s\n", AAudioStream_getSessionId(stream), rate,
                channels, float32 ? "float" : "s16");
    std::fflush(stdout);

    const double amplitude = std::pow(10.0, o.levelDb / 20.0);
    const double step = 2.0 * M_PI * o.frequency / rate;
    const std::int32_t chunk = rate / 100;  // 10 ms
    std::vector<float> f(static_cast<std::size_t>(chunk * channels));
    std::vector<std::int16_t> s(f.size());
    const auto total = static_cast<std::int64_t>(o.seconds * rate);
    double phase = 0.0;
    int status = 0;

    r = AAudioStream_requestStart(stream);
    if (r != AAUDIO_OK) {
        std::fprintf(stderr, "sp-tone: AAudioStream_requestStart: %s\n", AAudio_convertResultToText(r));
        status = 1;
    }
    for (std::int64_t done = 0; status == 0 && done < total; done += chunk) {
        for (std::int32_t i = 0; i < chunk; ++i) {
            const auto v = static_cast<float>(amplitude * std::sin(phase));
            phase = std::fmod(phase + step, 2.0 * M_PI);
            for (std::int32_t c = 0; c < channels; ++c) {
                const std::size_t k = static_cast<std::size_t>(i * channels + c);
                f[k] = v;
                s[k] = static_cast<std::int16_t>(std::lround(v * 32767.0f));
            }
        }
        const void* data = float32 ? static_cast<const void*>(f.data()) : static_cast<const void*>(s.data());
        r = AAudioStream_write(stream, data, chunk, 1'000'000'000LL);
        if (r < 0) {
            std::fprintf(stderr, "sp-tone: AAudioStream_write: %s\n", AAudio_convertResultToText(r));
            status = 1;
        }
    }
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    return status;
}
