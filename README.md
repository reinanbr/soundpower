# SoundPower

An open source, system-wide audio equalizer and processor for rooted Android devices.

SoundPower is a native audio effect, loaded by the device's audio HAL, that processes the output of
every music app. It is installed through a Magisk module and controlled by a Kotlin app.

> **Status:** early development. Phase 2 (`dsp-core`: parametric EQ, limiter, tests, WAV CLI) is
> complete. The Android effect, Magisk module and app are not written yet, so nothing here runs on
> a device.

## Repository layout

| Path | Contents |
|---|---|
| `dsp-core/` | Pure C++20 DSP library (no Android dependencies), tests, benchmark and the `sp-cli` WAV tool |
| `protocol/` | The versioned parameter protocol shared by the effect and the app |
| `effect-lib/` | Android audio effect wrapper (`libsoundpower.so`) — phase 3 |
| `magisk-module/` | Magisk module sources and zip builder — phase 4 |
| `app/`, `android-core/` | Kotlin + Jetpack Compose app — phase 5 |
| `docs/` | [Architecture](docs/architecture.md), [parameter protocol](docs/protocol.md), [risk register](docs/risks.md) |

## Building `dsp-core` on a desktop

Requirements: CMake ≥ 3.22, Ninja, and a C++20 compiler (GCC ≥ 12 or Clang ≥ 15).

```sh
cmake --preset host-debug
cmake --build --preset host-debug
ctest --preset host-debug
```

Other presets: `host-release`, `host-asan` (AddressSanitizer + UndefinedBehaviorSanitizer) and
`host-tsan` (ThreadSanitizer). Add `-DSP_WERROR=ON` to the configure step to treat warnings as
errors, as CI does. To build with Clang, set `CC=clang CXX=clang++` before configuring.

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
