# SoundPower

An open source, system-wide audio equalizer and processor for rooted Android devices.

SoundPower is a native audio effect, loaded by the device's audio HAL, that processes the output of
every music app. It is installed through a Magisk module and controlled by a Kotlin app.

> **Status:** early development. The DSP core, the Android effect library, the Magisk module and
> the app are in place: graphic (5–32 bands) and parametric (up to 32 bands, each on both
> channels, left, right, mid or side) equalizers, bass and treble, balance, width and mono, a
> compressor and a limiter. Presets, per-device profiles and more effects follow; see the
> [roadmap](docs/roadmap.md).
> Once installed, the effect runs on music streams that go through the audio mixer (offloaded and
> direct outputs bypass software effects; see [risks](docs/risks.md)), and the app changes it
> live.

## Repository layout

| Path | Contents |
|---|---|
| `dsp-core/` | Pure C++20 DSP library (no Android dependencies), tests, benchmark and the `sp-cli` WAV tool |
| `protocol/` | The versioned parameter protocol shared by the effect and the app |
| `effect-lib/` | Android audio effect library (`libsoundpower.so`), its tests and a libFuzzer target |
| `magisk-module/` | The Magisk module: installer and boot scripts, SELinux rules, zip builder, script tests |
| `app/`, `android-core/` | The Android app (Kotlin, Jetpack Compose) and its modules: protocol, model, storage, root access, effect control |
| `tools/` | `sp-xmlpatch` (edits the effects XML at install time) and `sp-tone` (device test player) |
| `scripts/` | `adb-exec.sh` (runs cross-compiled tests on a device), `device-test.sh` (checks an installed module) |
| `docs/` | [Architecture](docs/architecture.md), [parameter protocol](docs/protocol.md), [risk register](docs/risks.md), [debugging](docs/debugging.md) |

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

## Installing the Magisk module

Requirements: a rooted device with Magisk 24 or newer, Android 9 or newer, an ARM CPU, and the
HIDL effects HAL (the AIDL effects HAL of some Android 14+ devices is not supported yet; the
installer checks).

```sh
magisk-module/build.sh             # builds both ABIs, writes out/soundpower-module-v<version>.zip
adb push out/soundpower-module-v*.zip /data/local/tmp/
adb shell su -c 'magisk --install-module /data/local/tmp/soundpower-module-v*.zip'
adb reboot
scripts/device-test.sh --tone build/android-arm64/tools/sp-tone/sp-tone
```

Installing from the Magisk app should work too: it runs the zip's `update-binary`, which was tested
from a root shell, not from the app itself. The installer patches a **copy** of every
`audio_effects*.xml` the device has, adds `libsoundpower.so` for each ABI the vendor partition
has, and Magisk mounts these over the originals at boot; the device's files are never modified.
The app can install the module too (it bundles the same zip). Without the app, the effect runs
with its defaults, or with a `state.bin` placed in `/data/vendor/soundpower/`.

If audio breaks, the module disables itself after repeated audio HAL crashes. See
[debugging](docs/debugging.md) for diagnostics, logs and manual recovery.

## Building the app

Requirements: a full JDK 17 or newer (21 is tested; a JRE without `javac` does not work), the
Android SDK with platform 37.2 (`sdkmanager "platforms;android-37.2"`), and the module zip, which
the app bundles. The app and the module take their version from `VERSION`.

```sh
magisk-module/build.sh                  # the app bundles out/soundpower-module-v<version>.zip
./gradlew test                          # JVM tests: golden vectors, model, storage, root scripts, app logic
./gradlew lintDebug assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch the app asks for root, offers to install the bundled module, and asks for a
reboot. Once the effect is loaded, it opens the equalizer. Changes reach the effect at once and
are saved to `state.bin`, so they apply from boot even if the app never runs again.

## Trying the DSP on WAV files

`sp-cli` runs files through the same processing chain the Android effect will use:

```sh
cli=build/host-release/dsp-core/cli/sp-cli

$cli gen sweep sweep.wav --seconds 5 --amp 0.9          # test signal
$cli process sweep.wav out.wav \
     --band lowshelf:80:6:0.7 --band peak:3000:-4:2.0    # EQ + limiter
$cli response --band peak:1000:6:1.0 --fs 48000         # EQ curve as CSV
$cli response --band peak:250:4:1:left \
     --band highshelf:6000:3:0.7:side                   # curve per channel
$cli bench                                              # CPU cost per block
```

Band types are `peak`, `lowshelf`, `highshelf`, `lowpass`, `highpass`, `notch` and `bandpass`; an
optional fifth field routes a band to `left`, `right`, `mid` or `side` (default `all`). Run
`sp-cli --help` for every option (preamp, auto-preamp, limiter, tone, balance, width, mono,
compressor, bypass, output format).

## License

SoundPower is free software, licensed under the [GNU General Public License v3.0](LICENSE).
Third-party components keep their own licenses; see `third_party/`.
