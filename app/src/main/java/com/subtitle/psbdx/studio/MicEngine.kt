package com.subtitle.psbdx.studio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File

/** Captures the microphone as 16-bit PCM, optionally writing a WAV, and reports analysis frames. */
class MicEngine(private val bandCount: Int, private val onFrame: (MicFrame) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var writer: Wav.Writer? = null

    /** Returns an error message, or null on success. [out] == null means monitor only. */
    @SuppressLint("MissingPermission")
    fun start(out: File?): String? {
        stop()
        val minBuf = AudioRecord.getMinBufferSize(Wav.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return "Microphone is not available"
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, Wav.SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, Analyzer.N * 4),
            )
        } catch (e: Exception) {
            return e.message ?: "Could not open the microphone"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "Could not open the microphone"
        }
        val w = out?.let { Wav.Writer(it) }
        writer = w
        running = true
        val t = Thread({ loop(rec, w) }, "mic-engine")
        thread = t
        t.start()
        return null
    }

    private fun loop(rec: AudioRecord, w: Wav.Writer?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val analyzer = Analyzer(bandCount)
        val buf = ShortArray(Analyzer.N)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                w?.write(buf, n)
                onFrame(analyzer.analyze(buf, n))
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            w?.close()
        }
    }

    /** Stops capture and returns the number of samples written to the WAV (0 for monitor). */
    fun stop(): Long {
        running = false
        thread?.join(2000)
        thread = null
        val s = writer?.samples ?: 0L
        writer = null
        return s
    }
}
