# shellcheck shell=sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Run by Magisk when the module is removed: delete the saved EQ state.
MODDIR=${0%/*}
# shellcheck source=common.sh
. "$MODDIR/common.sh"
rm -rf "$SP_DATA_DIR"
