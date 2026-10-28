package com.subtitle.psbdx.studio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/** A recorded (or imported) piece of dub audio placed on the timeline. */
data class Clip(
    val id: Long,
    val file: String,
    val startMs: Long,
    val srcStartMs: Long,
    val durMs: Long,
    val gain: Float = 1f,
    val replaceOriginal: Boolean = true,
)

data class MsRange(val a: Long, val b: Long)

data class MixModel(val clips: List<Clip>, val mutes: List<MsRange>, val origGain: Float)

/**
 * Renders the mix: original audio (silenced under muted ranges and "replace original" clips)
 * plus all dub clips. Not thread-safe; create one per consumer and close it.
 */
class Mixer(private val dir: File, private val model: MixModel, originalFile: File?) : Closeable {
    private companion object {
        const val FADE = 353   // ~8 ms clip edge fade
        const val RAMP = 220   // ~5 ms gate ramp
    }

    private val orig: RandomAccessFile? =
        originalFile?.takeIf { it.exists() && it.length() > Wav.HEADER }?.let { RandomAccessFile(it, "r") }
    private val cache = HashMap<String, ShortArray>()
    private val gateIntervals: List<LongArray> = buildList {
        model.mutes.forEach { add(longArrayOf(Wav.msToSamples(it.a), Wav.msToSamples(it.b))) }
        model.clips.filter { it.replaceOriginal }.forEach {
            add(longArrayOf(Wav.msToSamples(it.startMs), Wav.msToSamples(it.startMs + it.durMs)))
        }
    }

    val originalSamples: Long = orig?.let { (it.length() - Wav.HEADER) / 2 } ?: 0L

    private fun load(name: String): ShortArray =
        cache.getOrPut(name) { runCatching { Wav.readAll(File(dir, name)) }.getOrDefault(ShortArray(0)) }

    fun render(startSample: Long, n: Int, out: ShortArray, original: Boolean, clips: Boolean, applyGate: Boolean) {
        val acc = FloatArray(n)

        if (original && orig != null && startSample >= 0) {
            val off = Wav.HEADER + startSample * 2
            if (off < orig.length()) {
                val bytes = ByteArray(n * 2)
                orig.seek(off)
                var read = 0
                while (read < bytes.size) {
                    val r = orig.read(bytes, read, bytes.size - read)
                    if (r <= 0) break
                    read += r
                }
                val count = read / 2
                for (i in 0 until count) {
                    val lo = bytes[2 * i].toInt() and 0xFF
                    val hi = bytes[2 * i + 1].toInt()
                    acc[i] = ((hi shl 8) or lo).toShort() * model.origGain
                }
                if (applyGate && gateIntervals.isNotEmpty()) {
                    val gate = FloatArray(n) { 1f }
                    var touched = false
                    for (iv in gateIntervals) {
                        val a = iv[0]
                        val b = iv[1]
                        val lo = max(startSample, a - RAMP)
                        val hi = min(startSample + n, b + RAMP)
                        if (lo >= hi) continue
                        touched = true
                        for (t in lo until hi) {
                            val g = if (t < a) (a - t) / RAMP.toFloat() else if (t >= b) (t - b + 1) / RAMP.toFloat() else 0f
                            val idx = (t - startSample).toInt()
                            if (g < gate[idx]) gate[idx] = g
                        }
                    }
                    if (touched) for (i in 0 until n) acc[i] *= gate[i]
                }
            }
        }

        if (clips) {
            for (c in model.clips) {
                val cs = Wav.msToSamples(c.startMs)
                val ce = cs + Wav.msToSamples(c.durMs)
                if (ce <= startSample || cs >= startSample + n) continue
                val data = load(c.file)
                val src0 = Wav.msToSamples(c.srcStartMs)
                val from = max(cs, startSample)
                val to = min(ce, startSample + n)
                for (t in from until to) {
                    val si = src0 + (t - cs)
                    if (si >= data.size) break
                    var g = c.gain
                    val a = t - cs
                    if (a < FADE) g *= a / FADE.toFloat()
                    val b = ce - t
                    if (b < FADE) g *= b / FADE.toFloat()
                    acc[(t - startSample).toInt()] += data[si.toInt()] * g
                }
            }
        }

        for (i in 0 until n) out[i] = acc[i].toInt().coerceIn(-32768, 32767).toShort()
    }

    override fun close() {
        try { orig?.close() } catch (_: Exception) {}
    }
}

/** Real-time playback of a [Mixer] through AudioTrack. Owns (and closes) the mixer. */
class PlayEngine(
    private val mixer: Mixer,
    private val startSample: Long,
    private val totalSamples: Long,
    private val original: Boolean,
    private val clips: Boolean,
    private val gate: Boolean,
    private val device: AudioDeviceInfo?,
    private val onEnd: () -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        val t = Thread({ loop() }, "play-engine")
        thread = t
        t.start()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var track: AudioTrack? = null
        var natural = false
        try {
            val minBuf = AudioTrack.getMinBufferSize(Wav.SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Wav.SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (device != null) track.setPreferredDevice(device)
            track.play()
            val buf = ShortArray(2048)
            var pos = startSample
            while (running && pos < totalSamples) {
                mixer.render(pos, buf.size, buf, original, clips, gate)
                track.write(buf, 0, buf.size)
                pos += buf.size
            }
            natural = running
        } catch (_: Exception) {
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            mixer.close()
        }
        if (natural) onEnd()
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }
}
