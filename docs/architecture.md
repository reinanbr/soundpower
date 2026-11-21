# SoundPower architecture

SoundPower has four parts, built and tested independently:

| Part | Language | Runs in | Role |
|---|---|---|---|
| `dsp-core` | C++20 | anywhere (desktop for tests) | All signal processing. No Android dependencies. |
| `effect-lib` | C++20 | the audio HAL process on the device | Exposes `dsp-core` as an Android audio effect (`libsoundpower.so`). |
| `magisk-module` | shell + a small native tool | install time / boot | Installs the library and patches the device's effects configuration systemlessly. |
| `app` | Kotlin, Jetpack Compose | user space | Onboarding, EQ editing, presets, per-device profiles, diagnostics. |

```
 Kotlin app (MVVM)                         audio HAL process (hal_audio_server domain)
 ┌─────────────────────────┐   binder   ┌──────────────────────────────────────────────┐
 │ UI (Compose) → ViewModel│──────────► │ libsoundpower.so  (AELI entry point)         │
 │ EffectController        │ AudioEffect│  ├─ GlobalState (process-wide, control side)  │
 │  (HiddenApiBypass)      │ set/getParam  │   latest params + instance registry        │
 │ RootManager (libsu)     │            │  ├─ Instance ×N (one per session)             │
 │ Room / DataStore        │            │  │   command() → validate → GlobalState       │
 └──────────┬──────────────┘            │  │   process() → dsp-core Processor (RT)      │
            │ su: write state/IR files  │  └─ state.bin loader (on create_effect)       │
            ▼                           └───────────────▲──────────────────────────────┘
   /data/vendor/soundpower/  ── read-only for HAL ───────┘   (loaded via patched audio_effects.xml
   (state.bin, ir/*.wav)                                      <postprocess> on music streams)
```

## How audio reaches the effect

1. The Magisk module overlays a copy of the device's own effects configuration (for example
   `/vendor/etc/audio_effects.xml`). The copy adds a `<library>`, an `<effect>`, and a `<postprocess>`
   block that applies the effect to `music` streams.
2. When an app starts playing a music stream, the audio policy attaches one SoundPower **instance
   to that audio session**. AudioFlinger then calls the instance's `process()` with that session's audio.
3. On Android 8 and later, effect libraries are loaded by the **audio HAL process** through the effects
   factory, not by `audioserver` itself. Everything in `effect-lib` therefore runs inside a privileged
   vendor process.
4. Fallback for devices where postprocess does not work: the app listens for
   `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcasts and attaches an instance to each announced
   session. This mode is mutually exclusive with postprocess. The module is installed without the
   `<postprocess>` block, and a duplicate-session guard self-bypasses a second instance on the same session.

## Shared parameter state

Postprocess creates one instance per session, and the app cannot address those instances directly.
The library keeps a process-wide **GlobalState**:

- *Any* instance that receives `EFFECT_CMD_SET_PARAM` updates GlobalState.
- GlobalState designs filter coefficients for **each registered instance at its own sample rate**, then
  publishes them to that instance through a lock-free triple buffer.
- The app controls the effect through its own **control instance**, attached to a private audio session
  (`AudioManager.generateAudioSessionId()`, no tracks). *Whether AudioFlinger accepts an effect on a
  track-less session must be verified on a device in phase 3/4.*
- The app also writes the full state to `/data/vendor/soundpower/state.bin` (as root). That directory is
  device-encrypted storage, so it can be read before the user unlocks. The library reads it when an
  instance is created and the file's inode, size or modification time has changed since the last
  read, so the EQ is correct from boot even if the app never runs.

## Processing chain

The order is fixed. The bold stages exist in phase 2; the rest arrive in phase 6.

**input → preamp → parametric EQ (≤32 bands)** → convolver → bass enhancement → loudness compensation →
multiband compressor → M/S stereo width → crossfeed → **lookahead limiter (always on) → output**

- **A/B bypass** crossfades (~20 ms) between the processed signal and a **latency-aligned dry path**. The
  dry signal is delayed by the limiter lookahead, so the crossfade never comb-filters.
- **Filter changes** crossfade the old filter bank into the new one over ~10 ms. The new bank starts
  from the old bank's signal history. The cost doubles only during the transition. Scalar gains use
  per-sample ramps.
- **Biquads** use Direct Form I with double-precision coefficients and state, and float I/O. In DF-I the
  state is the actual input/output history, which stays meaningful when coefficients change. That is
  what lets the new bank take over the old bank's history cleanly. Inactive and 0 dB bands are skipped.
- **Sub-blocking:** `process()` works in internal blocks of at most 256 frames, so it does not depend on
  the host's buffer size.
- **Denormals:** flush-to-zero is enabled for the duration of every `process()` call.

## Real-time rules and threading

| Context | Entry points | Allowed |
|---|---|---|
| HAL process thread | `process()` | Atomics, the engine hazard pointer, triple-buffer reads. **No** allocation, locks, logging or I/O. |
| HAL binder threads | `command()`, `create_effect()`, `release_effect()` | `SET_PARAM`, `GET_PARAM`, `SET_CONFIG`, `INIT` and `RESET` take the GlobalState mutex (control side only). `ENABLE`, `DISABLE`, `SET_VOLUME` and `SET_DEVICE` touch atomics only, because AudioFlinger may issue them from its mixer thread. |

All allocation happens on the control side. When `EFFECT_CMD_SET_CONFIG` changes the stream format,
the instance builds a new **engine** (a `dsp-core` `Processor` plus scratch buffers), prepares it and
publishes it with an atomic exchange. The audio thread announces the engine it is using in a single
hazard pointer for the duration of each `process()` call. The control thread then waits (sleeping
100 µs at a time, at most one `process()` call) until the old engine is no longer in use and frees it.
This replaces the worker-thread reclamation planned in phase 1: v1 has no library thread at all.
Phase 6 adds one for impulse-response loading.

`dsp-core` and `effect-lib` enforce these rules in their tests. A global `operator new` counter asserts
zero allocations inside `process()` (through enable, disable, format changes, parameter changes,
resets and duplicate hand-over), and ThreadSanitizer stress tests run audio, control, configuration
and instance-churn threads concurrently.

## effect-lib

`libsoundpower.so` exports exactly one symbol, `AELI` (`AUDIO_EFFECT_LIBRARY_INFO_SYM`, an
`audio_effect_library_t` version 3.0). A linker version script hides everything else, the C++
runtime is linked statically, and the only `NEEDED` libraries are `libc`, `libm`, `libdl` and `liblog`.
A build-time test checks all of this, plus 16 KB segment alignment for 64-bit ABIs.

| Source | Role |
|---|---|
| `src/android/effect_api.cpp` | The Android ABI: descriptor, `create_effect` / `release_effect` / `get_descriptor`, the `effect_interface_s` table and command parsing. The only file that includes AOSP headers. |
| `src/global_state.*` | Process-wide parameters, the instance registry, the duplicate-session guard and the `state.bin` loader. |
| `src/instance.*` | One effect instance: its engine, enable/disable fades, format conversion, statistics. |
| `src/codec.*`, `src/state_file.*`, `src/wire.h` | Bounds-checked (de)serialization of protocol payloads and `state.bin`. |
| `src/log.*` | Logging with the fixed tag `SoundPower` (`liblog` on Android, `stderr` on the host when `SP_LOG` is set). Never called from `process()`. |

**Descriptor.** Type `69dc09f7-…`, implementation `23e79ffb-…` (see the [protocol](protocol.md)), flags
`INSERT | INSERT_LAST | VOLUME_IND | DEVICE_IND`, no offload. `cpuLoad` and `memoryUsage` are declared as
0 and 1. AudioPolicy adds the declared values of every live effect and refuses new effects above about
43 MIPS / 512 KB. Postprocess creates one instance per session, so honest per-instance figures would make
SoundPower (or other effects) fail to attach once a few sessions play at once.

**Streams.** Input and output must have the same sample rate (8–384 kHz) and channel mask (mono or
stereo) and format (PCM float or PCM 16-bit). The output may be written or accumulated. 16-bit output
is TPDF-dithered. Anything else makes `SET_CONFIG` fail with `-EINVAL`, keeping the previous configuration.

**Commands.**

| Command | Behavior |
|---|---|
| `INIT`, `RESET` | Clear the DSP state (filter history, limiter). |
| `SET_CONFIG` / `GET_CONFIG` | Validate and apply / report the stream format. Fields not set in `mask` keep their values; a zero mask means all fields. |
| `ENABLE` / `DISABLE` | Fade the processed signal in or out over 10 ms. After the fade-out, `process()` passes the input through and returns `-ENODATA`, which tells AudioFlinger to stop calling it. |
| `SET_PARAM` / `GET_PARAM` | The [protocol](protocol.md). Any instance accepts parameters for the whole process. |
| `SET_DEVICE`, `SET_VOLUME` | Recorded (atomics only) for diagnostics; v1 does not use them. `SET_VOLUME` echoes the volumes unchanged, so the framework still applies the full volume. |
| `OFFLOAD` | Returns `-EINVAL` for offloaded outputs (the effect is not offloadable). |
| `DUMP` | Writes one status line to the given file descriptor (`dumpsys media.audio_flinger`). |
| anything else | `-EINVAL`. |

**Duplicate-session guard.** A second instance on the same session and output (for example, from
the control-session fallback while postprocess is also active) passes audio through bit-exactly and
sets `SP_STATUS_FLAG_DUPLICATE_SESSION`. When the first instance is released, the oldest duplicate
takes over, fading in.

**Tests.** `effect-lib/tests` covers the codec and `state.bin` against the golden vectors, instance
fades and formats, GlobalState, concurrency (TSan), zero allocation, and the Android API exactly as
AudioFlinger drives it (including `dlopen` of the real library). A libFuzzer target
(`effect-lib/fuzz`) feeds arbitrary command sequences, configurations and samples to two instances,
with every buffer sized exactly so that AddressSanitizer catches any overrun. The cross-compiled tests
run on a device through `scripts/adb-exec.sh`.

## Repository layout

```
soundpower/
├── VERSION  LICENSE  README.md  CMakeLists.txt  CMakePresets.json
├── protocol/            # UUIDs, parameter IDs, payload layouts (C header) + golden test vectors
├── dsp-core/            # include/sp/dsp, src, tests (GoogleTest), bench, cli (sp-cli)
├── effect-lib/          # Android effect wrapper (libsoundpower.so), tests, fuzzer
├── tools/xmlpatch/      # idempotent effects-XML patcher, host + NDK static build (phase 4)
├── magisk-module/       # module template, fixture tests, zip builder (phase 4)
├── scripts/             # adb-exec.sh (runs cross-compiled tests on a device); device test script (phase 4)
├── app/, android-core/  # Gradle modules; Gradle root is the repository root (phase 5)
├── third_party/         # pinned, vendored dependencies with their licenses
├── docs/
└── .github/workflows/
```

## Toolchain

| Component | Choice |
|---|---|
| Native build | CMake ≥ 3.22 + Ninja; presets in `CMakePresets.json` |
| Tests | GoogleTest 1.17.0 (fetched, SHA-256 pinned); libFuzzer (Clang) for the command interface |
| WAV I/O | dr_wav 0.14.6 (vendored; CLI and, in phase 6, IR loading) |
| Android native | NDK r28 (`c++_static`, 16 KB page alignment), ABIs `arm64-v8a` and `armeabi-v7a`, API 28. AOSP effect headers vendored from `android-12.1.0_r27`. |
| Host build of the AELI glue | Clang only: the AOSP `audio.h` declares C++ overloads inside `extern "C"`, which GCC rejects. GCC builds and tests everything else. |
| App | Kotlin, Jetpack Compose, Material 3, minSdk 28, latest stable targetSdk, libsu, HiddenApiBypass |
