// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
//
// Process-wide state shared by every instance of the effect: the one set of user parameters,
// the instance registry, the duplicate-session guard and the persisted state file.
//
// Postprocess effects get one instance per audio session, and the app can only talk to its own
// control instance. So a SET_PARAM on *any* instance updates the parameters here, which are
// then pushed to every registered instance (each designs its filters at its own sample rate).
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <atomic>
#include <mutex>
#include <span>
#include <string>

#include "codec.h"
#include "soundpower/protocol.h"
#include "sp/dsp/types.h"

namespace sp::fx {

class Instance;

class GlobalState {
public:
    /// `stateFilePath` is read by reloadStateFileIfChanged(); empty disables the state file.
    explicit GlobalState(std::string stateFilePath = {});
    /// All instances must have been destroyed.
    ~GlobalState();

    GlobalState(const GlobalState&) = delete;
    GlobalState& operator=(const GlobalState&) = delete;

    /// Registers a new instance. Returns nullptr when SP_MAX_INSTANCES are alive or memory is
    /// exhausted. If a live instance already processes the same (session, I/O handle), the new
    /// one is marked duplicate and passes audio through untouched.
    [[nodiscard]] Instance* createInstance(std::int32_t sessionId, std::int32_t ioId);

    /// Unregisters and frees an instance. If it was the active one for its session, the oldest
    /// duplicate takes over (fading in).
    void destroyInstance(Instance* instance);

    /// SET_PARAM: decodes, clamps, stores and pushes to every instance. Returns 0 or -EINVAL.
    /// INPUT_VOLUME_SESSIONS is kept apart from the parameters: it is never persisted.
    int setParam(std::uint32_t id, std::span<const std::uint8_t> value);

    /// GET_PARAM on behalf of `asking`. Returns the payload size, or -EINVAL for unknown IDs and
    /// a too-small `out`.
    int getParam(Instance& asking, std::uint32_t id, std::span<std::uint8_t> out);

    /// STATUS for `instance`, as GET_PARAM(STATUS) would report it.
    [[nodiscard]] Status status(Instance& instance);

    /// Re-reads the state file if its size, mtime or inode changed since the last attempt.
    /// A missing file keeps the current parameters; an invalid one is logged and ignored.
    /// Returns true if new parameters were applied.
    bool reloadStateFileIfChanged();

    /// Direct volume control: whether the effect's descriptor claims EFFECT_FLAG_VOLUME_CTRL.
    /// Decided once, on the first call (the effects factory reads the descriptor when it loads
    /// the library and never again), from MASTER's direct-volume flag in the state file. Later
    /// calls return the same answer; changing the flag takes effect when the library is loaded
    /// again (audio HAL restart or reboot).
    bool claimsVolumeControl();

    [[nodiscard]] dsp::ChainParams params() const;
    [[nodiscard]] std::size_t instanceCount() const;

    /// The highest sample rate any instance can run at. Stored parameters are clamped for it;
    /// each instance clamps again for its own rate.
    static constexpr double kHighestSampleRate = 384000.0;

private:
    friend class Instance;

    struct FileStamp {
        std::uint64_t inode = 0;
        std::int64_t size = -1;
        std::int64_t mtimeNs = 0;
        bool operator==(const FileStamp&) const = default;
    };

    void broadcastLocked();

    mutable std::mutex mutex_;
    dsp::ChainParams params_{};
    InputVolumeSessions inputVolume_{};
    std::array<Instance*, SP_MAX_INSTANCES> instances_{};
    std::size_t count_ = 0;

    const std::string statePath_;
    FileStamp stamp_{};
    bool haveStamp_ = false;

    std::once_flag volumeControlOnce_;
    std::atomic<bool> volumeControl_{false};
};

}  // namespace sp::fx
