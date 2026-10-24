package com.subtitle.psbdx.domain

data class Cue(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

enum class SubtitleFormat(val ext: String, val label: String, val mime: String) {
    SRT("srt", "SubRip (.srt)", "application/x-subrip"),
    VTT("vtt", "WebVTT (.vtt)", "text/vtt"),
    SBV("sbv", "YouTube (.sbv)", "application/octet-stream"),
    ASS("ass", "SubStation Alpha (.ass)", "application/octet-stream"),
    SUB("sub", "MicroDVD (.sub)", "application/octet-stream"),
    TXT("txt", "Raw text (.txt)", "text/plain");

    companion object {
        fun fromExtension(ext: String?): SubtitleFormat? {
            val e = ext?.lowercase()?.removePrefix(".") ?: return null
            return when (e) {
                "ssa" -> ASS
                else -> entries.firstOrNull { it.ext == e }
            }
        }
    }
}
