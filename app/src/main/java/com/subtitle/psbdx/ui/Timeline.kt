package com.subtitle.psbdx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitle.psbdx.domain.Cue
import com.subtitle.psbdx.studio.StudioState
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Zoom/scroll state shared between the timeline canvas and its toolbar buttons. */
class TimelineState {
    var pps by mutableFloatStateOf(60f)
    var scrollMs by mutableFloatStateOf(0f)
    var widthPx by mutableIntStateOf(0)

    fun zoomBy(factor: Float, anchorPx: Float, panPx: Float = 0f) {
        val old = pps
        val newPps = (old * factor).coerceIn(8f, 800f)
        val anchorMs = scrollMs + anchorPx * 1000f / old
        pps = newPps
        scrollMs = (anchorMs - (anchorPx + panPx) * 1000f / newPps).coerceAtLeast(0f)
    }

    fun zoomAtPlayhead(factor: Float, posMs: Long) {
        val x = (posMs - scrollMs) * pps / 1000f
        zoomBy(factor, x.coerceIn(0f, max(1, widthPx).toFloat()))
    }
}

private fun DrawScope.drawWave(
    peaks: FloatArray, x0: Float, x1: Float, top: Float, h: Float, color: Color, srcMsAtX: (Float) -> Float,
) {
    val mid = top + h / 2
    var x = max(0f, x0)
    val end = min(size.width, x1)
    while (x < end) {
        val t0 = srcMsAtX(x)
        val t1 = srcMsAtX(x + 1f)
        val i0 = (t0 / 10f).toInt().coerceAtLeast(0)
        val i1 = max(i0, (t1 / 10f).toInt())
        var m = 0f
        var i = i0
        while (i <= i1 && i < peaks.size) {
            if (peaks[i] > m) m = peaks[i]
            i++
        }
        if (m > 0f) {
            val a = sqrt(m) * h / 2f
            drawLine(color, Offset(x, mid - a), Offset(x, mid + a), 1f)
        }
        x += 1f
    }
}

@Composable
fun Timeline(
    ts: TimelineState,
    st: StudioState,
    origPeaks: FloatArray,
    clipPeaks: Map<String, FloatArray>,
    cues: List<Cue>,
    posMs: Long,
    durMs: Long,
    follow: Boolean,
    onSeek: (Long) -> Unit,
    onSelectClip: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val rulerH = with(density) { 22.dp.toPx() }
    val gap = with(density) { 4.dp.toPx() }
    val subsH = with(density) { 26.dp.toPx() }
    val trackH = with(density) { 76.dp.toPx() }
    val subsTop = rulerH + gap
    val origTop = subsTop + subsH + gap
    val dubTop = origTop + trackH + gap

    val curSt by rememberUpdatedState(st)
    val curSeek by rememberUpdatedState(onSeek)
    val curSelect by rememberUpdatedState(onSelectClip)

    LaunchedEffect(posMs, follow) {
        if (follow && ts.widthPx > 0) {
            val x = (posMs - ts.scrollMs) * ts.pps / 1000f
            if (x > ts.widthPx * 0.85f || x < 0f) {
                ts.scrollMs = max(0f, posMs - 0.15f * ts.widthPx * 1000f / ts.pps)
            }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(212.dp)
            .onSizeChanged { ts.widthPx = it.width }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> ts.zoomBy(zoom, centroid.x, pan.x) }
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val ms = (ts.scrollMs + o.x * 1000f / ts.pps).toLong().coerceAtLeast(0)
                    if (o.y >= dubTop && o.y <= dubTop + trackH) {
                        val hit = curSt.clips.lastOrNull { ms >= it.startMs && ms < it.startMs + it.durMs }
                        curSelect(hit?.id)
                    }
                    curSeek(ms)
                }
            },
    ) {
        val w = size.width
        val pps = ts.pps
        val scroll = ts.scrollMs
        fun x(ms: Long): Float = (ms - scroll) * pps / 1000f

        val label = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)

        // ---- ruler
        drawRect(cs.surface, Offset.Zero, Size(w, size.height))
        val steps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f)
        val stepSec = steps.firstOrNull { it * pps >= 80f } ?: 600f
        var k = floor(scroll / 1000f / stepSec).toInt()
        while (true) {
            val tSec = k * stepSec
            val xx = tSec * pps - scroll * pps / 1000f
            if (xx > w) break
            if (xx >= -1f) {
                drawLine(cs.outline, Offset(xx, rulerH - 8f), Offset(xx, rulerH), 1f)
                val mins = (tSec / 60f).toInt()
                val secs = tSec - mins * 60
                val text = if (stepSec < 1f) "%d:%04.1f".format(mins, secs) else "%d:%02d".format(mins, secs.toInt())
                drawText(tm, text, Offset(xx + 3f, 2f), label)
            }
            k++
        }

        // ---- subtitle track
        drawRoundRect(cs.surfaceVariant.copy(alpha = 0.5f), Offset(0f, subsTop), Size(w, subsH), CornerRadius(6f, 6f))
        for (c in cues) {
            val a = x(c.startMs)
            val b = x(c.endMs)
            if (b < 0f || a > w) continue
            val bw = max(2f, b - a)
            drawRoundRect(cs.tertiaryContainer, Offset(a, subsTop + 2f), Size(bw, subsH - 4f), CornerRadius(4f, 4f))
            if (bw > 36f && c.text.isNotBlank()) {
                clipRect(a, subsTop, a + bw, subsTop + subsH) {
                    drawText(
                        tm, c.text.replace('\n', ' '), Offset(max(a, 0f) + 3f, subsTop + 5f),
                        TextStyle(fontSize = 10.sp, color = cs.onTertiaryContainer), softWrap = false, maxLines = 1,
                    )
                }
            }
        }

        // ---- original track
        drawRoundRect(cs.surfaceVariant, Offset(0f, origTop), Size(w, trackH), CornerRadius(6f, 6f))
        if (origPeaks.isNotEmpty()) {
            drawWave(origPeaks, x(0), x(origPeaks.size * 10L), origTop, trackH, cs.secondary) { px -> scroll + px * 1000f / pps }
        } else {
            drawText(tm, if (st.preparing) "Reading original audio…" else "No original audio", Offset(8f, origTop + 6f), label)
        }
        drawText(tm, "ORIGINAL", Offset(6f, origTop + 4f), TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant))
        for (m in st.mutes) {
            val a = x(m.a)
            val b = x(m.b)
            if (b < 0f || a > w) continue
            drawRect(Color(0x77F44336), Offset(a, origTop), Size(b - a, trackH))
        }
        for (c in st.clips) {
            if (!c.replaceOriginal) continue
            val a = x(c.startMs)
            val b = x(c.startMs + c.durMs)
            if (b < 0f || a > w) continue
            drawRect(cs.surface.copy(alpha = 0.7f), Offset(a, origTop), Size(b - a, trackH))
        }

        // ---- dub track
        drawRoundRect(cs.surfaceVariant, Offset(0f, dubTop), Size(w, trackH), CornerRadius(6f, 6f))
        drawText(tm, "DUB", Offset(6f, dubTop + 4f), TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant))
        for (c in st.clips) {
            val a = x(c.startMs)
            val b = x(c.startMs + c.durMs)
            if (b < 0f || a > w) continue
            drawRoundRect(cs.primaryContainer, Offset(a, dubTop + 3f), Size(max(2f, b - a), trackH - 6f), CornerRadius(8f, 8f))
            clipRect(a, dubTop, b, dubTop + trackH) {
                val peaks = clipPeaks[c.file]
                if (peaks != null && peaks.isNotEmpty()) {
                    drawWave(peaks, a, b, dubTop + 6f, trackH - 12f, cs.onPrimaryContainer) { px ->
                        c.srcStartMs + (scroll + px * 1000f / pps - c.startMs)
                    }
                }
                drawText(tm, "Take ${c.id}", Offset(max(a, 0f) + 6f, dubTop + 6f), TextStyle(fontSize = 10.sp, color = cs.onPrimaryContainer))
            }
            if (c.id == st.selClip) {
                drawRoundRect(cs.primary, Offset(a, dubTop + 3f), Size(max(2f, b - a), trackH - 6f), CornerRadius(8f, 8f), style = Stroke(width = 4f))
            }
        }

        // ---- In / Out range
        val ri = st.inMs
        val ro = st.outMs
        if (ri != null && ro != null) {
            drawRect(cs.primary.copy(alpha = 0.18f), Offset(x(ri), subsTop), Size(x(ro) - x(ri), size.height - subsTop))
        }
        if (ri != null) {
            drawLine(cs.primary, Offset(x(ri), subsTop), Offset(x(ri), size.height), 3f)
            drawText(tm, "IN", Offset(x(ri) + 3f, rulerH - 12f), TextStyle(fontSize = 9.sp, color = cs.primary))
        }
        if (ro != null) {
            drawLine(cs.primary, Offset(x(ro), subsTop), Offset(x(ro), size.height), 3f)
            drawText(tm, "OUT", Offset(x(ro) + 3f, rulerH - 12f), TextStyle(fontSize = 9.sp, color = cs.primary))
        }

        // ---- end of video + playhead
        if (durMs > 0) drawLine(cs.outline, Offset(x(durMs), subsTop), Offset(x(durMs), size.height), 2f)
        val px = x(posMs)
        drawLine(cs.error, Offset(px, 0f), Offset(px, size.height), 3f)
    }
}
