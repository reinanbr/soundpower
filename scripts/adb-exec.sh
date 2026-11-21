#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# CMAKE_CROSSCOMPILING_EMULATOR for the Android presets: runs a test binary on a device.
#
#   adb-exec.sh <abi> <binary> [args...]
#
# The binary (plus any files listed in SP_DEVICE_FILES, colon-separated) is pushed to
# /data/local/tmp/soundpower-tests/<abi>/ and run from there as the shell user, so nothing on
# the device outside that directory changes. Set ANDROID_SERIAL to pick a device.
set -eu

abi=$1
binary=$2
shift 2

dir=/data/local/tmp/soundpower-tests/$abi
adb shell mkdir -p "$dir" >/dev/null
adb push "$binary" "$dir/" >/dev/null

old_ifs=$IFS
IFS=:
for file in ${SP_DEVICE_FILES:-}; do
    [ -n "$file" ] && adb push "$file" "$dir/" >/dev/null
done
IFS=$old_ifs

# Quote every argument for the device shell.
quoted=""
for arg in "$@"; do
    escaped=$(printf '%s' "$arg" | sed "s/'/'\\\\''/g")
    quoted="$quoted '$escaped'"
done

name=$(basename "$binary")
exec adb shell "cd $dir && chmod 755 ./$name && ./$name$quoted"
