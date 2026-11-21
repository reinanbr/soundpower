#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs the module scripts on the host against fake device trees, with Magisk's installer
# functions and the device commands (lshal, getprop, chcon, ...) replaced by stubs.
#
#   test_scripts.sh <host sp-xmlpatch> <sp-xmlpatch fixtures dir>
#
# Exit code 77 (skipped) when zip/unzip are not installed.
set -u

xmlpatch=$1
fixtures=$2
here=$(cd "$(dirname "$0")" && pwd)
template=$here/../template
command -v zip >/dev/null && command -v unzip >/dev/null || {
    echo "zip and unzip are needed"
    exit 77
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0
checks=0

ok() { # $1 = description, rest = test command
    checks=$((checks + 1))
    desc=$1
    shift
    if "$@"; then :; else
        echo "FAIL: $desc"
        failures=$((failures + 1))
    fi
}
contains() { grep -qF -- "$2" "$1"; }
lacks() { ! grep -qF -- "$2" "$1"; }
same() { cmp -s "$1" "$2"; }
absent() { for p in "$@"; do [ ! -e "$p" ] || return 1; done; }
refused() { [ "$rc" != 0 ] && contains "$out" "$1"; }
succeeded() { [ "$rc" = 0 ] && contains "$out" "$1"; }

# ---- Stub commands -----------------------------------------------------------------------------
stubs=$work/stubs
mkdir -p "$stubs"
cat >"$stubs/lshal" <<'EOF'
#!/bin/sh
# FAKE_HAL: hidl, aidl or none. FAKE_PIDS: a file with one PID per call (the last one repeats).
[ "${FAKE_HAL:-hidl}" = hidl ] || exit 0
pid=1160
if [ -n "${FAKE_PIDS:-}" ]; then
    pid=$(head -n 1 "$FAKE_PIDS")
    [ "$(wc -l <"$FAKE_PIDS")" -gt 1 ] && sed -i 1d "$FAKE_PIDS"
fi
echo "android.hardware.audio.effect@5.0::IEffectsFactory/default    N/A"
echo "android.hardware.audio.effect@5.0::IEffectsFactory/default    $pid"
EOF
cat >"$stubs/service" <<'EOF'
#!/bin/sh
[ "${FAKE_HAL:-hidl}" = aidl ] && echo "42	android.hardware.audio.effect.IFactory/default: [android.hardware.audio.effect.IFactory]"
exit 0
EOF
cat >"$stubs/getprop" <<'EOF'
#!/bin/sh
[ "$1" = sys.boot_completed ] && echo 1
exit 0
EOF
cat >"$stubs/chcon" <<'EOF'
#!/bin/sh
# Fails (unknown type) until magiskpolicy has "loaded" the rules, when FAKE_POLICY_DIR is set.
[ -z "${FAKE_POLICY_DIR:-}" ] || [ -f "$FAKE_POLICY_DIR/loaded" ] || exit 1
echo "chcon $*" >>"${FAKE_LOG:-/dev/null}"
EOF
cat >"$stubs/magiskpolicy" <<'EOF'
#!/bin/sh
echo "magiskpolicy $*" >>"${FAKE_LOG:-/dev/null}"
[ -n "${FAKE_POLICY_DIR:-}" ] && touch "$FAKE_POLICY_DIR/loaded"
exit 0
EOF
for c in sleep chown mount dmesg logcat dumpsys; do
    printf '#!/bin/sh\necho "%s $*" >>"${FAKE_LOG:-/dev/null}"\nexit 0\n' "$c" >"$stubs/$c"
done
chmod +x "$stubs"/*

# ---- A module zip with placeholder binaries ----------------------------------------------------
z=$work/zipdir
mkdir -p "$z"
cp -R "$template/." "$z/"
sed -e 's/@SP_VERSION@/9.9.9/' -e 's/@SP_VERSION_CODE@/90909/' "$template/module.prop.in" >"$z/module.prop"
rm "$z/module.prop.in"
echo "GPL-3.0" >"$z/LICENSE"
for abi in arm64-v8a armeabi-v7a; do
    mkdir -p "$z/lib/$abi" "$z/bin/$abi"
    echo "lib-$abi" >"$z/lib/$abi/libsoundpower.so"
    echo "placeholder" >"$z/bin/$abi/sp-xmlpatch"
done
(cd "$z" && zip -q -r "$work/module.zip" .)

# ---- Helpers -----------------------------------------------------------------------------------
# new_device <name>: an empty fake device; prints its root.
new_device() {
    d=$work/dev-$1
    rm -rf "$d"
    mkdir -p "$d/vendor/etc" "$d/system/etc" "$d/data/adb/modules"
    echo "$d"
}

# install <device root>: runs customize.sh like Magisk's install_module does. Output in $out, the
# set_perm calls in $perms, the exit code in $rc. Environment: ARCH, IS64BIT, API, BOOTMODE, FAKE_HAL.
install() {
    dev=$1
    mod=$work/modpath
    out=$work/install.out
    perms=$work/perms
    rm -rf "$mod" "$work/tmp" "$perms"
    mkdir -p "$mod" "$work/tmp"
    (
        PATH=$stubs:$PATH
        SP_ROOT=$dev
        SP_TEST_XMLPATCH=$xmlpatch
        MODPATH=$mod
        MODID=soundpower
        ZIPFILE=$work/module.zip
        TMPDIR=$work/tmp
        ARCH=${ARCH:-arm64}
        IS64BIT=${IS64BIT:-true}
        API=${API:-32}
        BOOTMODE=${BOOTMODE:-true}
        MAGISK_VER_CODE=31100
        export PATH SP_ROOT
        ui_print() { echo "$1"; }
        abort() {
            echo "$1"
            exit 1
        }
        set_perm() { echo "$1 $2:$3 $4 ${5:-u:object_r:system_file:s0}" >>"$perms"; }
        # shellcheck source=../template/customize.sh
        . "$template/customize.sh"
    ) >"$out" 2>&1
    rc=$?
}

perm_of() { grep "^$1 " "$perms" | tail -n 1 | cut -d' ' -f2-; }
check_patched() { "$xmlpatch" check "$1" 2>/dev/null | grep -q '^library=1$'; }
postprocess_of() { "$xmlpatch" check "$1" | sed -n 's/^postprocess=//p'; }

# ---- 1. A Samsung-like arm64 device ------------------------------------------------------------
dev=$(new_device samsung)
cp "$fixtures/qcom_style.xml" "$dev/vendor/etc/audio_effects.xml"
cp "$fixtures/samsung_style.xml" "$dev/vendor/etc/audio_effects_sec.xml"
cp "$fixtures/not_effects.xml" "$dev/vendor/etc/audio_effects_policy.xml"
echo "legacy" >"$dev/vendor/etc/audio_effects.conf"
mkdir -p "$dev/vendor/etc/audio/sku_x" "$dev/odm/etc" "$dev/vendor/lib/soundfx" "$dev/vendor/lib64/soundfx"
cp "$fixtures/minimal.xml" "$dev/vendor/etc/audio/sku_x/audio_effects.xml"
cp "$fixtures/minimal.xml" "$dev/odm/etc/audio_effects.xml"
mkdir -p "$dev/data/adb/modules/othermod/system/vendor/etc" "$dev/data/adb/modules/oldmod/system/vendor/etc"
echo x >"$dev/data/adb/modules/othermod/system/vendor/etc/audio_effects_sec.xml"
echo x >"$dev/data/adb/modules/oldmod/system/vendor/etc/audio_effects.xml"
touch "$dev/data/adb/modules/oldmod/disable"

install "$dev"
ok "samsung: install succeeds" [ "$rc" = 0 ]
ok "samsung: reports HIDL" contains "$out" "Effects HAL: HIDL"
for f in audio_effects.xml audio_effects_sec.xml audio/sku_x/audio_effects.xml; do
    ok "samsung: patched /vendor/etc/$f" check_patched "$mod/system/vendor/etc/$f"
    ok "samsung: /vendor/etc/$f context" [ "$(perm_of "$mod/system/vendor/etc/$f")" = "0:0 0644 u:object_r:vendor_configs_file:s0" ]
done
ok "samsung: music postprocess" [ "$(postprocess_of "$mod/system/vendor/etc/audio_effects.xml")" = music ]
ok "samsung: odm config goes to bind/" check_patched "$mod/bind/odm/etc/audio_effects.xml"
ok "samsung: bind.list" [ "$(cat "$mod/bind.list")" = /odm/etc/audio_effects.xml ]
ok "samsung: configs.list has 4 files" [ "$(wc -l <"$mod/configs.list")" -eq 4 ]
ok "samsung: skips a non-effects XML" contains "$out" "Skipping /vendor/etc/audio_effects_policy.xml"
ok "samsung: does not copy it" [ ! -e "$mod/system/vendor/etc/audio_effects_policy.xml" ]
ok "samsung: 32-bit library" [ "$(cat "$mod/system/vendor/lib/soundfx/libsoundpower.so")" = lib-armeabi-v7a ]
ok "samsung: 64-bit library" [ "$(cat "$mod/system/vendor/lib64/soundfx/libsoundpower.so")" = lib-arm64-v8a ]
ok "samsung: library context" [ "$(perm_of "$mod/system/vendor/lib/soundfx/libsoundpower.so")" = "0:0 0644 u:object_r:vendor_file:s0" ]
ok "samsung: vendor directory context" [ "$(perm_of "$mod/system/vendor/lib64/soundfx")" = "0:0 0755 u:object_r:vendor_file:s0" ]
ok "samsung: script permissions" [ "$(perm_of "$mod/service.sh")" = "0:0 0644 u:object_r:system_file:s0" ]
for f in module.prop common.sh post-fs-data.sh service.sh uninstall.sh diag.sh sepolicy.rule LICENSE; do
    ok "samsung: extracted $f" [ -f "$mod/$f" ]
done
ok "samsung: no stray files" absent "$mod/lib" "$mod/bin"
ok "samsung: data directory" [ -d "$dev/data/vendor/soundpower" ]
ok "samsung: warns about a conflicting module" contains "$out" "Module 'othermod' also replaces /vendor/etc/audio_effects_sec.xml"
ok "samsung: ignores a disabled module" lacks "$out" "oldmod"

# ---- 2. Reinstalling over the mounted copies changes nothing ----------------------------------
cp -R "$mod" "$work/first"
for f in audio_effects.xml audio_effects_sec.xml audio/sku_x/audio_effects.xml; do
    cp "$work/first/system/vendor/etc/$f" "$dev/vendor/etc/$f"
done
install "$dev"
ok "reinstall: succeeds" [ "$rc" = 0 ]
for f in audio_effects.xml audio_effects_sec.xml audio/sku_x/audio_effects.xml; do
    ok "reinstall: /vendor/etc/$f unchanged" same "$work/first/system/vendor/etc/$f" "$mod/system/vendor/etc/$f"
done

# ---- 3. Other architectures and layouts --------------------------------------------------------
dev=$(new_device arm32)
cp "$fixtures/minimal.xml" "$dev/vendor/etc/audio_effects.xml"
mkdir -p "$dev/vendor/lib"
ARCH=arm IS64BIT=false install "$dev"
ok "arm: succeeds" [ "$rc" = 0 ]
ok "arm: 32-bit library" [ -f "$mod/system/vendor/lib/soundfx/libsoundpower.so" ]
ok "arm: no 64-bit library" absent "$mod/system/vendor/lib64"

dev=$(new_device only64)
cp "$fixtures/minimal.xml" "$dev/vendor/etc/audio_effects.xml"
mkdir -p "$dev/vendor/lib64"
install "$dev"
ok "64-bit only: succeeds" [ "$rc" = 0 ]
ok "64-bit only: no 32-bit library" absent "$mod/system/vendor/lib"
ok "64-bit only: 64-bit library" [ -f "$mod/system/vendor/lib64/soundfx/libsoundpower.so" ]

dev=$(new_device sysonly)
cp "$fixtures/empty_root.xml" "$dev/system/etc/audio_effects.xml"
mkdir -p "$dev/vendor/lib64"
install "$dev"
ok "system config: succeeds" [ "$rc" = 0 ]
ok "system config: placed under system/etc" check_patched "$mod/system/etc/audio_effects.xml"
ok "system config: system context" [ "$(perm_of "$mod/system/etc/audio_effects.xml")" = "0:0 0644 u:object_r:system_file:s0" ]

BOOTMODE=false FAKE_HAL=none install "$dev"
ok "recovery: succeeds" [ "$rc" = 0 ]
ok "recovery: says the HAL is not checked" contains "$out" "Installing from recovery"

# ---- 4. Refusals -------------------------------------------------------------------------------
FAKE_HAL=aidl install "$dev"
ok "aidl: refused" refused "AIDL effects HAL"
FAKE_HAL=none install "$dev"
ok "unknown HAL: continues" succeeded "Could not identify the effects HAL"
ARCH=x64 install "$dev"
ok "x86-64: refused" refused "Unsupported CPU architecture"
API=27 install "$dev"
ok "API 27: refused" refused "Android 9"

dev=$(new_device confonly)
echo "legacy" >"$dev/vendor/etc/audio_effects.conf"
mkdir -p "$dev/vendor/lib64"
install "$dev"
ok "conf only: refused" refused "No audio effects XML"

dev=$(new_device broken)
cp "$fixtures/malformed.xml" "$dev/vendor/etc/audio_effects.xml"
mkdir -p "$dev/vendor/lib64"
install "$dev"
ok "malformed only: refused" refused "No audio effects XML"
ok "malformed only: explains the parse error" contains "$out" "Skipping /vendor/etc/audio_effects.xml"

# ---- 5. Boot scripts ---------------------------------------------------------------------------
# A module directory as Magisk leaves it, on a device tree.
new_moddir() {
    moddir=$work/moddir
    rm -rf "$moddir"
    cp -R "$work/first" "$moddir"
    rm -f "$moddir/disable"
}

dev=$(new_device boot)
mkdir -p "$dev/odm/etc"
cp "$fixtures/minimal.xml" "$dev/odm/etc/audio_effects.xml"
cp "$work/first/system/vendor/etc/audio_effects.xml" "$dev/vendor/etc/audio_effects.xml" # "mounted"
new_moddir
export FAKE_LOG="$work/fake.log"
: >"$FAKE_LOG"
mkdir -p "$work/policy"
(PATH=$stubs:$PATH SP_ROOT=$dev FAKE_POLICY_DIR=$work/policy sh "$moddir/post-fs-data.sh")
ok "post-fs-data: creates the data directory" [ -d "$dev/data/vendor/soundpower" ]
ok "post-fs-data: loads the rules when they were not preloaded" contains "$FAKE_LOG" "magiskpolicy --live --apply $moddir/sepolicy.rule"
ok "post-fs-data: labels the data directory" contains "$FAKE_LOG" "chcon -R u:object_r:soundpower_data_file:s0 $dev/data/vendor/soundpower"
ok "post-fs-data: logs the fallback" contains "$moddir/log/boot.log" "rules loaded at post-fs-data"
ok "post-fs-data: boot mark" [ -f "$moddir/log/boot.mark" ]

: >"$FAKE_LOG"
(PATH=$stubs:$PATH SP_ROOT=$dev sh "$moddir/post-fs-data.sh")
ok "post-fs-data: no live policy load when preloaded" lacks "$FAKE_LOG" "magiskpolicy"
ok "post-fs-data: bind-mounts the /odm copy" contains "$FAKE_LOG" "mount -o bind $moddir/bind/odm/etc/audio_effects.xml $dev/odm/etc/audio_effects.xml"
ok "post-fs-data: logs the bind mount" contains "$moddir/log/boot.log" "bind-mounted /odm/etc/audio_effects.xml"

# dash and busybox compare whole seconds in "test -nt": make this boot start well in the past.
touch -d '2001-01-01' "$moddir/log/boot.mark"

# Watchdog: a stable HAL.
printf '1160\n' >"$work/pids"
(PATH=$stubs:$PATH SP_ROOT=$dev FAKE_PIDS=$work/pids SP_WATCH_SECONDS=30 sh "$moddir/service.sh")
ok "watchdog: stable HAL keeps the module" [ ! -f "$moddir/disable" ]
ok "watchdog: logs the result" contains "$moddir/log/boot.log" "watchdog: no problem in 30s (0 restarts)"
ok "watchdog: writes diag.txt" contains "$moddir/log/diag.txt" "SoundPower diagnostics"
ok "diag: reports a mounted copy" contains "$moddir/log/diag.txt" "/vendor/etc/audio_effects.xml: patched"
ok "diag: reports a copy that is not mounted" contains "$moddir/log/diag.txt" "/odm/etc/audio_effects.xml: NOT PATCHED"

# Watchdog: a HAL that keeps restarting.
printf '1160\n1200\n1300\n1400\n' >"$work/pids"
(PATH=$stubs:$PATH SP_ROOT=$dev FAKE_PIDS=$work/pids SP_WATCH_SECONDS=60 sh "$moddir/service.sh")
ok "watchdog: restart loop disables the module" [ -f "$moddir/disable" ]
ok "watchdog: says why" contains "$moddir/log/boot.log" "the effects HAL restarted 3 times"

# Watchdog: a tombstone with a frame in the library.
rm -f "$moddir/disable"
mkdir -p "$dev/data/tombstones"
printf '    /vendor/lib/soundfx/libsoundpower.so (mapped, not crashing)\n' >"$dev/data/tombstones/tombstone_00"
touch -d '2000-01-01' "$dev/data/tombstones/tombstone_00"
printf '    #00 pc 0004e3c4  /vendor/lib/soundfx/libsoundpower.so (BuildId: 1)\n' >"$dev/data/tombstones/tombstone_01.pb"
printf 'backtrace:\n      #00 pc 000000000004e3c4  /vendor/lib/soundfx/libsoundpower.so (sp::x+52)\n' >"$dev/data/tombstones/tombstone_02"
printf '1160\n' >"$work/pids"
(PATH=$stubs:$PATH SP_ROOT=$dev FAKE_PIDS=$work/pids SP_WATCH_SECONDS=30 sh "$moddir/service.sh")
ok "watchdog: crash in the library disables the module" [ -f "$moddir/disable" ]
ok "watchdog: names the tombstone" contains "$moddir/log/boot.log" "tombstone_02"

# sp_crash_in_library ignores old tombstones, protobuf ones and ones without a frame in the library.
rm -f "$dev/data/tombstones/tombstone_02"
printf 'backtrace:\n      #00 pc 000000000004e3c4  /system/lib/libc.so (abort+52)\n    /vendor/lib/soundfx/libsoundpower.so\n' >"$dev/data/tombstones/tombstone_03"
crash=$(SP_ROOT=$dev sh -c '. "$1/common.sh"; sp_crash_in_library "$1/log/boot.mark"' sh "$moddir")
ok "tombstones: only frames in the library count" [ -z "$crash" ]

echo "$checks checks, $failures failed"
[ "$failures" = 0 ]
