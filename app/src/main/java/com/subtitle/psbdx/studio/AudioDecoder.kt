package com.subtitle.psbdx.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.floor

/** Decodes the first audio track of a video into a mono 44.1 kHz 16-bit WAV. */
object AudioDecoder {
    fun decode(context: Context, uri: Uri, out: File, cancelled: () -> Boolean, onProgress: (Float) -> Unit): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return false
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return false
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else Wav.SR

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val writer = Wav.Writer(out)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var phase = 0.0
            var prev = 0f
            var outBuf = ShortArray(8192)
            try {
                while (!outDone) {
                    if (cancelled()) return false
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val size = ex.readSampleData(ib, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val ch = maxOf(channels, 1)
                            val frames = sb.remaining() / ch
                            if (frames > 0) {
                                val mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var sum = 0
                                    for (c in 0 until ch) sum += sb.get().toInt()
                                    mono[f] = sum / ch.toFloat()
                                }
                                val ratio = rate.toDouble() / Wav.SR
                                val need = (frames / ratio).toInt() + 4
                                if (outBuf.size < need) outBuf = ShortArray(need)
                                var cnt = 0
                                while (true) {
                                    val idx = floor(phase).toInt()
                                    if (idx + 1 > frames - 1) break
                                    val frac = (phase - idx).toFloat()
                                    val s0 = if (idx < 0) prev else mono[idx]
                                    val s1 = mono[idx + 1]
                                    outBuf[cnt++] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
                                    phase += ratio
                                }
                                phase -= frames
                                prev = mono[frames - 1]
                                writer.write(outBuf, cnt)
                            }
                            if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                codec.release()
                writer.close()
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            ex.release()
        }
    }
}
