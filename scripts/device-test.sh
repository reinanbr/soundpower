#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Checks SoundPower on a connected device over adb (needs root through Magisk for most checks):
#
#   scripts/device-test.sh [--tone PATH]
#
# 1. Effects HAL: HIDL or AIDL, and the bitness of the process that loads effect libraries.
# 2. Module: installed, enabled, configurations mounted, library loaded, data directory label,
#    SELinux denials (the module's diag.sh).
# 3. With --tone (sp-tone built for the device, e.g. build/android-arm64/tools/sp-tone/sp-tone):
#    plays a quiet media tone for a few seconds and checks that a SoundPower instance is attached
#    to its session and processing audio (dumpsys media.audio_flinger, which runs the effect's
#    DUMP command).
#
# Changes nothing on the device except /data/local/tmp/soundpower-tests, removed at the end.
# Set ANDROID_SERIAL to choose a device. Exit code: number of failed checks.
set -u

tone=
case "${1:-}" in
    --tone) tone=${2:?--tone needs the path of sp-tone} ;;
    "") ;;
    *)
        echo "usage: $0 [--tone PATH]" >&2
        exit 64
        ;;
esac

failed=0
pass() { echo "PASS  $*"; }
fail() {
    echo "FAIL  $*"
    failed=$((failed + 1))
}
info() { echo "      $*"; }
su_sh() { adb shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }

adb get-state >/dev/null 2>&1 || {
    echo "no device connected" >&2
    exit 64
}
echo "== $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"

if [ "$(su_sh 'id -u')" != 0 ]; then
    echo "root is not available (adb shell su); only basic checks are possible" >&2
    exit 64
fi

# ---- 1. Effects HAL ------------------------------------------------------------------------
# lshal prints the interface in several tables; only the one for running servers has a command.
hal=$(su_sh 'lshal -i -r -m -p' | grep 'audio\.effect@.*IEffectsFactory/default' | awk 'NF >= 3' | head -n 1)
if [ -n "$hal" ]; then
    pass "HIDL effects HAL: $(echo "$hal" | awk '{ print $1 }')"
    info "$(echo "$hal" | awk '{ print $2 "-bit, " $3 }')"
elif su_sh 'service list' | grep -q 'android\.hardware\.audio\.effect\.IFactory'; then
    fail "AIDL effects HAL: SoundPower does not support it"
else
    fail "no effects HAL found"
fi

# ---- 2. Module -----------------------------------------------------------------------------
moddir=/data/adb/modules/soundpower
if [ "$(su_sh "[ -f $moddir/module.prop ] && echo yes")" != yes ]; then
    fail "the SoundPower module is not installed"
    exit "$failed"
fi
[ "$(su_sh "[ -f $moddir/disable ] && echo yes")" = yes ] && fail "the module is disabled (see $moddir/log/boot.log)"
diag=$(su_sh "sh $moddir/diag.sh")
echo "$diag" | grep -q '^library: /' && pass "library loaded: $(echo "$diag" | sed -n 's/^library: //p' | tr '\n' ' ')" ||
    fail "the effects HAL has not loaded libsoundpower.so"
not_patched=$(echo "$diag" | grep -c 'NOT PATCHED')
[ "$not_patched" = 0 ] && pass "every patched configuration is mounted" || fail "$not_patched configuration(s) not mounted"
# The label alone proves nothing: it can be set even when the policy does not define the type.
echo "$diag" | grep -q '^policy: soundpower_data_file loaded' && pass "SELinux type soundpower_data_file loaded" ||
    fail "SELinux type soundpower_data_file not in the policy"
echo "$diag" | grep -q 'soundpower_data_file:s0 .*/data/vendor/soundpower$' && pass "data directory labelled soundpower_data_file" ||
    fail "data directory missing or mislabelled"
denials=$(echo "$diag" | sed -n '/^== SELinux denials/,/^== /p' | grep -c 'avc:')
[ "$denials" = 0 ] && pass "no SELinux denials mentioning SoundPower" || fail "$denials SELinux denial(s)"

# ---- 3. A live stream ----------------------------------------------------------------------
if [ -n "$tone" ]; then
    dir=/data/local/tmp/soundpower-tests
    adb shell mkdir -p "$dir" >/dev/null
    adb push "$tone" "$dir/sp-tone" >/dev/null
    adb shell "chmod 755 $dir/sp-tone"
    adb logcat -c 2>/dev/null
    out=$(mktemp)
    adb shell "$dir/sp-tone --seconds 4 --level -45" >"$out" 2>&1 &
    player=$!
    sleep 2
    session=$(sed -n 's/^session \([0-9]*\).*/\1/p' "$out" | tr -d '\r')
    dump=$(su_sh 'dumpsys media.audio_flinger' | grep 'SoundPower [0-9]')
    wait "$player"
    info "sp-tone: $(head -n 1 "$out" | tr -d '\r')"
    if [ -z "$session" ]; then
        fail "sp-tone could not play: $(tr -d '\r' <"$out")"
    elif echo "$dump" | grep -q "session $session .*active"; then
        pass "a SoundPower instance is active on the tone's session $session"
        info "$(echo "$dump" | grep "session $session " | head -n 1 | sed 's/^ *//')"
        echo "$dump" | grep "session $session " | grep -qv ' 0 frames' && pass "it is processing audio" ||
            fail "it has processed no audio"
    else
        fail "no active SoundPower instance on session $session"
        [ -n "$dump" ] && info "instances: $dump"
    fi
    info "log: $(adb logcat -d -s SoundPower:V 2>/dev/null | tr -d '\r' | grep -v '^-' | tail -n 3 | tr '\n' '|')"
    rm -f "$out"
    adb shell rm -rf "$dir"
fi

echo "== $failed check(s) failed"
exit "$failed"
