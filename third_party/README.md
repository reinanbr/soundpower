# Third-party components

Every component here is pinned and kept unmodified. Each one keeps its own license, and all of them
are compatible with GPL-3.0.

| Component | Version | Source | License | Used by |
|---|---|---|---|---|
| dr_wav (`dr_wav/dr_wav.h`) | 0.14.6, commit `35b5c392cc15456736c440a68dbe073178bbffe0` | https://github.com/mackron/dr_libs | Public domain (Unlicense) or MIT-0 | `sp-cli`; IR loading in phase 6 |
| GoogleTest | 1.17.0, fetched at configure time, SHA-256 `65fab701d9829d38cb77c14acdc431d2108bfdbf8979e40eb8ae567edf10b27c` | https://github.com/google/googletest | BSD-3-Clause | tests only, not vendored |

SHA-256 of `dr_wav/dr_wav.h`: `fad60f1e360d6de726efc1b9a7985610c89c551719e3f110e8b992f0e1f308cd`.

`dr_wav/dr_wav_impl.c` is SoundPower's own one-line translation unit that instantiates the library.
