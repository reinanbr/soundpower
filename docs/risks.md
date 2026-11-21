# Risk register

Ranked by impact × likelihood. Items marked **[verify]** are assumptions about Android or Magisk
internals that have not yet been confirmed against source code or a device. Each lists how and when
it will be checked.

| # | Risk | Mitigation | Phase |
|---|---|---|---|
| 1 | **AIDL effects HAL.** Some devices running Android 14+ use the AIDL effects HAL, which does not load legacy `AUDIO_EFFECT_LIBRARY_INFO_SYM` libraries. On those devices, the approach in this repository does nothing. | `customize.sh` and the app detect `android.hardware.audio.effect.IFactory` in `service list`; v1 aborts installation with a clear message. An AIDL adapter would be a separate phase, after verifying the AOSP entry points and config file name in `hardware/interfaces/audio/aidl/default/EffectFactory.cpp`. **[verify]** | 3–4 |
| 2 | **32-bit audio HAL on 64-bit devices.** Many arm64 devices still run a 32-bit audio HAL. | Always build both ABIs and install both `lib/soundfx` and `lib64/soundfx`. The device script reports the HAL's ELF class. | 3–4 |
| 3 | **Effects config variants.** Vendors use `/odm/etc`, `/vendor/etc`, `/vendor/etc/audio/<sku>/` and legacy `.conf` files with different search orders. | Patch every variant found, with an idempotent patcher that removes previous SoundPower entries first (so updates work). Log which file the factory parsed. **[verify]** the log text. Legacy `.conf` gets best-effort library/effect injection only. **[verify]** whether `.conf` supports output post-processing. | 4 |
| 4 | **Partitions Magisk cannot overlay**, such as `/odm` on some devices. | Bind-mount fallback in `post-fs-data.sh` (HAL services start after post-fs-data). | 4 |
| 5 | **SELinux.** Overlaid files need the right contexts, and the HAL domain name varies by vendor. | `vendor_file` / `vendor_configs_file` contexts set at install. The data directory gets a custom type through `sepolicy.rule`, with rules written against the `hal_audio_server` attribute. **[verify]** that magiskpolicy accepts attributes in `allow` rules. The device script collects `avc: denied` lines. | 4 |
| 6 | **Paths that bypass software effects:** compress offload, direct/hi-res/bit-perfect USB output, AAudio MMAP exclusive mode. | Document them. The diagnostics screen shows how many instances are live. **[verify]** whether the framework turns off offload when a non-offloadable postprocess effect is enabled. | 4, 8 |
| 7 | **Untrusted callers.** Any app holding `MODIFY_AUDIO_SETTINGS` can create the effect and send commands into a privileged HAL process. | Strict size validation, clamping, no casts of input buffers, a libFuzzer target on the host harness. Accepted residual risk: other apps can change EQ settings (same as AOSP's equalizer). | 3 |
| 8 | **Concurrency.** `command()` and `process()` run on different HAL threads. | Lock-free parameter path (triple buffer, atomics). A ThreadSanitizer stress test in the host harness. | 2–3 |
| 9 | **A HAL crash means no sound for the whole device.** | `service.sh` watchdog: if audioserver/HAL restarts spike after boot, create the module's `disable` file and log why. Document Magisk safe mode. | 4 |
| 10 | **Vendor linker namespace and STL.** Vendor processes can only load LL-NDK dependencies, and `libc++_shared.so` is not available. | Build with `c++_static`; link only `liblog`, `libc`, `libm`, `libdl`. CI checks the `NEEDED` list with `llvm-readelf -d`. NDK r28 produces 16 KB-aligned segments for 16 KB page-size devices. | 3 |
| 11 | **Hidden API access** (`AudioEffect(UUID,UUID,int,int)` and `set/getParameter(byte[],byte[])` are not public SDK) may break on a future Android version. | HiddenApiBypass. A designed-in fallback channel: the app rewrites `state.bin` as root, and the worker thread reloads it on inotify (implemented only if needed). | 5 |
| 12 | **Other audio mods** (ViPER4Android, JamesDSP, Dolby ports) overlaying the same XML. | `customize.sh` detects other modules that ship effects XML and warns. | 4 |
| 13 | **Control instance on a track-less session.** The design assumes AudioFlinger accepts an effect on a private session with no tracks. **[verify]** | Test on a device. Fallback: attach the control instance to the session of the app's own silent `AudioTrack`. | 3–4 |
| 14 | **Volume semantics.** Whether stream volume is applied before or after session effects decides how loudness compensation reads the listening level. **[verify]** | Measure on a device before implementing loudness compensation. | 6 |
| 15 | **Latency.** The limiter lookahead (1.5 ms default) is not reported to the framework. | Small compared with output buffering. Documented. | — |
| 16 | **CPU scales with sessions.** Postprocess creates one instance per playing session. | Benchmark target: EQ32 + limiter, stereo 48 kHz, < 2 % of one big core per instance on arm64. | 2–3 |

## Open questions

These don't block the current phase. They are revisited at the phase where they matter.

- Which effects HAL does the test device use (HIDL or AIDL)? The device script in phase 3/4 answers this.
- Should streams other than `music` be processed by default?
- Is a −1 dBFS default limiter ceiling right?
- Release channel (GitHub Releases + Magisk `updateJson`) once a remote repository exists.
