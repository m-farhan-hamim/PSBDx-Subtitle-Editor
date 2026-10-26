package com.subtitle.psbdx.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Exporter {

    fun renderWav(mixer: Mixer, totalSamples: Long, out: File, progress: (Float) -> Unit) {
        val w = Wav.Writer(out)
        try {
            val buf = ShortArray(4096)
            var pos = 0L
            while (pos < totalSamples) {
                val n = minOf(buf.size.toLong(), totalSamples - pos).toInt()
                mixer.render(pos, n, buf, true, true, true)
                w.write(buf, n)
                pos += n
                progress(pos.toFloat() / totalSamples)
            }
        } finally {
            w.close()
        }
    }

    /**
     * Encodes the mix to AAC in an MP4/M4A container. When [videoUri] is given, the original video
     * track is copied untouched (no re-encode) and the mix becomes its only audio track.
     */
    fun encodeAac(context: Context, videoUri: Uri?, mixer: Mixer, totalSamples: Long, out: File, progress: (Float) -> Unit) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vEx: MediaExtractor? = null
        var vTrack = -1
        var enc: MediaCodec? = null
        var muxStarted = false
        try {
            if (videoUri != null) {
                val ex = MediaExtractor()
                vEx = ex
                ex.setDataSource(context, videoUri, null)
                var idx = -1
                for (i in 0 until ex.trackCount) {
                    if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { idx = i; break }
                }
                if (idx >= 0) {
                    ex.selectTrack(idx)
                    val vf = ex.getTrackFormat(idx)
                    if (vf.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
                    vTrack = muxer.addTrack(vf)
                }
            }

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.SR, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc = codec
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val block = ShortArray(2048)
            var aTrack = -1
            var inputDone = false
            var pos = 0L
            fun pts(sample: Long) = sample * 1_000_000L / Wav.SR

            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        val remain = totalSamples - pos
                        if (remain <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts(pos), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(block.size.toLong(), remain, (ib.capacity() / 2).toLong()).toInt()
                            mixer.render(pos, n, block, true, true, true)
                            ib.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until n) ib.putShort(block[i])
                            codec.queueInputBuffer(ii, 0, n * 2, pts(pos), 0)
                            pos += n
                            progress(0.95f * pos / totalSamples)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    aTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxStarted = true
                    val ex = vEx
                    if (ex != null && vTrack >= 0) copyVideo(ex, muxer, vTrack)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(aTrack, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            progress(1f)
        } finally {
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            if (muxStarted) try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { vEx?.release() } catch (_: Exception) {}
        }
    }

    private fun copyVideo(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(8 * 1024 * 1024)
        val bi = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            bi.set(0, size, ex.sampleTime, ex.sampleFlags)
            muxer.writeSampleData(track, buf, bi)
            ex.advance()
        }
    }
}
