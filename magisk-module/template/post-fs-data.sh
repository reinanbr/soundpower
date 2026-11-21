# shellcheck shell=sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs early at every boot (blocking, before the audio HAL starts): prepares the data directory
# the effect reads its saved state from, and bind-mounts files Magisk cannot overlay.
MODDIR=${0%/*}
umask 022
# shellcheck source=common.sh
. "$MODDIR/common.sh"
SP_LOG=$MODDIR/log/boot.log
mkdir -p "$MODDIR/log"
: >"$SP_LOG"
sp_tombstones >"$MODDIR/log/tombstones.txt" # what already existed, for the watchdog in service.sh
sp_log "$SP_LOG" "post-fs-data: $(grep '^version=' "$MODDIR/module.prop")"

# The audio HAL reads $SP_DATA_DIR/state.bin; sepolicy.rule defines the type that allows it.
mkdir -p "$SP_DATA_DIR"
chown 0:0 "$SP_DATA_DIR"
chmod 0755 "$SP_DATA_DIR"
find "$SP_DATA_DIR" -type f -exec chmod 0644 {} + 2>/dev/null
if ! chcon -R "$SP_DATA_CONTEXT" "$SP_DATA_DIR" 2>/dev/null; then
    # Magisk had nowhere to store the rules for early loading: load them into the live policy.
    magiskpolicy --live --apply "$MODDIR/sepolicy.rule" >/dev/null 2>&1
    if chcon -R "$SP_DATA_CONTEXT" "$SP_DATA_DIR" 2>/dev/null; then
        sp_log "$SP_LOG" "sepolicy: rules loaded at post-fs-data (not preloaded by Magisk)"
    else
        sp_log "$SP_LOG" "sepolicy: cannot label $SP_DATA_DIR; the saved state will not load"
    fi
fi

# Files on partitions Magisk does not overlay (such as a separate /odm).
if [ -f "$MODDIR/bind.list" ]; then
    while read -r sp_dev; do
        if [ -f "$MODDIR/bind$sp_dev" ] && [ -f "$SP_ROOT$sp_dev" ] &&
            mount -o bind "$MODDIR/bind$sp_dev" "$SP_ROOT$sp_dev"; then
            sp_log "$SP_LOG" "bind-mounted $sp_dev"
        else
            sp_log "$SP_LOG" "could not bind-mount $sp_dev"
        fi
    done <"$MODDIR/bind.list"
fi
