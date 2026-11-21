# Third-party components

Every component here is pinned and kept unmodified. Each one keeps its own license, and all of them
are compatible with GPL-3.0.

| Component | Version | Source | License | Used by |
|---|---|---|---|---|
| dr_wav (`dr_wav/dr_wav.h`) | 0.14.6, commit `35b5c392cc15456736c440a68dbe073178bbffe0` | https://github.com/mackron/dr_libs | Public domain (Unlicense) or MIT-0 | `sp-cli`; IR loading in phase 6 |
| AOSP audio effect headers (`aosp-headers/include`) | tag `android-12.1.0_r27` | https://android.googlesource.com (`hardware/libhardware`, `system/media`, `system/core`) | Apache-2.0 (see `aosp-headers/NOTICE`) | `effect-lib` (the Android ABI it implements) |
| tinyxml2 (`tinyxml2/tinyxml2.{h,cpp}`) | 11.0.0, commit `9148bdf719e997d1f474be6bcc7943881046dba1` | https://github.com/leethomason/tinyxml2 | zlib (`tinyxml2/LICENSE`) | `sp-xmlpatch` (Magisk module installer) |
| GoogleTest | 1.17.0, fetched at configure time, SHA-256 `65fab701d9829d38cb77c14acdc431d2108bfdbf8979e40eb8ae567edf10b27c` | https://github.com/google/googletest | BSD-3-Clause | tests only, not vendored |

SHA-256 of `dr_wav/dr_wav.h`: `fad60f1e360d6de726efc1b9a7985610c89c551719e3f110e8b992f0e1f308cd`.

SHA-256 of tinyxml2 (from the `11.0.0` release tarball, whose own SHA-256 is
`5556deb5081fb246ee92afae73efd943c889cef0cafea92b0b82422d6a18f289`):

```
0f3c6e5a91b6d65caca3cc7239926455f21e488cff80ac3d27c74f5262fd2602  tinyxml2/tinyxml2.h
95f64e4cb06c9e147d5b12e8860c88898944858dfbc38fed92ebf918f1436554  tinyxml2/tinyxml2.cpp
9332252e9b9e46db8285d4a3f0bf25f139bf1dca6781b956d57f2302efca6432  tinyxml2/LICENSE (LICENSE.txt upstream)
```

SHA-256 of the AOSP headers (paths relative to `aosp-headers/include`):

```
98cc23dc740cf827c27804f3f221abe7842c5c157cbb5f7a579294fe0c1b9dcd  cutils/bitops.h
35465295c44521ee7e548bb73c4980d0641d670651328f2002b56b5f5f5d1e81  hardware/audio_effect.h
8297dd0b2a524a2dae2a1f2d67db06f47913f68673155e30370afe5cca57fc3c  system/audio-base.h
b48ae642df444caf532509072ec32f2f0c60d58e57b5e20102022a2b951ac11c  system/audio-base-utils.h
42cf7549b19fe820aeea1428403910a8e0e586bc542c3e3c5b756f98a9288aab  system/audio_common-base.h
0dc8d6260e9cad4e2b26a06806a5b38732ad45bcf5963aa7d80359ea9dd9702e  system/audio_effect-base.h
4279ef33d0d0cc29f38c78ef4bc41affd1d0937f6f7d2de56ddc367c549fae01  system/audio_effect.h
4e7baebe0224c8d43de556f102dbc67436a6608fe3a9acf6a883371176ea97f2  system/audio.h
7dd6e95f42a411131389aa54b0da4be336859e99eab3ac1564acccd7d969842f  system/audio-hal-enums.h
```

The effect library ABI (`audio_effect_library_t` 3.0, effect control interface 2.0) has not changed
since Android 5, so these headers describe the same structures on every supported device. GCC
rejects `system/audio.h` in C++ mode (it overloads `static inline` functions inside `extern "C"`);
Clang and the NDK accept it, so code that includes these headers is built with Clang only.

`dr_wav/dr_wav_impl.c` is SoundPower's own one-line translation unit that instantiates the library.
