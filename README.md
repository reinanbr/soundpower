# SoundPower

An open source, system-wide audio equalizer and processor for rooted Android devices.

SoundPower is a native audio effect, loaded by the device's audio HAL, that processes the output of
every music app. It is installed through a Magisk module and controlled by a Kotlin app.

> **Status:** early development. Phase 2 (`dsp-core`) is in progress. Nothing here is ready for
> devices yet.

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
`host-tsan` (ThreadSanitizer).

## License

SoundPower is free software, licensed under the [GNU General Public License v3.0](LICENSE).
Third-party components keep their own licenses; see `third_party/`.
