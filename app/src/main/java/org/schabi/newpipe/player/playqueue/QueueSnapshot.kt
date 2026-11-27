package org.schabi.newpipe.player.playqueue

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.RepeatMode
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem

internal data class QueueSnapshot(
    val entries: List<PlayerMediaItem>,
    val selectedId: String?,
    val positionMs: Long,
    val repeatMode: Int,
    val originalOrder: List<String>?,
    val automaticTailId: String?,
) {
    companion object {
        private const val VERSION = 1
        val EMPTY = QueueSnapshot(emptyList(), null, 0, 0, null, null)

        fun decode(json: String): QueueSnapshot? {
            val root = try {
                JsonParser.`object`().from(json)
            } catch (_: JsonParserException) {
                return null
            }
            if (root.getInt("version") != VERSION) return null
            val encoded = root["entries"] as? JsonArray ?: return null
            if (encoded.isEmpty()) return EMPTY

            val entries = encoded.map { (it as? JsonObject)?.let(::decodeEntry) ?: return null }
            val ids = entries.mapTo(HashSet()) { it.uuid }
            val selected = root.getString("selected")
            val position = root.getLong("positionMs", -1)
            val repeat = root.getInt("repeat", -1)
            if (ids.size != entries.size || selected !in ids || position < 0 ||
                repeat !in RepeatMode.entries.indices
            ) return null

            val order = root["originalOrder"]?.let { raw ->
                val original = (raw as? JsonArray)?.map { it as? String ?: return null }
                    ?: return null
                if (original.size != ids.size || original.toSet() != ids) return null
                original
            }
            val tail = root.getString("automaticTail")
            if (tail != null && tail != entries.last().uuid) return null
            return QueueSnapshot(entries, selected, position, repeat, order, tail)
        }

        private fun decodeEntry(entry: JsonObject): PlayerMediaItem? {
            val id = entry.getString("id")?.takeIf(String::isNotBlank) ?: return null
            val url = entry.getString("url")?.takeIf(String::isNotBlank) ?: return null
            val service = (entry["service"] as? Number)?.toInt() ?: return null
            val type = StreamType.entries.firstOrNull { it.name == entry.getString("type") }
                ?: return null
            return PlayerMediaItem.Builder().uuid(id).url(url).serviceId(service)
                .title(entry.getString("title", ""))
                .uploader(entry.getString("uploader", ""))
                .uploaderUrl(entry.getString("uploaderUrl"))
                .duration(entry.getLong("duration"))
                .thumbnailUrl(entry.getString("thumbnail"))
                .streamType(type)
                .build()
        }
    }

    fun encode(): String = JsonWriter.string(
        mapOf(
            "version" to VERSION,
            "selected" to selectedId,
            "positionMs" to positionMs,
            "repeat" to repeatMode,
            "originalOrder" to originalOrder,
            "automaticTail" to automaticTailId,
            "entries" to entries.map {
                mapOf(
                    "id" to it.uuid, "url" to it.url, "service" to it.serviceId,
                    "title" to it.title, "uploader" to it.uploader, "uploaderUrl" to it.uploaderUrl,
                    "duration" to it.duration, "thumbnail" to it.thumbnailUrl,
                    "type" to it.streamType.name,
                )
            },
        )
    )
}
