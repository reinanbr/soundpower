# shellcheck shell=sh disable=SC2034 # the variables are used by the scripts that source this
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Shared by the SoundPower module scripts. Every device path is prefixed with $SP_ROOT, which is
# empty on a device and points at a fake device tree in the host tests (magisk-module/tests).

SP_ROOT=${SP_ROOT:-}
SP_LIB=libsoundpower.so
SP_DATA_DIR=$SP_ROOT/data/vendor/soundpower
SP_DATA_CONTEXT=u:object_r:soundpower_data_file:s0

# Audio effects XML files that an effects parser may read: the effects factory in the audio HAL
# (vendor) and AudioPolicy in audioserver (system). Vendors rename the file (Samsung's HAL reads
# audio_effects_sec.xml while audioserver reads audio_effects.xml), so every audio_effects*.xml is
# a candidate; sp-xmlpatch later skips the ones that are not effects configurations.
sp_config_candidates() {
    for f in "$SP_ROOT"/odm/etc/audio_effects*.xml \
        "$SP_ROOT"/vendor/etc/audio_effects*.xml \
        "$SP_ROOT"/vendor/etc/audio/*/audio_effects*.xml \
        "$SP_ROOT"/system/etc/audio_effects*.xml; do
        [ -f "$f" ] && echo "$f"
    done
}

# Where a device file is placed inside the module, and the SELinux context it needs.
# Magisk overlays system, vendor, product and system_ext ("magic mount"); anything else (a real
# /odm partition) goes to bind/ and is bind-mounted by post-fs-data.sh.
sp_module_path() { # $1 = device path
    case "$1" in
        /system/*) echo "$1" ;;
        /vendor/* | /product/* | /system_ext/*) echo "/system$1" ;;
        *) echo "/bind$1" ;;
    esac
}

sp_config_context() { # $1 = device path
    case "$1" in
        /vendor/* | /odm/* | /system/vendor/*) echo u:object_r:vendor_configs_file:s0 ;;
        *) echo u:object_r:system_file:s0 ;;
    esac
}

# PID of the process that serves the HIDL effects factory (usually the audio HAL service).
sp_effects_hal_pid() {
    lshal -i -p 2>/dev/null |
        awk '/audio\.effect@[0-9.]+::IEffectsFactory\/default/ && $2 ~ /^[0-9]+$/ { print $2; exit }'
}

# The text tombstones on the device, one "path size mtime" line each. post-fs-data.sh saves this
# list at the start of every boot, and the watchdog compares tombstones with it rather than by
# time: some devices only set the clock later in the boot (the test device starts in 2018).
sp_tombstones() {
    for t in "$SP_ROOT"/data/tombstones/tombstone_*; do
        [ -f "$t" ] || continue
        case "$t" in *.pb) continue ;; esac
        stat -c '%n %s %Y' "$t"
    done
}

# Prints the first tombstone that is not in the list $1 (new, or rewritten since) and whose
# backtrace has a frame in libsoundpower.so.
sp_crash_in_library() { # $1 = list written by sp_tombstones at the start of this boot
    _sp_hit=$(sp_tombstones | while read -r _sp_t _sp_rest; do
        grep -qxF "$_sp_t $_sp_rest" "$1" 2>/dev/null && continue
        if grep -qE '#[0-9]+ +pc +[0-9a-f]+ +[^ ]*libsoundpower\.so' "$_sp_t"; then
            echo "$_sp_t"
            break
        fi
    done)
    [ -n "$_sp_hit" ] && echo "$_sp_hit"
}

sp_log() { # $1 = log file, $2... = message
    _sp_log_file=$1
    shift
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$_sp_log_file"
}
