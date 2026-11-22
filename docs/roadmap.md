# Roadmap

Phases 1–5 are done: architecture and protocol, `dsp-core`, `effect-lib`, the Magisk module and a
minimal app (setup and a 10-band EQ). The specification then grew to **functional parity with a
well-known Android equalizer app**: every tool and mechanism, the same information architecture and
interaction model, plus mechanisms beyond it. The visual identity stays 100% original: no copied
artwork, icons, colours, component styling, strings or branding, and no reference to that app
anywhere in the UI or store listing.

## Phases

| Phase | Scope |
|---|---|
| 6 | **Main equalizer and its tools.** `dsp-core`: bass and treble shelves with configurable frequency, balance, stereo width and mono, a compressor, limiter on/off, per-band channel routing (both, left, right, mid, side). Protocol 1.1 (`TONE`, `STEREO`, `COMPRESSOR`, limiter-off flag, band channel field, capability bits) in C++ and Kotlin with golden vectors. App model: fixed and custom (5–32) band layouts with start/end frequencies, bands overlap, ±15 dB, parametric bands (type, frequency, gain, Q, channel, colour, lock), graphic and parametric data side by side, the response curve computed outside the UI. App UI: three-tab navigation (equalizer, tone and dynamics, menu); equalizer page with a preamp column beside the band sliders (graphic) or band cards (parametric), a response strip with a mini-map of the visible bands, quick toggles for the equalizer and the limiter, the preset name with save and reset; tone-and-dynamics page with knobs for bass, treble, compressor, limiter, balance, width and mono; long-press any value to type it; motion and haptics throughout; light and dark themes. |
| 6b | **Mode without root** (done, beyond the original scope). A choice on first launch between the root engine and Android's Dynamics Processing effect, held by a foreground service on the global mix or on announced player sessions. Everything that does not mix channels works (graphic and parametric bands with L/R routing, tone, preamp, balance, compressor, a level limiter); mid/side, width and mono need root. See [architecture](architecture.md#mode-without-root). |
| 7 | **Presets and devices** (done; see [architecture](architecture.md#presets-and-outputs)). Cast is left out: audio sent to a cast device is played there, outside this phone's effects. Preset sheet: search, filters (user, built-in, AutoEQ; graphic, parametric; assigned to a device), a curve thumbnail per preset, the current one checked, lock, autosave, share/import/export as JSON from a long press. AutoEQ: import, a bundled database with the measurement source per entry (shown under the name), suggestions when headphones connect. Save dialog that assigns the preset to outputs: speaker, wired headset, Bluetooth (any or a named device), USB DAC, cast; named devices override generic ones. A foreground service switches presets on output changes. Settings export/import including assignments. |
| 8 | **Players, direct volume control and diagnostics** (done except direct volume control per player; see [players](architecture.md#players) and [diagnostics](architecture.md#diagnostics)). Detected-player chip on the equalizer page (which apps are being equalized), known-players list, per-session fallback (`ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`), an advanced player-tracking mode for players that do not announce their sessions (needs extra permissions, explained in the app). Direct volume control: `EFFECT_FLAG_VOLUME_CTRL` with `EFFECT_CMD_SET_VOLUME` applied in the chain, a volume knob on the dynamics page, toggleable globally and per player (verify framework behaviour on the test device first). Diagnostics and an "equalizer does not work" troubleshooting page. |
| 9 | **Visualization and skins.** Real-time spectrum in the response strip, MilkDrop `.milk` presets through projectM (LGPL, separate shared library) as a full-screen background behind the controls, with faded-controls opacity, automatic preset rotation (duration setting) and a random-preset button; waveform and spectrum styles; an open, documented skin format with the light and dark skins as the reference; third-party preset packs. |
| 10 | **System integration.** Now-playing bar (track, artist, player) through a notification listener, media-style notification with on/off and preset switching, Quick Settings tile (bypass), home-screen widget (presets), auto-resume on headset/Bluetooth connect, volume-key controls (resume, pause, track change) with the required permissions explained, in-app language choice, settings search, tablet and landscape layouts (two panes). |
| 11 | **Beyond parity: sound.** Reverb with room presets, convolution/IR, multiband compressor (and the compressor crossover frequency below), loudness compensation (ISO 226), crossfeed, psychoacoustic bass, dynamic EQ bands (gain that follows the signal level), exciter, per-channel delay for car and speaker alignment, loudness normalization across apps. |
| 12 | **Beyond parity: tools.** On-device AutoEQ fitting to any target curve (Harman and user targets), headphone/room correction from a microphone sweep, hearing personalization from a simple audiogram test, A/B compare with level matching, undo/redo history, LUFS/true-peak meters and a phase scope, an intent API for automation apps. |
| 13 | **Documentation.** Build guide, CONTRIBUTING, per-vendor troubleshooting. |

Each phase ends with a report (done / how to test / pending) and waits for validation.

## Not possible system-wide

- **Tempo / speed change.** A post-processing effect must return as many frames as it receives;
  changing the playback rate needs the player's cooperation.
- **Player-only mechanisms** (gapless, crossfade between tracks, ReplayGain from tags, the
  decoder's resampler): they belong to a music player, not to an effect on the mixed stream.

## Interpretations to confirm

- The compressor in the reference shows a **Frequency** knob next to attack, release, ratio,
  threshold and gain. SoundPower reads it as a crossover: only the content below that frequency
  is compressed (20 kHz = the whole signal). It arrives in phase 11 with the multiband
  compressor, which shares the crossover filters.
