# shellcheck shell=sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# SoundPower module installer, sourced by Magisk's install_module (util_functions.sh). Magisk
# provides MODPATH, MODID, ZIPFILE, TMPDIR, ARCH, IS64BIT, API, BOOTMODE and MAGISK_VER_CODE, and
# the ui_print, abort and set_perm functions.
#
# It patches a copy of every audio effects XML on the device (so the effects factory loads the
# library and AudioPolicy attaches the effect to music streams), adds the library for each ABI the
# vendor partition has, and records what it did for the diagnostics script.
# Magisk reads SKIPUNZIP and sets MODPATH, ZIPFILE and the rest before sourcing this file.
# shellcheck disable=SC2034,SC2154
SKIPUNZIP=1
# magisk --install-module runs with umask 0; set_perm fixes what is kept, this covers the rest.
umask 022

# ---- Requirements --------------------------------------------------------------------------
[ "$API" -ge 28 ] || abort "! SoundPower needs Android 9 (API 28) or newer; this is API $API."
[ "$MAGISK_VER_CODE" -ge 24000 ] || abort "! SoundPower needs Magisk 24.0 or newer."
case "$ARCH" in
    arm64) SP_ABI=arm64-v8a ;;
    arm) SP_ABI=armeabi-v7a ;;
    *) abort "! Unsupported CPU architecture '$ARCH': SoundPower is built for arm and arm64." ;;
esac

unzip -o "$ZIPFILE" common.sh "bin/$SP_ABI/sp-xmlpatch" 'lib/*' -d "$TMPDIR" >&2 ||
    abort "! Could not extract the module files."
# shellcheck source=common.sh
. "$TMPDIR/common.sh"
SP_XMLPATCH=${SP_TEST_XMLPATCH:-$TMPDIR/bin/$SP_ABI/sp-xmlpatch}
chmod 0755 "$SP_XMLPATCH"

# ---- Effects HAL ---------------------------------------------------------------------------
# Only the HIDL effects factory loads legacy effect libraries; the AIDL effects HAL (Android 14+
# on some devices) does not.
if $BOOTMODE; then
    sp_hidl=$(lshal -i 2>/dev/null | grep -c 'android\.hardware\.audio\.effect@.*::IEffectsFactory/')
    sp_aidl=$(service list 2>/dev/null | grep -c 'android\.hardware\.audio\.effect\.IFactory')
    if [ "$sp_hidl" -gt 0 ]; then
        ui_print "- Effects HAL: HIDL"
    elif [ "$sp_aidl" -gt 0 ]; then
        abort "! This device uses the AIDL effects HAL, which SoundPower does not support yet."
    else
        ui_print "! Could not identify the effects HAL; installing anyway."
    fi
else
    ui_print "- Installing from recovery: the effects HAL type is not checked."
fi

# ---- Effects configurations ----------------------------------------------------------------
: >"$MODPATH/configs.list"
sp_seen=" "
sp_patched=0
for sp_src in $(sp_config_candidates); do
    sp_real=$(readlink -f "$sp_src")
    case "$sp_seen" in *" $sp_real "*) continue ;; esac
    sp_seen="$sp_seen$sp_real "
    sp_dev=${sp_real#"$SP_ROOT"}

    if ! "$SP_XMLPATCH" check "$sp_src" >/dev/null 2>&1; then
        ui_print "  Skipping $sp_dev: not an audio effects configuration."
        continue
    fi
    sp_dest=$MODPATH$(sp_module_path "$sp_dev")
    mkdir -p "${sp_dest%/*}"
    if sp_err=$("$SP_XMLPATCH" patch "$sp_src" "$sp_dest" 2>&1); then
        set_perm "$sp_dest" 0 0 0644 "$(sp_config_context "$sp_dev")"
        echo "$sp_dev" >>"$MODPATH/configs.list"
        case "$sp_dev" in /odm/*) echo "$sp_dev" >>"$MODPATH/bind.list" ;; esac
        sp_patched=$((sp_patched + 1))
        ui_print "- Patched $sp_dev"
    else
        rm -f "$sp_dest"
        ui_print "! Could not patch $sp_dev: $sp_err"
    fi
done
[ "$sp_patched" -gt 0 ] ||
    abort "! No audio effects XML found. Devices with only legacy .conf files are not supported."

# ---- Libraries -----------------------------------------------------------------------------
# The effects factory looks for libraries in <partition>/lib*/soundfx. The audio HAL is often
# 32-bit even on 64-bit devices, so install every ABI the vendor partition has.
sp_install_lib() { # $1 = ABI, $2 = lib or lib64
    sp_dir=$MODPATH/system/vendor/$2/soundfx
    mkdir -p "$sp_dir"
    cp -f "$TMPDIR/lib/$1/$SP_LIB" "$sp_dir/$SP_LIB" || abort "! The zip has no lib/$1/$SP_LIB."
    set_perm "$sp_dir/$SP_LIB" 0 0 0644 u:object_r:vendor_file:s0
    ui_print "- Installed /vendor/$2/soundfx/$SP_LIB ($1)"
}
[ -d "$SP_ROOT/vendor/lib" ] && sp_install_lib armeabi-v7a lib
$IS64BIT && [ -d "$SP_ROOT/vendor/lib64" ] && sp_install_lib arm64-v8a lib64
[ -d "$MODPATH/system/vendor/lib" ] || [ -d "$MODPATH/system/vendor/lib64" ] ||
    abort "! Found neither /vendor/lib nor /vendor/lib64."

# ---- Module scripts ------------------------------------------------------------------------
unzip -o "$ZIPFILE" module.prop common.sh post-fs-data.sh service.sh uninstall.sh diag.sh \
    sepolicy.rule LICENSE -d "$MODPATH" >&2 || abort "! Could not extract the module scripts."
mkdir -p "$MODPATH/log"

# Directories: 0755. Those Magisk may have to create on /vendor need the vendor context.
find "$MODPATH" -type d | while read -r sp_d; do
    case "$sp_d" in
        "$MODPATH"/system/vendor | "$MODPATH"/system/vendor/*) set_perm "$sp_d" 0 0 0755 u:object_r:vendor_file:s0 ;;
        *) set_perm "$sp_d" 0 0 0755 ;;
    esac
done
for sp_f in module.prop common.sh post-fs-data.sh service.sh uninstall.sh diag.sh sepolicy.rule \
    LICENSE configs.list; do
    set_perm "$MODPATH/$sp_f" 0 0 0644
done
[ -f "$MODPATH/bind.list" ] && set_perm "$MODPATH/bind.list" 0 0 0644

# The data directory is labelled soundpower_data_file by post-fs-data.sh at every boot, once
# sepolicy.rule is loaded; until then it keeps the label of its parent.
mkdir -p "$SP_DATA_DIR"
set_perm "$SP_DATA_DIR" 0 0 0755 u:object_r:vendor_data_file:s0

# ---- Conflicts -----------------------------------------------------------------------------
# Two modules replacing the same file: only one copy is mounted.
for sp_m in "$SP_ROOT"/data/adb/modules/*; do
    [ -d "$sp_m" ] || continue
    [ "${sp_m##*/}" = "$MODID" ] && continue
    [ -f "$sp_m/disable" ] || [ -f "$sp_m/remove" ] && continue
    while read -r sp_dev; do
        if [ -f "$sp_m$(sp_module_path "$sp_dev")" ]; then
            ui_print "! Module '${sp_m##*/}' also replaces $sp_dev. Only one of the two copies"
            ui_print "  takes effect; disable one module if SoundPower does not load."
        fi
    done <"$MODPATH/configs.list"
done

ui_print "- Reboot to activate SoundPower."
