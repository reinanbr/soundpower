// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#include "global_state.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <new>
#include <utility>
#include <vector>

#include "instance.h"
#include "log.h"
#include "sp/dsp/limits.h"
#include "state_file.h"

namespace sp::fx {

namespace {

bool sameStream(const Instance& a, const Instance& b) noexcept {
    return a.sessionId() == b.sessionId() && a.ioId() == b.ioId();
}

/// Reads a whole regular file of at most SP_STATE_FILE_MAX_BYTES (one extra byte is read so
/// that an oversized file is detected). Control side only.
bool readSmallFile(const std::string& path, std::vector<std::uint8_t>& out) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    out.resize(SP_STATE_FILE_MAX_BYTES + 1);
    std::size_t total = 0;
    while (total < out.size()) {
        const ssize_t n = ::read(fd, out.data() + total, out.size() - total);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        total += static_cast<std::size_t>(n);
    }
    ::close(fd);
    out.resize(total);
    return true;
}

}  // namespace

GlobalState::GlobalState(std::string stateFilePath)
    : params_(dsp::sanitize(dsp::ChainParams{}, kHighestSampleRate)),
      statePath_(std::move(stateFilePath)) {}

GlobalState::~GlobalState() {
    if (count_ != 0) log::error("GlobalState destroyed with %zu live instances", count_);
}

Instance* GlobalState::createInstance(std::int32_t sessionId, std::int32_t ioId) {
    auto* instance = new (std::nothrow) Instance(*this, sessionId, ioId);
    if (instance == nullptr) return nullptr;

    std::lock_guard lock(mutex_);
    if (count_ == instances_.size()) {
        delete instance;
        log::error("refusing instance for session %d: %zu instances alive", sessionId, count_);
        return nullptr;
    }
    bool duplicate = false;
    for (std::size_t i = 0; i < count_; ++i) {
        if (sameStream(*instances_[i], *instance) && !instances_[i]->duplicate()) duplicate = true;
    }
    instance->setDuplicateLocked(duplicate);
    instance->setVolumeAtInput(inputVolume_.contains(sessionId));
    instances_[count_++] = instance;
    if (duplicate) {
        log::warn("session %d (io %d) already has a SoundPower instance; the new one passes audio "
                  "through",
                  sessionId, ioId);
    }
    return instance;
}

void GlobalState::destroyInstance(Instance* instance) {
    if (instance == nullptr) return;
    {
        std::lock_guard lock(mutex_);
        std::size_t index = count_;
        for (std::size_t i = 0; i < count_; ++i) {
            if (instances_[i] == instance) index = i;
        }
        if (index == count_) {
            log::error("destroyInstance: unknown instance %p", static_cast<void*>(instance));
            return;
        }
        // Keep registration order (the oldest duplicate is promoted first).
        for (std::size_t i = index; i + 1 < count_; ++i) instances_[i] = instances_[i + 1];
        instances_[--count_] = nullptr;

        if (!instance->duplicate()) {
            for (std::size_t i = 0; i < count_; ++i) {
                if (sameStream(*instances_[i], *instance) && instances_[i]->duplicate()) {
                    instances_[i]->setDuplicateLocked(false);
                    log::info("session %d: duplicate instance takes over", instance->sessionId());
                    break;
                }
            }
        }
    }
    delete instance;  // outside the lock: it may wait for the audio thread
}

int GlobalState::setParam(std::uint32_t id, std::span<const std::uint8_t> value) {
    if (id == SP_PARAM_INPUT_VOLUME_SESSIONS) {
        std::lock_guard lock(mutex_);
        if (const int status = decodeInputVolumeSessions(value, inputVolume_); status != 0) {
            return status;
        }
        for (std::size_t i = 0; i < count_; ++i) {
            instances_[i]->setVolumeAtInput(inputVolume_.contains(instances_[i]->sessionId()));
        }
        return 0;
    }
    if (!isSettable(id)) return -EINVAL;
    std::lock_guard lock(mutex_);
    dsp::ChainParams next = params_;
    if (const int status = decodeParam(id, value, next); status != 0) return status;
    params_ = dsp::sanitize(next, kHighestSampleRate);
    broadcastLocked();
    return 0;
}

int GlobalState::getParam(Instance& asking, std::uint32_t id, std::span<std::uint8_t> out) {
    switch (id) {
        case SP_PARAM_PROTOCOL_INFO:
            return encodeProtocolInfo(out);
        case SP_PARAM_STATUS: {
            if (out.size() < SP_STATUS_SIZE) return -EINVAL;  // don't consume the peak/flags
            return encodeStatus(status(asking), out);
        }
        case SP_PARAM_INPUT_VOLUME_SESSIONS: {
            std::lock_guard lock(mutex_);
            return encodeInputVolumeSessions(inputVolume_, out);
        }
        default: {
            if (!isSettable(id)) return -EINVAL;
            std::lock_guard lock(mutex_);
            return encodeParam(id, params_, out);
        }
    }
}

Status GlobalState::status(Instance& instance) {
    std::lock_guard lock(mutex_);
    Status s = instance.statusLocked();
    s.instances = static_cast<std::uint32_t>(count_);
    if (params_.bypass) s.flags |= SP_STATUS_FLAG_BYPASSED;
    if (volumeControl_.load(std::memory_order_acquire)) s.flags |= SP_STATUS_FLAG_VOLUME_CTRL;
    for (std::size_t i = 0; i < count_; ++i) {
        if (instances_[i]->volumeApplied() && instances_[i]->enabled()) {
            s.flags |= SP_STATUS_FLAG_VOLUME_APPLIED;
            break;
        }
    }
    return s;
}

bool GlobalState::claimsVolumeControl() {
    std::call_once(volumeControlOnce_, [this] {
        reloadStateFileIfChanged();
        bool claim;
        {
            std::lock_guard lock(mutex_);
            claim = params_.directVolume;
        }
        volumeControl_.store(claim, std::memory_order_release);
        log::info("direct volume control %s", claim ? "on: the effect applies the stream volume"
                                                    : "off");
    });
    return volumeControl_.load(std::memory_order_acquire);
}

bool GlobalState::reloadStateFileIfChanged() {
    if (statePath_.empty()) return false;

    struct stat st {};
    if (::stat(statePath_.c_str(), &st) != 0) return false;  // no file: keep what we have
    FileStamp stamp;
    stamp.inode = static_cast<std::uint64_t>(st.st_ino);
    stamp.size = static_cast<std::int64_t>(st.st_size);
    stamp.mtimeNs = static_cast<std::int64_t>(st.st_mtim.tv_sec) * 1000000000 +
                    static_cast<std::int64_t>(st.st_mtim.tv_nsec);
    {
        std::lock_guard lock(mutex_);
        if (haveStamp_ && stamp == stamp_) return false;
        // Record the attempt even if it fails, so a broken file isn't re-parsed on every
        // instance creation.
        stamp_ = stamp;
        haveStamp_ = true;
    }

    std::vector<std::uint8_t> bytes;
    if (!readSmallFile(statePath_, bytes)) {
        log::warn("cannot read %s: %s", statePath_.c_str(), std::strerror(errno));
        return false;
    }

    std::lock_guard lock(mutex_);
    dsp::ChainParams next = params_;
    const StateFileResult r = applyStateFile(bytes, next);
    if (r.error != StateFileError::None) {
        log::warn("ignoring %s: %s (protocol %u.%u, entry 0x%04x)", statePath_.c_str(),
                  toString(r.error), unsigned{r.major}, unsigned{r.minor}, r.failedId);
        return false;
    }
    params_ = dsp::sanitize(next, kHighestSampleRate);
    broadcastLocked();
    log::info("loaded %s: %u entries applied, %u skipped (protocol %u.%u)", statePath_.c_str(),
              r.applied, r.skipped, unsigned{r.major}, unsigned{r.minor});
    return true;
}

dsp::ChainParams GlobalState::params() const {
    std::lock_guard lock(mutex_);
    return params_;
}

std::size_t GlobalState::instanceCount() const {
    std::lock_guard lock(mutex_);
    return count_;
}

void GlobalState::broadcastLocked() {
    for (std::size_t i = 0; i < count_; ++i) instances_[i]->applyParamsLocked(params_);
}

}  // namespace sp::fx
