# SoundPower parameter protocol — version 1.2

Version 1.2 adds direct volume control: a `MASTER` flag, a capability bit and two `STATUS` flags.
Version 1.1 added `TONE`, `STEREO` and `COMPRESSOR`, the `MASTER` flag that turns the limiter off,
and the matching capability bits. A controller sends the new IDs only when `PROTOCOL_INFO`
reports their capability; an older effect skips their `state.bin` entries and ignores the new
flags.

This document is the contract between `libsoundpower.so` and anything that controls it (the app, tests,
the host harness). Its machine-readable counterpart is
[`protocol/include/soundpower/protocol.h`](../protocol/include/soundpower/protocol.h). If the two
disagree, the header is authoritative and this document has a bug.

## Effect identity

| | UUID |
|---|---|
| Effect **type** | `69dc09f7-12a9-471d-89a1-603608c452c9` |
| Effect **implementation** | `23e79ffb-ab65-4cd7-a768-b493ded3fabe` |

Both are random (version 4) UUIDs and never change across releases. The library name is `soundpower`,
the shared object is `libsoundpower.so`, and the log tag is `SoundPower`.

Descriptor flags: `EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_VOLUME_IND |
EFFECT_FLAG_DEVICE_IND`, with `EFFECT_FLAG_VOLUME_CTRL` in place of `EFFECT_FLAG_VOLUME_IND`
when direct volume control is on (see `MASTER`). The effect does not support offload.

## Direct volume control (1.2)

With `MASTER` bit 3 set when the library is loaded, the descriptor claims
`EFFECT_FLAG_VOLUME_CTRL`. AudioFlinger then sends `EFFECT_CMD_SET_VOLUME` with a reply buffer
to the chain's volume controller (the last enabled effect with that flag in the session's
chain). SoundPower answers with unity (`1 << 24` for both channels), so the mixer feeds the
chain at full scale, and applies the stream volume itself:

- After the compressor and just before the limiter, ramped over 10 ms, so the limiter only works
  when the output itself would go over its ceiling.
- With auto-preamp on, the volume stage gives back the attenuation auto-preamp took off the EQ's
  peak, as far as the volume leaves room for it. Its gain is `v · min(cut, 1 / v_max)` per
  channel, where `cut` is auto-preamp's attenuation as a gain ≥ 1 and `v_max` the louder
  channel's volume: the EQ boost is heard in full up to the volume where it would clip, and
  above that the peak stays where auto-preamp alone puts it.
- The dry signal (A/B bypass, the enable and disable fades, the duplicate-session pass-through,
  and the last buffer before `process()` returns `-ENODATA`) gets the plain volume, so no path
  leaves at the mixer's full scale.

If another effect after SoundPower is the controller, SoundPower gets `SET_VOLUME` without a
reply and applies nothing (`STATUS` bit 4 stays clear). Sessions with two tracks at different
volumes (a player crossfading inside one session) are mixed at unity, and the chain applies the
last volume it received to both.

The effects factory reads the descriptor once, when it loads the library, so the library reads
bit 3 from `state.bin` at that moment (on its first descriptor query or instance creation) and
keeps the answer for the life of the process: changing the flag takes effect after the audio
HAL restarts (a reboot, or a root `setprop ctl.restart` of the HAL service). `STATUS` bit 3
reports what the running library decided. With the flag off the effect behaves exactly as in
1.1.

## Transport

Parameters travel in the standard Android `effect_param_t` structure, through
`EFFECT_CMD_SET_PARAM` / `EFFECT_CMD_GET_PARAM` (from the app: the hidden
`AudioEffect.setParameter(byte[], byte[])` / `getParameter(byte[], byte[])`).

```
effect_param_t:  int32 status | uint32 psize | uint32 vsize | param[psize] | pad to 4 | value[vsize]
```

- **Param** is `uint32 id` (`psize = 4`), or `uint32 id, uint32 index` (`psize = 8`) for indexed items.
  No v1 parameter is indexed. The indexed form is reserved.
- **Value** is a packed little-endian byte sequence with the layout given below. Implementations must
  (de)serialize with `memcpy`, field by field. They must never cast the buffer to a struct, because
  `effect_param_t` buffers are only guaranteed 4-byte alignment.
- Every message is at most 1 KiB.
- Large data (impulse responses) never goes through `SET_PARAM`. The app copies the file into
  `/data/vendor/soundpower/ir/` as root, then sends its relative path (phase 6).

## Versioning rules

- `PROTOCOL_INFO` reports `major.minor`. A controller **must not** send `SET_PARAM` if the major version
  differs from its own. The app then shows "update the module" (or "update the app").
- A minor version may only **append** fields to existing payloads or **add** new IDs.
- On `SET_PARAM`, the effect accepts `vsize >= ` the size it knows and ignores trailing bytes. This
  lets a newer app talk to an older effect. A smaller `vsize` is an error.
- On `GET_PARAM`, the effect writes the full payload it knows and sets `vsize` accordingly. If the
  caller's `vsize` (the space it reserved for the value) is too small, `status` is `-EINVAL` and
  `vsize` is 0.

### Command framing details

- `SET_PARAM`: the command must hold the header, the padded parameter and `vsize` value bytes, and
  `psize`/`vsize` are each at most 1 KiB; otherwise the command itself fails with `-EINVAL`. The
  reply is one `int32` status. `psize` other than 4 is well-formed but has no v1 parameters, so the
  status is `-EINVAL`.
- `GET_PARAM`: the command is the header plus the parameter. The reply buffer may be the **same
  memory** as the command buffer (the framework's `AudioEffect::getParameter()` and the HIDL wrapper
  do this), and the effect handles that. The reply is the header (with `status` and `vsize`
  filled in), the parameter echoed back, padding, then the value; the reply size is
  `12 + pad4(psize) + vsize`.
- Each `SET_PARAM` is applied all-or-nothing: a malformed payload changes nothing.

## Status codes

`effect_param_t.status` and the `int32` reply of `SET_PARAM` use Android `status_t` values.

| Value | Meaning |
|---|---|
| `0` | OK |
| `-22` (`-EINVAL`) | Unknown ID, malformed sizes, or wrong direction (`GET` on a write-only ID or vice versa) |

Out-of-range numeric values are **clamped**, not rejected. `GET_PARAM` returns the clamped values.

## Parameters

| ID | Name | Direction | Payload size |
|---|---|---|---|
| `0x0001` | `PROTOCOL_INFO` | GET | 16 |
| `0x0002` | `STATUS` | GET | 48 |
| `0x0003` | `SPECTRUM` | GET (phase 6) | 4 + 4·n |
| `0x0100` | `MASTER` | SET / GET | 8 |
| `0x0200` | `EQ_STATE` | SET / GET | 4 + 16·count |
| `0x0300` | `LIMITER` | SET / GET | 12 |
| `0x0400` | `TONE` (1.1) | SET / GET | 20 |
| `0x0500` | `STEREO` (1.1) | SET / GET | 12 |
| `0x0600` | `COMPRESSOR` (1.1) | SET / GET | 28 |
| `0x1000`–`0x1FFF` | reserved for later modules (convolver, multiband compressor, loudness, crossfeed, psychoacoustic bass) | | |

### `0x0001 PROTOCOL_INFO` (GET)

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | `major` (1) |
| 2 | u16 | `minor` (2) |
| 4 | u32 | `lib_version`: `(major << 16) \| (minor << 8) \| patch` of `VERSION` |
| 8 | u32 | `max_bands` (32) |
| 12 | u32 | `capabilities`: bitmask, see `SP_CAP_*` |

Capabilities: bit 0 parametric EQ, 1 limiter, 2 spectrum, 3 convolver, 4 multiband compressor,
5 psychoacoustic bass, 6 loudness, 7 (reserved, was "width"), 8 crossfeed, 9 `TONE`, 10 `STEREO`,
11 `COMPRESSOR`, 12 the limiter-off flag, 13 band channels, 14 direct volume control (1.2). A 1.2
library reports bits 0, 1 and 9–14.

### `0x0002 STATUS` (GET)

Describes the instance that answers, plus process-wide counters.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | `sample_rate` (Hz, 0 before `SET_CONFIG`) |
| 4 | u32 | `channels` |
| 8 | u32 | `format`: Android `audio_format_t` (`0x1` PCM 16-bit, `0x5` PCM float) |
| 12 | u32 | `state`: 0 uninitialized, 1 initialized, 2 active, 3 disabling |
| 16 | f32 | `cpu_avg_percent`: `process()` time ÷ buffer duration, exponential average |
| 20 | f32 | `cpu_peak_percent`: maximum since last read |
| 24 | u32 | `instances`: live instances in the process (at most 64; `create_effect` fails with `-ENOMEM` beyond that) |
| 28 | i32 | `session_id` |
| 32 | u32 | `flags`: bit 0 bypassed, bit 1 duplicate-session guard active, bit 2 non-finite input detected, bit 3 the library claims volume control (1.2), bit 4 an enabled instance in the process applies its stream's volume (1.2) |
| 36 | f32 | `limiter_gr_db`: current limiter gain reduction (≤ 0) |
| 40 | u64 | `frames_processed` |

### `0x0003 SPECTRUM` (GET, phase 6)

`u32 bin_count`, then `bin_count` × `f32` magnitudes in dBFS, log-spaced from 20 Hz to
min(20 kHz, fs/2). The FFT runs inside the `GET_PARAM` handler, on a binder thread, over the latest
samples from a lock-free ring buffer that `process()` fills.

### `0x0100 MASTER` (SET / GET)

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | u32 | `flags`: bit 0 bypass (A/B), bit 1 auto-preamp, bit 2 limiter off (1.1), bit 3 direct volume control (1.2) | default `0x2` |
| 4 | f32 | `preamp_db` | −30…+30, default 0 |

With auto-preamp on, the effect adds `−max(0, peak of the EQ curve in dB)` to `preamp_db`, so the
EQ alone cannot push a full-scale signal over 0 dBFS. The tone shelves count as part of the EQ
curve.

With bit 2 set the limiter stops reducing gain; its gain recovers with the release time, and its
delay stays, so the latency does not change. A 1.0 effect ignores the bit and keeps limiting.

Bit 3 requests direct volume control. It is stored and returned like the other flags, but it
only acts when the library is loaded (see [Direct volume control](#direct-volume-control-12)).
Older effects ignore it.

### `0x0200 EQ_STATE` (SET / GET)

Replaces the whole EQ atomically. The new curve crossfades in over ~10 ms.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | `count` (0…32) |
| 4 + 16·i | band | band `i` |

Each band is 16 bytes:

| Offset | Type | Field | Range |
|---|---|---|---|
| 0 | u8 | `type` | see below |
| 1 | u8 | `enabled` | 0 or 1 (any non-zero value reads as 1) |
| 2 | u8 | `channel` (1.1) | see below; a 1.0 library ignores it |
| 3 | u8 | reserved | written as 0, ignored on read |
| 4 | f32 | `freq_hz` | 10 … 0.49·fs (clamped per instance) |
| 8 | f32 | `gain_db` | −30 … +30 (ignored by pass, notch and band-pass types) |
| 12 | f32 | `q` | 0.05 … 40 |

Band types (RBJ Audio EQ Cookbook designs):

| Value | Type |
|---|---|
| 0 | peaking |
| 1 | low shelf |
| 2 | high shelf |
| 3 | low pass (2nd order) |
| 4 | high pass (2nd order) |
| 5 | notch |
| 6 | band pass (0 dB peak) |

Band channels (1.1; before, the byte was reserved and written as 0, so 1.0 data reads as "all"):

| Value | Channels the band filters |
|---|---|
| 0 | all |
| 1 | left |
| 2 | right |
| 3 | mid, (L+R)/2 |
| 4 | side, (L−R)/2 |

Bands run in order. A mid or side band (or a run of them) converts the first two channels to mid
and side, filters one of them and converts back, so it changes width-related content only. On a
mono stream only "all" and "mid" bands apply; on more than two channels the routed bands use the
first two. A value above 4 makes the whole message `-EINVAL`. A 1.0 library applies every band
to all channels, so the app sends routed bands only to an effect reporting bit 13 and disables
them otherwise. Auto-preamp takes, at each frequency, all + max(left, right) + max(mid, side).

### `0x0300 LIMITER` (SET / GET)

The limiter is on unless `MASTER` bit 2 turns it off (1.1).

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | f32 | `ceiling_db` | −24 … 0, default −1 |
| 4 | f32 | `lookahead_ms` | 0.5 … 10, default 1.5 |
| 8 | f32 | `release_ms` | 1 … 1000, default 50 |

Changing `lookahead_ms` changes the effect's latency. The output fades out and back in over about 2 ms
around the change.

### `0x0400 TONE` (SET / GET, 1.1)

Bass and treble: a low shelf and a high shelf (Q 0.7071, shelf slope 1) after the EQ bands. They
are part of the EQ curve: they crossfade with it and count for auto-preamp.

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | u32 | `flags`: bit 0 enabled | default `0x1` |
| 4 | f32 | `bass_db` | −30 … +30, default 0 |
| 8 | f32 | `bass_freq_hz` | 10 Hz … 0.49 fs, default 100 |
| 12 | f32 | `treble_db` | −30 … +30, default 0 |
| 16 | f32 | `treble_freq_hz` | 10 Hz … 0.49 fs, default 8000 |

### `0x0500 STEREO` (SET / GET, 1.1)

After the EQ, for stereo streams only. With M = (L + R) / 2 and S = (L − R) / 2 the stage outputs
L′ = M + w·S and R′ = M − w·S (w = 0 when mono is set), then balance attenuates the opposite
channel linearly (balance +0.5 halves the left channel, +1 silences it). Changes ramp over 20 ms.

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | u32 | `flags`: bit 0 mono downmix | default `0x0` |
| 4 | f32 | `balance` | −1 (left only) … +1 (right only), default 0 |
| 8 | f32 | `width` | 0 (mono) … 2, default 1 (unchanged) |

### `0x0600 COMPRESSOR` (SET / GET, 1.1)

Feed-forward, stereo-linked downward compressor before the limiter. The detector is the largest
absolute sample of the frame; the static curve has a quadratic soft knee of `knee_db` centred on
the threshold; the gain moves toward its target in dB with the attack time when it falls and the
release time when it rises. Turning it off releases to 0 dB the same way.

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | u32 | `flags`: bit 0 enabled | default `0x0` |
| 4 | f32 | `threshold_db` | −60 … 0, default −18 |
| 8 | f32 | `ratio` | 1 … 20, default 3 |
| 12 | f32 | `attack_ms` | 0.1 … 200, default 10 |
| 16 | f32 | `release_ms` | 5 … 2000, default 150 |
| 20 | f32 | `knee_db` | 0 … 24, default 6 |
| 24 | f32 | `makeup_db` | 0 … 24, default 0 |

## Persisted state file (`state.bin`)

Path: `/data/vendor/soundpower/state.bin`, written by the app as root, read-only for the HAL.
The file uses the same payloads as `SET_PARAM`:

```
header:  char magic[4] = "SPST" | u16 major | u16 minor | u32 entry_count
entry:   u32 id | u32 size | payload[size] | pad to 4
```

The library reads the file whenever an instance is created and the file's inode, size or
modification time differs from the last read (so the app can update it at any time, and the next
stream picks it up). Rules:

- The file is at most 64 KiB and each entry at most 1 KiB.
- Entries are decoded exactly like `SET_PARAM` values, in file order.
- Unknown IDs are skipped (a newer minor version may add some).
- The file is applied **all-or-nothing**: a bad header, a different major version, a truncated
  entry, trailing bytes or an invalid payload for a known ID leave the current parameters untouched.
  The reason is logged under the `SoundPower` tag.
- A missing file is not an error; the effect keeps its defaults (or the last parameters set).

## Golden vectors

`protocol/vectors/*.hex` holds reference encodings, one message per file (`#` comment lines, then hex
bytes). They are generated by `protocol/vectors/generate.py`, which packs the values with Python's
`struct` module independently of the C++ code, using only exactly-representable floats.

| File | Contents |
|---|---|
| `master_default`, `master_bypass` | `MASTER` defaults; bypass on, auto-preamp off, −6.5 dB preamp |
| `eq_state_empty`, `eq_state_3band` | `EQ_STATE` with 0 bands; low shelf, peaking and a disabled high pass |
| `eq_state_channels` | `EQ_STATE` 1.1 with left, right, side, mid and all bands |
| `limiter_default`, `limiter_custom` | `LIMITER` defaults; −3 dB / 5 ms / 250 ms |
| `master_limiter_off` | `MASTER` (1.1): auto-preamp on, limiter off, +3.25 dB preamp |
| `master_direct_volume` | `MASTER` (1.2): auto-preamp on, direct volume control on, −2.5 dB preamp |
| `tone_default`, `tone_custom` | `TONE` defaults; off, bass +7.5 dB at 62.5 Hz, treble −3 dB at 12 kHz |
| `stereo_default`, `stereo_custom` | `STEREO` defaults; mono, balance −0.25, width 1.5 |
| `compressor_default`, `compressor_custom` | `COMPRESSOR` defaults; on, −24 dB, 4.5:1, 2.5 ms, 400 ms, hard knee, +6 dB |
| `status_example` | A `STATUS` reply |
| `state_file_example` | `state.bin` with `MASTER`, `EQ_STATE` and `LIMITER` |
| `state_file_unknown_entry` | The same from a newer minor version, with an extra unknown entry |
| `state_file_v1_1` | `state.bin` 1.1: the six settable IDs, as the 1.1 library writes them |
| `state_file_v1_2` | `state.bin` 1.2: the same with direct volume control on, as the 1.2 library writes them |

The effect-lib tests embed these files at configure time and check both directions (encode and
decode). The Kotlin tests (`android-core:protocol`) read the same files and must produce identical
bytes.
