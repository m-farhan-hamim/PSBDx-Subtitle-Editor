package com.subtitle.psbdx.domain

import kotlin.math.roundToLong

/** Parsing and writing for every supported subtitle format. Pure Kotlin, no Android deps. */
object SubtitleCodec {

    private const val DEFAULT_FPS = 23.976
    private val TIME = Regex("""^\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})(?:[.,:](\d{1,3}))?\s*$""")
    private val TAGS_KEEP_IBU = Regex("""<(?!/?[ibu]>)[^>]+>""")
    private val BRACE_TAGS = Regex("""\{[^}]*\}""")
    private val ARROW = Regex("""\s*-->\s*""")

    // ---------- Detection ----------

    fun detect(fileName: String?, content: String): SubtitleFormat {
        SubtitleFormat.fromExtension(fileName?.substringAfterLast('.', ""))?.let { fromExt ->
            // .sub can be MicroDVD or SubViewer; both handled by SUB parser.
            return fromExt
        }
        val head = content.removePrefix("\uFEFF").trimStart().take(400)
        return when {
            head.startsWith("WEBVTT") -> SubtitleFormat.VTT
            head.contains("[Script Info]", true) || head.contains("[Events]", true) -> SubtitleFormat.ASS
            Regex("""^\{\d+\}\{\d+\}""").containsMatchIn(head) -> SubtitleFormat.SUB
            Regex("""\d{1,2}:\d{2}:\d{2}\.\d{3},\d{1,2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(head) -> SubtitleFormat.SBV
            head.contains("-->") -> SubtitleFormat.SRT
            else -> SubtitleFormat.TXT
        }
    }

    // ---------- Time helpers ----------

    fun parseTime(s: String): Long? {
        val m = TIME.matchEntire(s) ?: return null
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).ifEmpty { "000" }.toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }

    fun formatTime(ms: Long, msSep: Char = ',', hourDigits: Int = 2): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val milli = t % 1000
        return "%0${hourDigits}d:%02d:%02d%c%03d".format(h, m, s, msSep, milli)
    }

    private fun formatAss(ms: Long): String {
        val t = (ms.coerceAtLeast(0) / 10.0).roundToLong() * 10
        val h = t / 3_600_000
        val m = (t / 60_000) % 60
        val s = (t / 1000) % 60
        val cs = (t % 1000) / 10
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    // ---------- Parsing ----------

    fun parse(raw: String, format: SubtitleFormat): List<Cue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when (format) {
            SubtitleFormat.SRT -> parseArrowBlocks(text, vtt = false)
            SubtitleFormat.VTT -> parseArrowBlocks(text, vtt = true)
            SubtitleFormat.SBV -> parseSbv(text)
            SubtitleFormat.ASS -> parseAss(text)
            SubtitleFormat.SUB -> parseSub(text)
            SubtitleFormat.TXT -> parseTxt(text)
        }
        return cues.sortedBy { it.startMs }.mapIndexed { i, c -> c.copy(id = (i + 1).toLong()) }
    }

    private fun blocks(text: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) {
                if (cur.isNotEmpty()) { out.add(cur); cur = mutableListOf() }
            } else cur.add(line.trimEnd())
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun parseArrowBlocks(text: String, vtt: Boolean): List<Cue> {
        val cues = mutableListOf<Cue>()
        for (b in blocks(text)) {
            val ai = b.indexOfFirst { it.contains("-->") }
            if (ai < 0) continue
            val parts = b[ai].split(ARROW)
            if (parts.size < 2) continue
            val start = parseTime(parts[0].trim()) ?: continue
            val end = parseTime(parts[1].trim().split(Regex("\\s+")).first()) ?: continue
            var body = b.drop(ai + 1).joinToString("\n")
            if (vtt) body = body.replace(TAGS_KEEP_IBU, "")
            cues.add(Cue(startMs = start, endMs = end, text = body))
        }
        return cues
    }

    private fun parseSbv(text: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        for (b in blocks(text)) {
            val times = b[0].split(',')
            if (times.size != 2) continue
            val start = parseTime(times[0].trim()) ?: continue
            val end = parseTime(times[1].trim()) ?: continue
            cues.add(Cue(startMs = start, endMs = end, text = b.drop(1).joinToString("\n")))
        }
        return cues
    }

    private fun parseAss(text: String): List<Cue> {
        var fmt = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        val cues = mutableListOf<Cue>()
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.startsWith("[")) { inEvents = l.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (l.startsWith("Format:", true)) {
                fmt = l.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (l.startsWith("Dialogue:", true)) {
                val fields = l.substringAfter(':').trimStart().split(',', limit = fmt.size)
                val si = fmt.indexOf("start"); val ei = fmt.indexOf("end"); val ti = fmt.indexOf("text")
                if (si < 0 || ei < 0 || ti < 0 || fields.size <= ti) continue
                val start = parseTime(fields[si].trim()) ?: continue
                val end = parseTime(fields[ei].trim()) ?: continue
                val body = fields[ti].replace("\\N", "\n").replace("\\n", "\n").replace(BRACE_TAGS, "")
                cues.add(Cue(startMs = start, endMs = end, text = body))
            }
        }
        return cues
    }

    private fun parseSub(text: String): List<Cue> {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.startsWith("{")) parseMicroDvd(text) else parseSubViewer(text)
    }

    private fun parseMicroDvd(text: String): List<Cue> {
        val re = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")
        var fps = DEFAULT_FPS
        val cues = mutableListOf<Cue>()
        var firstLine = true
        for (line in text.split('\n')) {
            val m = re.matchEntire(line.trim()) ?: continue
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val body = m.groupValues[3]
            if (firstLine && a <= 1 && b <= 1) {
                val f = body.trim().toDoubleOrNull()
                if (f != null && f in 5.0..120.0) { fps = f; firstLine = false; continue }
            }
            firstLine = false
            cues.add(
                Cue(
                    startMs = (a * 1000 / fps).roundToLong(),
                    endMs = (b * 1000 / fps).roundToLong(),
                    text = body.replace(BRACE_TAGS, "").replace("|", "\n"),
                )
            )
        }
        return cues
    }

    private fun parseSubViewer(text: String): List<Cue> {
        val re = Regex("""^(\d+:\d+:\d+[.,]\d+)\s*,\s*(\d+:\d+:\d+[.,]\d+)$""")
        val cues = mutableListOf<Cue>()
        for (b in blocks(text)) {
            val m = re.matchEntire(b[0].trim()) ?: continue
            val start = parseTime(m.groupValues[1]) ?: continue
            val end = parseTime(m.groupValues[2]) ?: continue
            cues.add(Cue(startMs = start, endMs = end, text = b.drop(1).joinToString("\n").replace("[br]", "\n")))
        }
        return cues
    }

    private fun parseTxt(text: String): List<Cue> {
        val stamp = Regex("""^\[?(\d+:\d{2}(?::\d{2})?[.,]\d{1,3})]?\s*(.*)$""")
        val lines = text.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        val hasStamps = lines.any { stamp.matches(it.trim()) }
        if (!hasStamps) {
            return lines.mapIndexed { i, l -> Cue(startMs = i * 2500L, endMs = i * 2500L + 2000, text = l.trim()) }
        }
        val starts = mutableListOf<Long>()
        val texts = mutableListOf<String>()
        for (l in lines) {
            val m = stamp.matchEntire(l.trim())
            val t = m?.let { parseTime(it.groupValues[1]) }
            if (m != null && t != null) { starts.add(t); texts.add(m.groupValues[2]) }
            else if (texts.isNotEmpty()) texts[texts.lastIndex] = texts.last() + "\n" + l.trim()
        }
        return starts.indices.map { i ->
            val end = if (i + 1 < starts.size) starts[i + 1] else starts[i] + 2500
            Cue(startMs = starts[i], endMs = maxOf(end, starts[i] + 1), text = texts[i])
        }
    }

    // ---------- Writing ----------

    fun write(cues: List<Cue>, format: SubtitleFormat, title: String = "Subtitles"): String {
        // Empty subtitle boxes are never written out.
        val list = cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        return when (format) {
            SubtitleFormat.SRT -> list.mapIndexed { i, c ->
                "${i + 1}\n${formatTime(c.startMs)} --> ${formatTime(c.endMs)}\n${c.text}\n"
            }.joinToString("\n")

            SubtitleFormat.VTT -> "WEBVTT\n\n" + list.joinToString("\n") { c ->
                "${formatTime(c.startMs, '.')} --> ${formatTime(c.endMs, '.')}\n${c.text}\n"
            }

            SubtitleFormat.SBV -> list.joinToString("\n") { c ->
                "${formatTime(c.startMs, '.', 1)},${formatTime(c.endMs, '.', 1)}\n${c.text}\n"
            }

            SubtitleFormat.ASS -> buildString {
                append("[Script Info]\nTitle: $title\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\n\n")
                append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                append("Style: Default,Arial,64,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,3,1,2,40,40,50,1\n\n")
                append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                list.forEach { c ->
                    append("Dialogue: 0,${formatAss(c.startMs)},${formatAss(c.endMs)},Default,,0,0,0,,${c.text.replace("\n", "\\N")}\n")
                }
            }

            SubtitleFormat.SUB -> buildString {
                append("{1}{1}$DEFAULT_FPS\n")
                list.forEach { c ->
                    val a = (c.startMs * DEFAULT_FPS / 1000).roundToLong()
                    val b = maxOf((c.endMs * DEFAULT_FPS / 1000).roundToLong(), a + 1)
                    append("{$a}{$b}${c.text.replace("\n", "|")}\n")
                }
            }

            SubtitleFormat.TXT -> list.joinToString("\n") { c ->
                "[${formatTime(c.startMs, '.')}] ${c.text.replace("\n", " ")}"
            } + "\n"
        }
    }
}
