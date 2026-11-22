# Debugging SoundPower on a device

Most checks need root (`adb shell su -c ...`). Nothing below changes the device unless it says so.

## Quick check

```sh
scripts/device-test.sh --tone build/android-arm64/tools/sp-tone/sp-tone
```

It reports the effects HAL type and bitness, whether the module is enabled, whether the patched
configurations are mounted and the library is loaded, the data directory label, SELinux denials,
and (with `--tone`) whether a SoundPower instance attaches to a media stream and processes it.
`sp-tone` plays a quiet (−45 dBFS) 1 kHz tone for 4 seconds; build it with the `android-arm64` or
`android-armv7` preset (`cmake --build --preset android-arm64 --target sp-tone`).

The app's own diagnostics screen (menu, *Status*) shows most of this live, judges it and shares a
report that includes everything below. On the device itself, the same information comes from the
module's diagnostics script:

```sh
su -c sh /data/adb/modules/soundpower/diag.sh
```

It also runs once at every boot; the output is in `/data/adb/modules/soundpower/log/diag.txt`.

## What to look at

| Question | Command |
|---|---|
| Which effects HAL? | `lshal -i -r -m -p \| grep IEffectsFactory` (HIDL, with bitness and binary) or `service list \| grep audio.effect` (AIDL) |
| Which configuration did the parsers read? | The effects factory (HAL) and AudioPolicy (audioserver) may read **different** files. `strings` on `/vendor/lib*/libeffectsconfig.so` and `/system/lib*/libeffectsconfig.so` shows the file names each one looks for. |
| Is the patched copy mounted? | `grep -c soundpower /vendor/etc/audio_effects*.xml` |
| Is the library loaded? | `grep -l libsoundpower /proc/[0-9]*/maps` (after a restart of the HAL service, `lshal` may stop showing its pid; the mapping is the reliable test) |
| Is the effect attached to a stream? | `dumpsys media.audio_flinger`: each live instance prints a `SoundPower x.y.z ...: session N io M, active, ...` line (the effect's `EFFECT_CMD_DUMP`). |
| Effect log | `logcat -s SoundPower` (instance creation, configuration, `state.bin` loading) |
| SELinux | `dmesg \| grep avc` and `logcat -b all \| grep avc`, looking for `soundpower` or `soundfx` |
| Saved state | `ls -lZ /data/vendor/soundpower` (directory and files must be `soundpower_data_file`). The label alone is not proof: check that the policy defines the type with `printf %s u:object_r:soundpower_data_file:s0 > /sys/fs/selinux/context` (fails if not; `diag.sh` prints `policy: soundpower_data_file loaded`). Otherwise the HAL sees the files as `unlabeled` and is denied. |

## Module logs

`/data/adb/modules/soundpower/log/boot.log` records, for every boot:

- `post-fs-data`: the module version, whether the SELinux rules had to be loaded late
  (`sepolicy: rules loaded at post-fs-data`, normal after installing from the Magisk app), bind
  mounts;
- the watchdog: the effects HAL PID, restarts seen in the first 3 minutes after boot, and, if it
  disabled the module, why.

The `post-fs-data` line may carry a wrong date: some devices only set the clock later in the boot.
`log/tombstones.txt` lists the tombstones that existed when the boot started; the watchdog only
looks at the others.

A healthy boot on the test device looks like this:

```
2018-01-20 09:11:31 post-fs-data: version=v0.1.0
2026-11-21 09:51:26 watchdog: effects HAL pid 1200
2026-11-21 09:55:26 watchdog: no problem in 180s (0 restarts)
```

and a media stream gets an instance (from `dumpsys media.audio_flinger`):

```
SoundPower 0.2.0 (protocol 1.2): session 94321 io 13, active, 48000 Hz, 2 ch, format 0x5,
cpu avg 10.88% peak 15.93%, limiter 0.00 dB, 112320 frames, flags 0x18, 1 instances,
volume -14.4/-14.4 dB applied (stage -14.4 dB), peak in -40.0 out -66.2 dBFS
```

`flags` are the `STATUS` flags: bit 0 master bypass, bit 1 duplicate session, bit 2 non-finite
input, bit 3 direct volume control claimed, bit 4 a stream's volume applied by SoundPower. The
`volume` part (since 1.2) is the last stream volume per channel, `applied` when SoundPower applies
it (direct volume control) or `by mixer` when the mixer already did, and the gain of the chain's
volume stage. `peak in` / `out` are the largest input and output samples since the previous
dump (`-999.0` for silence). With direct volume control, `peak in` does not move with the
volume: the mixer feeds the chain at full scale. The effect reads `state.bin` whenever an instance is created
and the file has changed (inode, size or modification time), so a new file takes effect at the
next stream start; `logcat -s SoundPower` shows `loaded /data/vendor/soundpower/state.bin: N
entries applied`. Deleting the file keeps the current settings until the audio HAL restarts.

## When audio stops working

A crash inside an audio effect takes down the whole audio HAL, so the device has no sound until
the HAL restarts (and it crashes again if the cause remains).

1. **The watchdog.** If the effects HAL restarts 3 times within 3 minutes of boot, or a tombstone
   shows a crash inside `libsoundpower.so`, the module creates its `disable` file. The next boot
   runs without it. `log/boot.log` says why.
2. **By hand.** `adb shell su -c touch /data/adb/modules/soundpower/disable`, then reboot.
   `remove` instead of `disable` uninstalls the module at the next boot.
3. **Without adb or root access.** Magisk's safe mode disables every module for one boot. On most
   devices: hold volume down from the boot logo until the lock screen.
4. **Report it.** Attach `log/boot.log`, `log/diag.txt` and the newest file in `/data/tombstones/`
   that mentions `libsoundpower.so`.

## Removing the module

From the Magisk app, or `magisk --remove-modules` (all modules), or
`touch /data/adb/modules/soundpower/remove` and reboot. `uninstall.sh` then deletes
`/data/vendor/soundpower`. The device's own files were never modified: the module only mounts
patched copies over them.
