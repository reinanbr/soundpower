// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The few icons the app needs, drawn on a 24-unit grid. */
object SpIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = block)
            .build()

    /** An outlined icon: [stroke] is drawn with a 2-unit round pen, [fill] filled. */
    private fun lineIcon(name: String, fill: (PathBuilder.() -> Unit)? = null, stroke: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = stroke,
            )
            .apply { if (fill != null) path(fill = SolidColor(Color.Black), pathBuilder = fill) }
            .build()

    private fun PathBuilder.dot(x: Float, y: Float, r: Float) {
        moveTo(x - r, y)
        arcToRelative(r, r, 0f, true, false, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, false, -2 * r, 0f)
        close()
    }

    /** Three faders at different heights: the equalizer tab. */
    val Faders: ImageVector by lazy {
        lineIcon("faders", fill = {
            dot(6f, 14f, 2.6f)
            dot(12f, 8f, 2.6f)
            dot(18f, 16f, 2.6f)
        }) {
            for (x in listOf(6f, 12f, 18f)) {
                moveTo(x, 4f)
                lineTo(x, 20f)
            }
        }
    }

    /** A dial with its pointer: the tone and dynamics tab. */
    val Dial: ImageVector by lazy {
        lineIcon("dial", fill = { dot(15.5f, 8.5f, 1.6f) }) {
            moveTo(4f, 12f)
            arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
            arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
        }
    }

    /** Stacked lines: the menu tab. */
    val Menu: ImageVector by lazy {
        lineIcon("menu") {
            for (y in listOf(7f, 12f, 17f)) {
                moveTo(5f, y)
                lineTo(19f, y)
            }
        }
    }

    val Lock: ImageVector by lazy {
        lineIcon("lock", fill = { dot(12f, 15.5f, 1.5f) }) {
            moveTo(6f, 11f)
            lineTo(18f, 11f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            close()
            moveTo(8.5f, 11f)
            lineTo(8.5f, 8f)
            arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
            lineTo(15.5f, 11f)
        }
    }

    val LockOpen: ImageVector by lazy {
        lineIcon("lock-open", fill = { dot(12f, 15.5f, 1.5f) }) {
            moveTo(6f, 11f)
            lineTo(18f, 11f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            close()
            moveTo(8.5f, 11f)
            lineTo(8.5f, 8f)
            arcToRelative(3.5f, 3.5f, 0f, false, true, 6.8f, -1.2f)
        }
    }

    /** A circular arrow: put values back. */
    val Reset: ImageVector by lazy {
        lineIcon("reset") {
            moveTo(5.5f, 12f)
            arcToRelative(6.5f, 6.5f, 0f, true, false, 2f, -4.7f)
            moveTo(5f, 4.5f)
            lineTo(5f, 8.5f)
            lineTo(9f, 8.5f)
        }
    }


    val ChevronRight: ImageVector by lazy {
        lineIcon("chevron-right") {
            moveTo(10f, 7f)
            lineTo(15f, 12f)
            lineTo(10f, 17f)
        }
    }

    val Back: ImageVector by lazy {
        icon("back") {
            moveTo(15.5f, 4.5f)
            lineTo(17f, 6f)
            lineTo(11f, 12f)
            lineTo(17f, 18f)
            lineTo(15.5f, 19.5f)
            lineTo(8f, 12f)
            close()
        }
    }


    val Add: ImageVector by lazy {
        icon("add") {
            moveTo(11f, 5f)
            horizontalLineTo(13f)
            verticalLineTo(11f)
            horizontalLineTo(19f)
            verticalLineTo(13f)
            horizontalLineTo(13f)
            verticalLineTo(19f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            horizontalLineTo(5f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            close()
        }
    }

    val Remove: ImageVector by lazy {
        icon("remove") {
            moveTo(6.3f, 4.9f)
            lineTo(12f, 10.6f)
            lineTo(17.7f, 4.9f)
            lineTo(19.1f, 6.3f)
            lineTo(13.4f, 12f)
            lineTo(19.1f, 17.7f)
            lineTo(17.7f, 19.1f)
            lineTo(12f, 13.4f)
            lineTo(6.3f, 19.1f)
            lineTo(4.9f, 17.7f)
            lineTo(10.6f, 12f)
            lineTo(4.9f, 6.3f)
            close()
        }
    }

    /** A shield with a check: the mode with root, SoundPower's own effect in the system. */
    val Shield: ImageVector by lazy {
        lineIcon("shield") {
            moveTo(12f, 3f)
            lineTo(19f, 6f)
            verticalLineTo(11f)
            curveTo(19f, 15.5f, 16f, 19f, 12f, 21f)
            curveTo(8f, 19f, 5f, 15.5f, 5f, 11f)
            verticalLineTo(6f)
            close()
            moveTo(9f, 12f)
            lineTo(11f, 14f)
            lineTo(15f, 10f)
        }
    }

    /** A sound wave: the mode without root, on Android's own audio effects. */
    val Wave: ImageVector by lazy {
        lineIcon("wave") {
            moveTo(3f, 12f)
            curveTo(5f, 6f, 7f, 6f, 9f, 12f)
            reflectiveCurveTo(13f, 18f, 15f, 12f)
            reflectiveCurveTo(19f, 6f, 21f, 12f)
        }
    }

    /** A tick: the current preset. */
    val Check: ImageVector by lazy {
        lineIcon("check") {
            moveTo(5f, 12.5f)
            lineTo(10f, 17.5f)
            lineTo(19f, 7f)
        }
    }

    val Search: ImageVector by lazy {
        lineIcon("search") {
            moveTo(4f, 10.5f)
            arcToRelative(6.5f, 6.5f, 0f, true, true, 13f, 0f)
            arcToRelative(6.5f, 6.5f, 0f, true, true, -13f, 0f)
            moveTo(15.5f, 15.5f)
            lineTo(20f, 20f)
        }
    }

    /** Three dots: more actions. */
    val More: ImageVector by lazy {
        icon("more") {
            dot(12f, 5f, 2f)
            dot(12f, 12f, 2f)
            dot(12f, 19f, 2f)
        }
    }

    /** Opens a list below. */
    val ExpandMore: ImageVector by lazy {
        lineIcon("expand-more") {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }

    /** A bookmark: save the preset. */
    val Save: ImageVector by lazy {
        lineIcon("save") {
            moveTo(6f, 4f)
            lineTo(18f, 4f)
            lineTo(18f, 20f)
            lineTo(12f, 15.5f)
            lineTo(6f, 20f)
            close()
        }
    }

    /** An arrow into a tray. */
    val Import: ImageVector by lazy {
        lineIcon("import") {
            moveTo(12f, 4f)
            lineTo(12f, 15f)
            moveTo(7f, 10f)
            lineTo(12f, 15f)
            lineTo(17f, 10f)
            moveTo(4f, 15f)
            lineTo(4f, 20f)
            lineTo(20f, 20f)
            lineTo(20f, 15f)
        }
    }

    /** An arrow out of a tray. */
    val Share: ImageVector by lazy {
        lineIcon("share") {
            moveTo(12f, 15f)
            lineTo(12f, 4f)
            moveTo(7f, 9f)
            lineTo(12f, 4f)
            lineTo(17f, 9f)
            moveTo(4f, 15f)
            lineTo(4f, 20f)
            lineTo(20f, 20f)
            lineTo(20f, 15f)
        }
    }

    val Close: ImageVector by lazy {
        lineIcon("close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    /** Over-ear headphones: AutoEQ suggestions. */
    val Headphones: ImageVector by lazy {
        lineIcon("headphones") {
            moveTo(4f, 17f)
            lineTo(4f, 12f)
            arcToRelative(8f, 8f, 0f, false, true, 16f, 0f)
            lineTo(20f, 17f)
            moveTo(4f, 14f)
            lineTo(7f, 14f)
            lineTo(7f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(20f, 14f)
            lineTo(17f, 14f)
            lineTo(17f, 20f)
            lineTo(20f, 20f)
            close()
        }
    }

    /** A rounded triangle with a mark: something to fix. */
    val Alert: ImageVector by lazy {
        lineIcon("alert", fill = { dot(12f, 16.5f, 1.3f) }) {
            moveTo(10.3f, 4.6f)
            lineTo(3.2f, 17f)
            arcToRelative(2f, 2f, 0f, false, false, 1.7f, 3f)
            lineTo(19.1f, 20f)
            arcToRelative(2f, 2f, 0f, false, false, 1.7f, -3f)
            lineTo(13.7f, 4.6f)
            arcToRelative(2f, 2f, 0f, false, false, -3.4f, 0f)
            close()
            moveTo(12f, 9f)
            lineTo(12f, 13f)
        }
    }

    /** A circle with an "i": good to know. */
    val Info: ImageVector by lazy {
        lineIcon("info", fill = { dot(12f, 7.8f, 1.3f) }) {
            moveTo(3f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            moveTo(12f, 11f)
            lineTo(12f, 16.5f)
        }
    }

    /** An open circle with an arrowhead: read again. */
    val Refresh: ImageVector by lazy {
        lineIcon("refresh") {
            moveTo(19.5f, 12f)
            arcToRelative(7.5f, 7.5f, 0f, true, true, -2.2f, -5.3f)
            moveTo(18f, 3.5f)
            lineTo(18f, 7.5f)
            lineTo(14f, 7.5f)
        }
    }

    /** Two overlapping sheets: copy. */
    val Copy: ImageVector by lazy {
        lineIcon("copy") {
            moveTo(9f, 9f)
            lineTo(19f, 9f)
            lineTo(19f, 20f)
            lineTo(9f, 20f)
            close()
            moveTo(15f, 9f)
            lineTo(15f, 4f)
            lineTo(5f, 4f)
            lineTo(5f, 15f)
            lineTo(9f, 15f)
        }
    }
}
