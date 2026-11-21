# shellcheck shell=sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs in the background at every boot. A crash in an audio effect takes the whole audio HAL
# down with it (no sound on the device), so for a few minutes after boot this watches the
# process that hosts the effects factory. If it keeps restarting, or a tombstone shows a crash
# inside libsoundpower.so, the module disables itself for the next boot and says why in
# log/boot.log. A restart the app asked for (it touches restart-requested first, for direct
# volume control) does not count. Then it writes log/diag.txt.
MODDIR=${0%/*}
umask 022
# shellcheck source=common.sh
. "$MODDIR/common.sh"
SP_LOG=$MODDIR/log/boot.log
SP_WATCH_SECONDS=${SP_WATCH_SECONDS:-180}
SP_WATCH_INTERVAL=${SP_WATCH_INTERVAL:-5}
SP_MAX_RESTARTS=3
SP_RESTART_REQUESTED=$MODDIR/restart-requested
# A request left from the previous boot must not excuse a crash in this one.
rm -f "$SP_RESTART_REQUESTED"

sp_waited=0
until [ "$(getprop sys.boot_completed)" = 1 ] || [ "$sp_waited" -ge 600 ]; do
    sleep 2
    sp_waited=$((sp_waited + 2))
done

sp_last=$(sp_effects_hal_pid)
sp_log "$SP_LOG" "watchdog: effects HAL pid ${sp_last:-unknown}"
sp_restarts=0
sp_elapsed=0
while [ "$sp_elapsed" -lt "$SP_WATCH_SECONDS" ]; do
    sleep "$SP_WATCH_INTERVAL"
    sp_elapsed=$((sp_elapsed + SP_WATCH_INTERVAL))
    sp_pid=$(sp_effects_hal_pid)
    if [ -n "$sp_pid" ] && [ -n "$sp_last" ] && [ "$sp_pid" != "$sp_last" ]; then
        if [ -f "$SP_RESTART_REQUESTED" ]; then
            rm -f "$SP_RESTART_REQUESTED"
            sp_log "$SP_LOG" "watchdog: effects HAL restarted on request ($sp_last -> $sp_pid)"
        else
            sp_restarts=$((sp_restarts + 1))
            sp_log "$SP_LOG" "watchdog: effects HAL restarted ($sp_last -> $sp_pid)"
        fi
    fi
    [ -n "$sp_pid" ] && sp_last=$sp_pid

    sp_reason=
    if sp_tomb=$(sp_crash_in_library "$MODDIR/log/tombstones.txt"); then
        sp_reason="crash inside $SP_LIB ($sp_tomb)"
    elif [ "$sp_restarts" -ge "$SP_MAX_RESTARTS" ]; then
        sp_reason="the effects HAL restarted $sp_restarts times"
    fi
    if [ -n "$sp_reason" ]; then
        touch "$MODDIR/disable"
        sp_log "$SP_LOG" "watchdog: $sp_reason; SoundPower is disabled from the next boot."
        sp_log "$SP_LOG" "watchdog: re-enable it in the Magisk app after fixing the cause."
        break
    fi
done
[ -z "$sp_reason" ] && sp_log "$SP_LOG" "watchdog: no problem in ${sp_elapsed}s ($sp_restarts restarts)"

sh "$MODDIR/diag.sh" >"$MODDIR/log/diag.txt" 2>&1
