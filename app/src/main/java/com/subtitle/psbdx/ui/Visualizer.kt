package com.subtitle.psbdx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subtitle.psbdx.studio.StudioController
import com.subtitle.psbdx.studio.VisualState
import kotlin.math.ln
import kotlin.math.roundToInt

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private const val PITCH_MIN = 60f
private const val PITCH_MAX = 600f

internal fun noteName(hz: Float): String {
    val n = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)
    val r = n.roundToInt()
    val cents = ((n - r) * 100).roundToInt()
    val name = NOTE_NAMES[((r % 12) + 12) % 12] + (Math.floorDiv(r, 12) - 1)
    return "$name ${if (cents >= 0) "+" else ""}$cents¢"
}

/** Live view of what the dubber is saying: loudness over time, pitch contour, spectrum and meters. */
@Composable
fun LiveVisualizer(v: VisualState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val green = Color(0xFF4CAF50)
    val amber = Color(0xFFFFC107)
    val red = Color(0xFFF44336)
    val pitchColor = Color(0xFF00BCD4)

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Readout("Pitch", if (v.pitchHz > 0f) "${v.pitchHz.roundToInt()} Hz · ${noteName(v.pitchHz)}" else "—")
            Readout("Level", if (v.db > -89f) "${v.db.roundToInt()} dB" else "—")
            Readout(
                "Peak",
                if (v.peakDb > -1f) "CLIPPING" else if (v.peakDb > -89f) "${v.peakDb.roundToInt()} dB" else "—",
                if (v.peakDb > -1f) red else null,
            )
        }

        // Loudness envelope (how hard / how long) with the pitch contour (how high) on top.
        Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 6.dp)) {
            drawRoundRect(cs.surfaceVariant, Offset.Zero, size, CornerRadius(8f, 8f))
            val h = size.height
            val w = size.width
            val n = v.levels.size
            val bw = w / n
            val mid = h / 2

            // note reference lines
            for ((hz, label) in listOf(110f to "A2", 220f to "A3", 440f to "A4")) {
                val y = h - (ln(hz / PITCH_MIN) / ln(PITCH_MAX / PITCH_MIN)) * h
                drawLine(cs.outline.copy(alpha = 0.35f), Offset(0f, y), Offset(w, y), 1f)
                drawText(tm, label, Offset(4f, y - 14f), TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant))
            }

            for (i in 0 until n) {
                val lv = v.levels[i]
                if (lv <= 0.01f) continue
                val bh = lv * h * 0.9f
                val col = when {
                    lv > 0.93f -> red
                    lv > 0.75f -> amber
                    else -> green
                }
                drawRect(col.copy(alpha = 0.55f), Offset(i * bw, mid - bh / 2), Size(bw * 0.8f, bh))
            }

            var prev: Offset? = null
            for (i in 0 until n) {
                val p = v.pitches[i]
                if (p <= 0f) { prev = null; continue }
                val y = h - ((ln(p.coerceIn(PITCH_MIN, PITCH_MAX) / PITCH_MIN) / ln(PITCH_MAX / PITCH_MIN)) * h)
                val pt = Offset(i * bw + bw / 2, y)
                prev?.let { drawLine(pitchColor, it, pt, 3f) }
                drawCircle(pitchColor, 4f, pt)
                prev = pt
            }
        }

        // Spectrum
        Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp)) {
            drawRoundRect(cs.surfaceVariant, Offset.Zero, size, CornerRadius(8f, 8f))
            val n = v.bands.size
            val bw = size.width / n
            for (i in 0 until n) {
                val bh = v.bands[i] * size.height
                drawRect(cs.primary.copy(alpha = 0.85f), Offset(i * bw + 1f, size.height - bh), Size(bw - 2f, bh))
            }
        }

        // Level meter
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 4.dp)) {
            drawRect(cs.surfaceVariant, Offset.Zero, size)
            val lv = ((v.db + 60f) / 60f).coerceIn(0f, 1f)
            val col = when {
                lv > 0.93f -> red
                lv > 0.75f -> amber
                else -> green
            }
            drawRect(col, Offset.Zero, Size(size.width * lv, size.height))
            val pk = ((v.peakDb + 60f) / 60f).coerceIn(0f, 1f)
            drawRect(cs.onSurface, Offset(size.width * pk - 2f, 0f), Size(3f, size.height))
        }
    }
}

@Composable
private fun Readout(label: String, value: String, color: Color? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

internal val HIST_SIZE = StudioController.HIST
