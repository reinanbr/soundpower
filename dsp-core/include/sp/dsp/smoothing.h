// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 SoundPower contributors
#pragma once

#include <cstdint>

namespace sp::dsp {

/// Linear ramp toward a target over a fixed number of samples. Real-time safe.
class LinearRamp {
public:
    /// Jumps to `value` immediately.
    void reset(double value) noexcept {
        current_ = value;
        target_ = value;
        step_ = 0.0;
        remaining_ = 0;
    }

    /// Ramps from the current value to `target` over `samples` samples (0 = jump).
    void setTarget(double target, std::uint32_t samples) noexcept {
        target_ = target;
        if (samples == 0 || target == current_) {
            reset(target);
            return;
        }
        step_ = (target - current_) / static_cast<double>(samples);
        remaining_ = samples;
    }

    /// Advances one sample and returns the new value.
    double next() noexcept {
        if (remaining_ == 0) return current_;
        if (--remaining_ == 0) {
            current_ = target_;  // land exactly on the target, no accumulated error
        } else {
            current_ += step_;
        }
        return current_;
    }

    [[nodiscard]] bool ramping() const noexcept { return remaining_ != 0; }
    [[nodiscard]] double current() const noexcept { return current_; }
    [[nodiscard]] double target() const noexcept { return target_; }

private:
    double current_ = 0.0;
    double target_ = 0.0;
    double step_ = 0.0;
    std::uint32_t remaining_ = 0;
};

}  // namespace sp::dsp
