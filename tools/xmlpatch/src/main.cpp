// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// sp-xmlpatch: command-line front end of effects_xml, run by the Magisk module installer.
//
//   sp-xmlpatch patch [--stream TYPE]... [--no-postprocess] INPUT OUTPUT
//   sp-xmlpatch remove INPUT OUTPUT
//   sp-xmlpatch check INPUT
//
// Exit codes: 0 success, 1 usage error, 2 INPUT is not an audio effects configuration (or is not
// well-formed), 3 I/O error.
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "effects_xml.h"

namespace {

constexpr int kExitUsage = 1;
constexpr int kExitNotConfig = 2;
constexpr int kExitIo = 3;
constexpr long kMaxInputBytes = 4L << 20;

int usage() {
    std::fputs(
        "usage: sp-xmlpatch patch [--stream TYPE]... [--no-postprocess] INPUT OUTPUT\n"
        "       sp-xmlpatch remove INPUT OUTPUT\n"
        "       sp-xmlpatch check INPUT\n"
        "\n"
        "patch   declare the SoundPower library and effect in an audio effects XML and apply the\n"
        "        effect to output streams (default: music). Previous SoundPower entries are\n"
        "        replaced, so patching is idempotent.\n"
        "remove  remove every SoundPower entry.\n"
        "check   print what SoundPower entries INPUT has, as key=value lines.\n",
        stderr);
    return kExitUsage;
}

bool readFile(const char* path, std::string& out) {
    std::FILE* f = std::fopen(path, "rb");
    if (f == nullptr) {
        std::fprintf(stderr, "sp-xmlpatch: cannot open %s: %s\n", path, std::strerror(errno));
        return false;
    }
    out.clear();
    char buf[16384];
    std::size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) {
        out.append(buf, n);
        if (static_cast<long>(out.size()) > kMaxInputBytes) {
            std::fprintf(stderr, "sp-xmlpatch: %s is larger than %ld bytes\n", path, kMaxInputBytes);
            std::fclose(f);
            return false;
        }
    }
    const bool ok = std::ferror(f) == 0;
    std::fclose(f);
    if (!ok) std::fprintf(stderr, "sp-xmlpatch: cannot read %s\n", path);
    return ok;
}

/// Writes to OUTPUT.tmp, then renames it over OUTPUT.
bool writeFile(const char* path, const std::string& data) {
    const std::string tmp = std::string(path) + ".tmp";
    std::FILE* f = std::fopen(tmp.c_str(), "wb");
    if (f == nullptr) {
        std::fprintf(stderr, "sp-xmlpatch: cannot create %s: %s\n", tmp.c_str(), std::strerror(errno));
        return false;
    }
    const bool written = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    const bool closed = std::fclose(f) == 0;
    if (!written || !closed || std::rename(tmp.c_str(), path) != 0) {
        std::fprintf(stderr, "sp-xmlpatch: cannot write %s: %s\n", path, std::strerror(errno));
        std::remove(tmp.c_str());
        return false;
    }
    return true;
}

int finish(const sp::xmlpatch::Result& r, const char* input, const char* output) {
    if (r.error != sp::xmlpatch::Error::None) {
        std::fprintf(stderr, "sp-xmlpatch: %s: %s (%s)\n", input, sp::xmlpatch::toString(r.error),
                     r.message.c_str());
        return r.error == sp::xmlpatch::Error::BadStreamType ? kExitUsage : kExitNotConfig;
    }
    return writeFile(output, r.xml) ? 0 : kExitIo;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) return usage();
    const std::string_view command = argv[1];
    std::vector<const char*> files;
    sp::xmlpatch::PatchOptions options;
    bool streamsGiven = false;
    bool noPostprocess = false;

    for (int i = 2; i < argc; ++i) {
        const std::string_view arg = argv[i];
        if (command == "patch" && arg == "--stream" && i + 1 < argc) {
            if (!streamsGiven) options.streams.clear();
            streamsGiven = true;
            options.streams.emplace_back(argv[++i]);
        } else if (command == "patch" && arg == "--no-postprocess") {
            noPostprocess = true;
        } else if (arg.size() > 1 && arg[0] == '-') {
            std::fprintf(stderr, "sp-xmlpatch: unknown option %s\n", argv[i]);
            return usage();
        } else {
            files.push_back(argv[i]);
        }
    }
    if (noPostprocess) {
        if (streamsGiven) {
            std::fputs("sp-xmlpatch: --stream and --no-postprocess are exclusive\n", stderr);
            return kExitUsage;
        }
        options.streams.clear();
    }

    std::string input;
    if (command == "patch" || command == "remove") {
        if (files.size() != 2) return usage();
        if (!readFile(files[0], input)) return kExitIo;
        const sp::xmlpatch::Result r = command == "patch" ? sp::xmlpatch::patch(input, options)
                                                          : sp::xmlpatch::remove(input);
        return finish(r, files[0], files[1]);
    }
    if (command == "check") {
        if (files.size() != 1) return usage();
        if (!readFile(files[0], input)) return kExitIo;
        const sp::xmlpatch::Summary s = sp::xmlpatch::inspect(input);
        if (s.error != sp::xmlpatch::Error::None) {
            std::fprintf(stderr, "sp-xmlpatch: %s: %s (%s)\n", files[0], sp::xmlpatch::toString(s.error),
                         s.message.c_str());
            return kExitNotConfig;
        }
        std::string streams;
        for (const std::string& t : s.postprocess) streams += (streams.empty() ? "" : ",") + t;
        std::printf("version=%s\nlibrary=%d\neffect=%d\npostprocess=%s\n", s.version.c_str(),
                    s.library ? 1 : 0, s.effect ? 1 : 0, streams.c_str());
        return 0;
    }
    return usage();
}
