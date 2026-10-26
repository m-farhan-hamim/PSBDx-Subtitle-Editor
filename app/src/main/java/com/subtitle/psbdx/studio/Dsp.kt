package com.subtitle.psbdx.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MicFrame(
    val level: Float,      // 0..1 (maps -60..0 dBFS)
    val db: Float,         // RMS dBFS
    val peakDb: Float,     // sample peak dBFS
    val bands: FloatArray, // 0..1 per band, low -> high frequency
    val pitchHz: Float,    // 0 when unvoiced
)

/** Per-frame level, spectrum and pitch analysis. One instance per thread. */
class Analyzer(private val bandCount: Int) {
    companion object { const val N = 2048 }

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val x = FloatArray(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(-2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(-2.0 * PI * it / N).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val edges = IntArray(bandCount + 1) { i ->
        val f = 100.0 * (8000.0 / 100.0).pow(i.toDouble() / bandCount)
        (f / (Wav.SR.toDouble() / N)).toInt().coerceIn(1, N / 2 - 1)
    }
    private val cum = DoubleArray(N + 1)
    private val r = FloatArray(Wav.SR / 70 + 2)

    fun analyze(buf: ShortArray, n: Int): MicFrame {
        val m = max(1, min(n, N))
        var sumSq = 0.0
        var peak = 0
        for (i in 0 until N) {
            if (i < m) {
                val s = buf[i].toInt()
                x[i] = s / 32768f
                sumSq += x[i] * x[i]
                peak = max(peak, abs(s))
            } else x[i] = 0f
        }
        val rms = sqrt(sumSq / m)
        val db = (20.0 * log10(rms + 1e-9)).toFloat()
        val peakDb = (20.0 * log10(peak / 32768.0 + 1e-9)).toFloat()

        for (i in 0 until N) { re[i] = x[i] * win[i]; im[i] = 0f }
        fft()
        val bands = FloatArray(bandCount)
        for (b in 0 until bandCount) {
            val lo = edges[b]
            val hi = max(lo + 1, edges[b + 1])
            var mag = 0f
            for (k in lo until hi) {
                val mm = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mm > mag) mag = mm
            }
            val amp = 4.0 * mag / N
            val d = 20.0 * log10(amp + 1e-9)
            bands[b] = ((d + 90.0) / 65.0).coerceIn(0.0, 1.0).toFloat()
        }
        val pitch = if (db > -48f) pitch(m) else 0f
        return MicFrame(((db + 60f) / 60f).coerceIn(0f, 1f), db, peakDb, bands, pitch)
    }

    private fun fft() {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var start = 0
            while (start < N) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    /** Normalised autocorrelation pitch tracker, 70..500 Hz. */
    private fun pitch(m: Int): Float {
        val minLag = Wav.SR / 500
        val maxLag = min(Wav.SR / 70, m / 2)
        if (maxLag <= minLag + 2) return 0f
        var mean = 0.0
        for (i in 0 until m) mean += x[i]
        mean /= m
        cum[0] = 0.0
        for (i in 0 until m) {
            val v = x[i] - mean
            cum[i + 1] = cum[i] + v * v
        }
        var best = 0f
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until m - lag) sum += (x[i] - mean) * (x[i + lag] - mean)
            val e1 = cum[m - lag]
            val e2 = cum[m] - cum[lag]
            val c = (sum / sqrt(e1 * e2 + 1e-12)).toFloat()
            r[lag] = c
            if (c > best) best = c
        }
        if (best < 0.5f) return 0f
        var bestLag = -1
        for (lag in minLag + 1 until maxLag) {
            if (r[lag] >= 0.9f * best && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) { bestLag = lag; break }
        }
        if (bestLag < 0) return 0f
        val a = r[bestLag - 1]; val b = r[bestLag]; val c = r[bestLag + 1]
        val denom = a - 2 * b + c
        val shift = if (abs(denom) > 1e-6f) 0.5f * (a - c) / denom else 0f
        return Wav.SR / (bestLag + shift.coerceIn(-1f, 1f))
    }
}
