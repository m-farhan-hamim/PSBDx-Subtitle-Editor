package com.subtitle.psbdx.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import kotlin.math.min
import androidx.compose.ui.text.drawText as composeDrawText

/**
 * Text drawing that never hands the text engine a negative or oversized constraint
 * (which crashes when a label sits at/after the canvas edge or far off-screen).
 */
internal fun DrawScope.drawText(
    tm: TextMeasurer, text: String, topLeft: Offset, style: TextStyle,
    softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
) {
    if (text.isEmpty()) return
    val x = topLeft.x.coerceAtLeast(-1000f)
    val w = min(size.width - x, 4000f)
    val h = min(size.height - topLeft.y, 2000f)
    if (w < 4f || h < 4f || w.isNaN() || h.isNaN()) return
    composeDrawText(tm, text, Offset(x, topLeft.y), style, softWrap = softWrap, maxLines = maxLines, size = Size(w, h))
}
