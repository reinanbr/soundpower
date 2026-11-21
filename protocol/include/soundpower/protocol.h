/*
 * SoundPower parameter protocol, version 1.0.
 *
 * Copyright (C) 2026 SoundPower contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This header is the single source of truth for the protocol between libsoundpower.so and its
 * controllers. docs/protocol.md describes it in prose. It is plain C so it can be included from
 * C and C++, and has no dependency on Android headers.
 *
 * The structs below document the wire layout and are size-checked. Wire buffers are only
 * guaranteed 4-byte alignment: (de)serialize field by field with memcpy, never by casting.
 * All multi-byte fields are little-endian.
 */
#ifndef SOUNDPOWER_PROTOCOL_H
#define SOUNDPOWER_PROTOCOL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Versions ------------------------------------------------------------------------------ */

#define SP_PROTOCOL_VERSION_MAJOR 1
#define SP_PROTOCOL_VERSION_MINOR 0

/* ---- Effect identity ----------------------------------------------------------------------- */

#define SP_EFFECT_NAME "SoundPower"
#define SP_EFFECT_IMPLEMENTOR "SoundPower contributors"
#define SP_LIBRARY_NAME "soundpower"
#define SP_LIBRARY_FILE "libsoundpower.so"
#define SP_LOG_TAG "SoundPower"

#define SP_EFFECT_TYPE_UUID_STR "69dc09f7-12a9-471d-89a1-603608c452c9"
#define SP_EFFECT_IMPL_UUID_STR "23e79ffb-ab65-4cd7-a768-b493ded3fabe"

/* Initializers in the field order of AOSP's effect_uuid_t:
 * { timeLow, timeMid, timeHiAndVersion, clockSeq, { node[0..5] } } */
#define SP_EFFECT_TYPE_UUID_INIT \
    {0x69dc09f7u, 0x12a9u, 0x471du, 0x89a1u, {0x60u, 0x36u, 0x08u, 0xc4u, 0x52u, 0xc9u}}
#define SP_EFFECT_IMPL_UUID_INIT \
    {0x23e79ffbu, 0xab65u, 0x4cd7u, 0xa768u, {0xb4u, 0x93u, 0xdeu, 0xd3u, 0xfau, 0xbeu}}

/* ---- Files --------------------------------------------------------------------------------- */

#define SP_DATA_DIR "/data/vendor/soundpower"
#define SP_STATE_FILE SP_DATA_DIR "/state.bin"
#define SP_IR_DIR SP_DATA_DIR "/ir"
#define SP_STATE_FILE_MAGIC "SPST"
#define SP_STATE_FILE_MAX_BYTES 65536u

/* ---- Limits -------------------------------------------------------------------------------- */

#define SP_MAX_BANDS 32
#define SP_MAX_MESSAGE_BYTES 1024
#define SP_MAX_INSTANCES 64 /* live effect instances per process */

#define SP_FREQ_MIN_HZ 10.0f
#define SP_FREQ_MAX_FS_RATIO 0.49f /* freq_hz is clamped to fs * this ratio */
#define SP_GAIN_MIN_DB (-30.0f)
#define SP_GAIN_MAX_DB 30.0f
#define SP_Q_MIN 0.05f
#define SP_Q_MAX 40.0f
#define SP_PREAMP_MIN_DB (-30.0f)
#define SP_PREAMP_MAX_DB 30.0f

#define SP_LIMITER_CEILING_MIN_DB (-24.0f)
#define SP_LIMITER_CEILING_MAX_DB 0.0f
#define SP_LIMITER_CEILING_DEFAULT_DB (-1.0f)
#define SP_LIMITER_LOOKAHEAD_MIN_MS 0.5f
#define SP_LIMITER_LOOKAHEAD_MAX_MS 10.0f
#define SP_LIMITER_LOOKAHEAD_DEFAULT_MS 1.5f
#define SP_LIMITER_RELEASE_MIN_MS 1.0f
#define SP_LIMITER_RELEASE_MAX_MS 1000.0f
#define SP_LIMITER_RELEASE_DEFAULT_MS 50.0f

/* ---- Parameter IDs ------------------------------------------------------------------------- */

enum sp_param_id {
    SP_PARAM_PROTOCOL_INFO = 0x0001, /* GET */
    SP_PARAM_STATUS = 0x0002,        /* GET */
    SP_PARAM_SPECTRUM = 0x0003,      /* GET, phase 6 */
    SP_PARAM_MASTER = 0x0100,        /* SET / GET */
    SP_PARAM_EQ_STATE = 0x0200,      /* SET / GET */
    SP_PARAM_LIMITER = 0x0300,       /* SET / GET */
    /* 0x1000 - 0x1FFF reserved for phase 6 modules */
};

/* ---- Capabilities (PROTOCOL_INFO.capabilities) --------------------------------------------- */

#define SP_CAP_PARAMETRIC_EQ (1u << 0)
#define SP_CAP_LIMITER (1u << 1)
#define SP_CAP_SPECTRUM (1u << 2)
#define SP_CAP_CONVOLVER (1u << 3)
#define SP_CAP_MULTIBAND_COMP (1u << 4)
#define SP_CAP_BASS (1u << 5)
#define SP_CAP_LOUDNESS (1u << 6)
#define SP_CAP_STEREO_WIDTH (1u << 7)
#define SP_CAP_CROSSFEED (1u << 8)

/* ---- Band types (values match sp::dsp::FilterType) ----------------------------------------- */

enum sp_band_type {
    SP_BAND_PEAKING = 0,
    SP_BAND_LOW_SHELF = 1,
    SP_BAND_HIGH_SHELF = 2,
    SP_BAND_LOW_PASS = 3,
    SP_BAND_HIGH_PASS = 4,
    SP_BAND_NOTCH = 5,
    SP_BAND_BAND_PASS = 6,
    SP_BAND_TYPE_COUNT = 7,
};

/* ---- Flags --------------------------------------------------------------------------------- */

#define SP_MASTER_FLAG_BYPASS (1u << 0)
#define SP_MASTER_FLAG_AUTO_PREAMP (1u << 1)
#define SP_MASTER_FLAGS_DEFAULT SP_MASTER_FLAG_AUTO_PREAMP

#define SP_STATUS_FLAG_BYPASSED (1u << 0)
#define SP_STATUS_FLAG_DUPLICATE_SESSION (1u << 1)
#define SP_STATUS_FLAG_NONFINITE_INPUT (1u << 2)

/* STATUS.format values: Android's audio_format_t codes for the formats the effect accepts. */
#define SP_FORMAT_PCM_16_BIT 0x1u
#define SP_FORMAT_PCM_FLOAT 0x5u

enum sp_instance_state {
    SP_STATE_UNINITIALIZED = 0,
    SP_STATE_INITIALIZED = 1,
    SP_STATE_ACTIVE = 2,
    SP_STATE_DISABLING = 3,
};

/* ---- Payload layouts ----------------------------------------------------------------------- */

typedef struct sp_protocol_info {
    uint16_t major;
    uint16_t minor;
    uint32_t lib_version; /* (major << 16) | (minor << 8) | patch */
    uint32_t max_bands;
    uint32_t capabilities;
} sp_protocol_info_t;

typedef struct sp_status {
    uint32_t sample_rate;
    uint32_t channels;
    uint32_t format; /* audio_format_t */
    uint32_t state;  /* enum sp_instance_state */
    float cpu_avg_percent;
    float cpu_peak_percent;
    uint32_t instances;
    int32_t session_id;
    uint32_t flags; /* SP_STATUS_FLAG_* */
    float limiter_gr_db;
    uint64_t frames_processed;
} sp_status_t;

typedef struct sp_master {
    uint32_t flags; /* SP_MASTER_FLAG_* */
    float preamp_db;
} sp_master_t;

typedef struct sp_band {
    uint8_t type; /* enum sp_band_type */
    uint8_t enabled;
    uint16_t reserved;
    float freq_hz;
    float gain_db;
    float q;
} sp_band_t;

/* On the wire, EQ_STATE is `count` followed by exactly `count` bands (4 + 16 * count bytes). */
typedef struct sp_eq_state {
    uint32_t count;
    sp_band_t bands[SP_MAX_BANDS];
} sp_eq_state_t;

typedef struct sp_limiter {
    float ceiling_db;
    float lookahead_ms;
    float release_ms;
} sp_limiter_t;

#define SP_PROTOCOL_INFO_SIZE 16u
#define SP_STATUS_SIZE 48u
#define SP_MASTER_SIZE 8u
#define SP_BAND_SIZE 16u
#define SP_EQ_STATE_HEADER_SIZE 4u
#define SP_LIMITER_SIZE 12u
#define SP_EQ_STATE_SIZE(count) (SP_EQ_STATE_HEADER_SIZE + SP_BAND_SIZE * (count))

#ifdef __cplusplus
} /* extern "C" */
#define SP_STATIC_ASSERT(cond, msg) static_assert(cond, msg)
#else
#define SP_STATIC_ASSERT(cond, msg) _Static_assert(cond, msg)
#endif

SP_STATIC_ASSERT(sizeof(sp_protocol_info_t) == SP_PROTOCOL_INFO_SIZE, "PROTOCOL_INFO layout");
SP_STATIC_ASSERT(sizeof(sp_status_t) == SP_STATUS_SIZE, "STATUS layout");
SP_STATIC_ASSERT(sizeof(sp_master_t) == SP_MASTER_SIZE, "MASTER layout");
SP_STATIC_ASSERT(sizeof(sp_band_t) == SP_BAND_SIZE, "band layout");
SP_STATIC_ASSERT(sizeof(sp_limiter_t) == SP_LIMITER_SIZE, "LIMITER layout");
SP_STATIC_ASSERT(SP_EQ_STATE_SIZE(SP_MAX_BANDS) <= SP_MAX_MESSAGE_BYTES, "EQ_STATE fits a message");

#endif /* SOUNDPOWER_PROTOCOL_H */
