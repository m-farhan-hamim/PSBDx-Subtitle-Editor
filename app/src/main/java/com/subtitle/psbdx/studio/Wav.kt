package com.subtitle.psbdx.studio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Mono 16-bit PCM WAV helpers. Everything in the studio runs at [SR]. */
object Wav {
    const val SR = 44100
    const val HEADER = 44

    fun msToSamples(ms: Long): Long = ms * SR / 1000
    fun samplesToMs(samples: Long): Long = samples * 1000 / SR

    class Writer(file: File) {
        private val raf = RandomAccessFile(file, "rw")
        var samples = 0L
            private set

        init {
            raf.setLength(0)
            raf.write(ByteArray(HEADER))
        }

        fun write(buf: ShortArray, n: Int) {
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) bb.putShort(buf[i])
            raf.write(bb.array(), 0, n * 2)
            samples += n
        }

        fun close() {
            val dataLen = samples * 2
            val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray())
            h.putInt((36 + dataLen).toInt())
            h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray())
            h.putInt(16)
            h.putShort(1)
            h.putShort(1)
            h.putInt(SR)
            h.putInt(SR * 2)
            h.putShort(2)
            h.putShort(16)
            h.put("data".toByteArray())
            h.putInt(dataLen.toInt())
            raf.seek(0)
            raf.write(h.array())
            raf.close()
        }
    }

    fun sampleCount(f: File): Long = ((f.length() - HEADER) / 2).coerceAtLeast(0)

    fun readAll(f: File): ShortArray {
        val bytes = f.readBytes()
        if (bytes.size <= HEADER) return ShortArray(0)
        val sb = ByteBuffer.wrap(bytes, HEADER, bytes.size - HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(sb.remaining())
        sb.get(out)
        return out
    }

    /** Max absolute amplitude (0..1) per [window] samples. */
    fun peaks(f: File, window: Int): FloatArray {
        val total = sampleCount(f)
        if (total <= 0) return FloatArray(0)
        val out = FloatArray(((total + window - 1) / window).toInt())
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(HEADER.toLong())
            val buf = ByteArray(window * 2 * 64)
            var global = 0L
            while (true) {
                val read = raf.read(buf)
                if (read <= 1) break
                val bb = ByteBuffer.wrap(buf, 0, read - (read % 2)).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.remaining() >= 2) {
                    val idx = (global / window).toInt()
                    if (idx < out.size) out[idx] = max(out[idx], abs(bb.short.toInt()) / 32768f)
                    global++
                }
            }
        }
        return out
    }
}
