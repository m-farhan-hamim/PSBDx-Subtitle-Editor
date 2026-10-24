package com.subtitle.psbdx.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.subtitle.psbdx.domain.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ProjectRepository(private val context: Context, private val dao: ProjectDao) {

    fun observeAll() = dao.observeAll()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun save(entity: ProjectEntity): Long = dao.upsert(entity)
    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Cannot open file")
        decode(bytes)
    }

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write file")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val utf8 = String(b, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(b, charset("windows-1252")) else utf8
    }

    companion object {
        fun cuesToJson(cues: List<Cue>): String {
            val arr = JSONArray()
            cues.forEach { arr.put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) }
            return arr.toString()
        }

        fun cuesFromJson(json: String): List<Cue> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Cue(id = (i + 1).toLong(), startMs = o.getLong("s"), endMs = o.getLong("e"), text = o.getString("t"))
            }
        }
    }
}
