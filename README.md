# SoundPower

An open source, system-wide audio equalizer and processor for rooted Android devices.

SoundPower is a native audio effect, loaded by the device's audio HAL, that processes the output of
every music app. It is installed through a Magisk module and controlled by a Kotlin app.

> **Status:** early development. Phase 2 (`dsp-core`: parametric EQ, limiter, tests, WAV CLI) and
> phase 3 (`effect-lib`: the Android audio effect library) are complete and tested on a device. The
> Magisk module that installs the effect and the app are not written yet, so the effect is not active
> on any device yet.

## Repository layout

| Path | Contents |
|---|---|
| `dsp-core/` | Pure C++20 DSP library (no Android dependencies), tests, benchmark and the `sp-cli` WAV tool |
| `protocol/` | The versioned parameter protocol shared by the effect and the app |
| `effect-lib/` | Android audio effect library (`libsoundpower.so`), its tests and a libFuzzer target |
| `magisk-module/` | Magisk module sources and zip builder — phase 4 |
| `app/`, `android-core/` | Kotlin + Jetpack Compose app — phase 5 |
| `docs/` | [Architecture](docs/architecture.md), [parameter protocol](docs/protocol.md), [risk register](docs/risks.md) |

## Building and testing on a desktop

Requirements: CMake ≥ 3.22, Ninja, and a C++20 compiler (GCC ≥ 12 or Clang ≥ 15).

```sh
cmake --preset host-debug
cmake --build --preset host-debug
ctest --preset host-debug
```

Other presets: `host-release`, `host-asan` (AddressSanitizer + UndefinedBehaviorSanitizer) and
`host-tsan` (ThreadSanitizer). Add `-DSP_WERROR=ON` to the configure step to treat warnings as
errors, as CI does. To build with Clang, set `CC=clang CXX=clang++` before configuring.

With GCC, `effect-lib` builds and tests its core only. Building `libsoundpower.so` and its Android API
tests on the host needs Clang, because the AOSP headers it includes are not valid C++ for GCC.

The `host-fuzz` preset (Clang, AddressSanitizer + UndefinedBehaviorSanitizer) builds a libFuzzer target
for the effect's command interface:

```sh
cmake --preset host-fuzz && cmake --build --preset host-fuzz
build/host-fuzz/effect-lib/fuzz/sp_effect_fuzz -max_total_time=300 corpus/
```

## Building for Android

Requirements: Android NDK r28 (`sdkmanager "ndk;28.2.13676358"`) and `ANDROID_NDK_HOME` pointing at it.

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
cmake --preset android-armv7 && cmake --build --preset android-armv7    # or android-arm64
```

The library is at `build/android-armv7/effect-lib/libsoundpower.so`. With a device connected over
`adb`, `ctest --preset android-armv7` runs every test suite **on the device**: each test binary is
pushed to `/data/local/tmp/soundpower-tests/<abi>/` and run as the shell user (no root, nothing
installed). Set `ANDROID_SERIAL` to choose a device. Check whether the device's audio HAL is 32- or
64-bit (`file /vendor/bin/hw/android.hardware.audio*`) to know which ABI matters there.

## Trying the DSP on WAV files

`sp-cli` runs files through the same processing chain the Android effect will use:

```sh
cli=build/host-release/dsp-core/cli/sp-cli

$cli gen sweep sweep.wav --seconds 5 --amp 0.9          # test signal
$cli process sweep.wav out.wav \
     --band lowshelf:80:6:0.7 --band peak:3000:-4:2.0    # EQ + limiter
$cli response --band peak:1000:6:1.0 --fs 48000         # EQ curve as CSV
$cli bench                                              # CPU cost per block
```

Band types are `peak`, `lowshelf`, `highshelf`, `lowpass`, `highpass`, `notch` and `bandpass`.
Run `sp-cli --help` for every option (preamp, auto-preamp, limiter ceiling/lookahead/release,
bypass, output format).

## License

SoundPower is free software, licensed under the [GNU General Public License v3.0](LICENSE).
Third-party components keep their own licenses; see `third_party/`.
