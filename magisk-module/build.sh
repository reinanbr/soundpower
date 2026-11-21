#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the Magisk module zip: out/soundpower-module-v<VERSION>.zip
#
#   magisk-module/build.sh             configure and build the android-arm64 and android-armv7
#                                      presets first (needs ANDROID_NDK_HOME), then package
#   magisk-module/build.sh --no-build  package what build/android-* already contains
#
# The libraries and sp-xmlpatch are stripped. File times are set from the last commit
# (or SOURCE_DATE_EPOCH), so the same sources give the same zip.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=${here%/*}
build=1
case "${1:-}" in
    --no-build) build=0 ;;
    "") ;;
    *)
        echo "usage: $0 [--no-build]" >&2
        exit 1
        ;;
esac

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an Android NDK r28 installation}"
strip_tool=$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -path '*/bin/llvm-strip' | head -n 1)
[ -x "$strip_tool" ] || {
    echo "llvm-strip not found in $ANDROID_NDK_HOME" >&2
    exit 1
}

version=$(head -n 1 "$root/VERSION")
IFS=. read -r major minor patch <<EOF
$version
EOF
version_code=$((major * 10000 + minor * 100 + patch))

if [ "$build" = 1 ]; then
    for preset in android-arm64 android-armv7; do
        (cd "$root" && cmake --preset "$preset" >/dev/null && cmake --build --preset "$preset" --target soundpower sp-xmlpatch)
    done
fi

stage=$root/build/magisk-module/stage
rm -rf "$stage"
mkdir -p "$stage"
cp -R "$here/template/." "$stage/"
rm "$stage/module.prop.in"
sed -e "s/@SP_VERSION@/$version/" -e "s/@SP_VERSION_CODE@/$version_code/" \
    "$here/template/module.prop.in" >"$stage/module.prop"
cp "$root/LICENSE" "$stage/LICENSE"

for pair in arm64-v8a:android-arm64 armeabi-v7a:android-armv7; do
    abi=${pair%%:*}
    out=$root/build/${pair#*:}
    for f in "$out/effect-lib/libsoundpower.so" "$out/tools/xmlpatch/sp-xmlpatch"; do
        [ -f "$f" ] || {
            echo "missing $f (run without --no-build)" >&2
            exit 1
        }
    done
    mkdir -p "$stage/lib/$abi" "$stage/bin/$abi"
    "$strip_tool" --strip-unneeded -o "$stage/lib/$abi/libsoundpower.so" "$out/effect-lib/libsoundpower.so"
    "$strip_tool" --strip-all -o "$stage/bin/$abi/sp-xmlpatch" "$out/tools/xmlpatch/sp-xmlpatch"
done

epoch=${SOURCE_DATE_EPOCH:-$(git -C "$root" log -1 --format=%ct 2>/dev/null || date +%s)}
find "$stage" -exec touch -h -d "@$epoch" {} +
chmod -R u=rwX,go=rX "$stage"

mkdir -p "$root/out"
zip_file=$root/out/soundpower-module-v$version.zip
rm -f "$zip_file"
(cd "$stage" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | zip -X -q "$zip_file" -@)
echo "$zip_file"
