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
 │ RootManager (libsu)     │            │  ├─ EffectContext ×N (one per session)        │
 │ Room / DataStore        │            │  │   command() → validate → GlobalState       │
 └──────────┬──────────────┘            │  │   process() → dsp-core Processor (RT)      │
            │ su: write state/IR files  │  └─ Worker thread: file loads, IR, GC         │
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
  device-encrypted storage, so it can be read before the user unlocks. The library loads it when the
  first instance is created, so the EQ is correct from boot even if the app never runs.

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
| HAL process thread | `process()` | Atomics, triple-buffer reads, SPSC writes (spectrum ring, stats). **No** allocation, locks, logging or I/O. |
| HAL binder threads | `command()` | `SET_PARAM`, `SET_CONFIG` and `INIT` take the registry mutex (control side only). `SET_VOLUME` and `SET_DEVICE` touch atomics only, because AudioFlinger may issue them from its mixer thread. |
| Library worker thread | file and IR loading, resampling, deferred frees, deferred logging | Anything |

All allocation happens in `prepare()`, called on `EFFECT_CMD_SET_CONFIG`. When a reconfiguration needs
new memory, a new `Processor` is built on the control side and swapped in atomically. The old one goes
to the worker thread to be freed (deferred reclamation).

`dsp-core` enforces these rules in its tests. A global `operator new` counter asserts zero allocations
inside `process()`, and a ThreadSanitizer preset exercises concurrent parameter updates.

## Repository layout

```
soundpower/
├── VERSION  LICENSE  README.md  CMakeLists.txt  CMakePresets.json
├── protocol/            # UUIDs, parameter IDs, payload layouts (C header) + golden test vectors
├── dsp-core/            # include/sp/dsp, src, tests (GoogleTest), bench, cli (sp-cli)
├── effect-lib/          # Android effect wrapper + desktop host harness and fuzzer (phase 3)
├── tools/xmlpatch/      # idempotent effects-XML patcher, host + NDK static build (phase 4)
├── magisk-module/       # module template, fixture tests, zip builder (phase 4)
├── scripts/             # device test script (phase 4)
├── app/, android-core/  # Gradle modules; Gradle root is the repository root (phase 5)
├── third_party/         # pinned, vendored dependencies with their licenses
├── docs/
└── .github/workflows/
```

## Toolchain

| Component | Choice |
|---|---|
| Native build | CMake ≥ 3.22 + Ninja; presets in `CMakePresets.json` |
| Tests | GoogleTest 1.17.0 (fetched, SHA-256 pinned) |
| WAV I/O | dr_wav 0.14.6 (vendored; CLI and, in phase 6, IR loading) |
| Android native | NDK r28 (`c++_static`, 16 KB page alignment), ABIs `arm64-v8a` and `armeabi-v7a`, API 28 |
| App | Kotlin, Jetpack Compose, Material 3, minSdk 28, latest stable targetSdk, libsu, HiddenApiBypass |
