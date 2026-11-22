# shellcheck shell=sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Prints what SoundPower looks like on this device. Run as root:
#   su -c sh /data/adb/modules/soundpower/diag.sh
MODDIR=${0%/*}
# shellcheck source=common.sh
. "$MODDIR/common.sh"

echo "== SoundPower diagnostics, $(date '+%Y-%m-%d %H:%M:%S')"
echo "module: $(grep '^version=' "$MODDIR/module.prop" | cut -d= -f2)$([ -f "$MODDIR/disable" ] && echo ' (disabled)')$([ -f "$MODDIR/remove" ] && echo ' (to be removed)')"
echo "device: $(getprop ro.product.manufacturer) $(getprop ro.product.model), Android $(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk)), $(getprop ro.product.cpu.abi)"

echo "== effects HAL"
lshal -i -r -m -p 2>/dev/null | grep 'audio\.effect@.*IEffectsFactory' | awk 'NF >= 3' # with a server command
service list 2>/dev/null | grep -i 'audio\.effect'
sp_pid=$(sp_effects_hal_pid)
if [ -n "$sp_pid" ]; then
    echo "pid $sp_pid: $(tr '\0' ' ' <"/proc/$sp_pid/cmdline")"
    sp_maps=$(grep "$SP_LIB" "/proc/$sp_pid/maps" 2>/dev/null | awk '{ print $6 }' | sort -u)
    echo "library: ${sp_maps:-NOT LOADED}"
else
    echo "effects HAL process not found"
fi

echo "== configurations"
while read -r sp_dev; do
    if grep -q 'name="soundpower"' "$SP_ROOT$sp_dev" 2>/dev/null; then
        echo "$sp_dev: patched"
    else
        echo "$sp_dev: NOT PATCHED (the module's copy is not mounted)"
    fi
done <"$MODDIR/configs.list"

echo "== data"
if sp_context_known "$SP_DATA_CONTEXT"; then
    echo "policy: soundpower_data_file loaded"
else
    echo "policy: soundpower_data_file NOT LOADED (the audio HAL cannot read the saved state)"
fi
ls -ldZ "$SP_DATA_DIR" 2>&1
for sp_file in "$SP_DATA_DIR"/*; do
    if [ -e "$sp_file" ]; then ls -ldZ "$sp_file"; fi
done

echo "== SELinux denials"
{ dmesg 2>/dev/null; logcat -d -b all 2>/dev/null; } | grep 'avc: *denied' |
    grep -iE 'soundpower|soundfx' | tail -n 20

echo "== log (tag SoundPower)"
logcat -d -s SoundPower:V 2>/dev/null | tail -n 40

echo "== live instances (dumpsys media.audio_flinger)"
dumpsys media.audio_flinger 2>/dev/null | grep 'SoundPower [0-9]' | head -n 20

echo "== boot log"
cat "$MODDIR/log/boot.log" 2>/dev/null
