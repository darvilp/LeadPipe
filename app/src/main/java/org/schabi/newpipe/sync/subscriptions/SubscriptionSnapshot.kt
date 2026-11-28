package org.schabi.newpipe.sync.subscriptions

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.extractor.ServiceList
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.UUID

internal data class SnapshotChannel(val service: String, val url: String, val name: String) {
    val key get() = service to url
}

internal data class SubscriptionSnapshot(
    val origin: String,
    val revision: Long,
    val channels: List<SnapshotChannel>
) {
    fun encode(): String {
        val writer = JsonWriter.string().`object`()
            .value("type", "subscriptions")
            .value("version", 1)
            .value("origin", origin)
            .value("revision", revision)
            .array("channels")
        for (channel in channels) {
            writer.`object`().value("service", channel.service).value("url", channel.url)
                .value("name", channel.name).end()
        }
        return writer.end().end().done()
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_CHANNELS = 20_000

        private val services = mapOf(
            "youtube" to ServiceList.YouTube,
            "bilibili" to ServiceList.BiliBili,
            "niconico" to ServiceList.NicoNico
        )

        fun serviceIds() = services.values.map { it.serviceId }

        fun serviceKey(id: Int): String? = services.entries.firstOrNull { it.value.serviceId == id }?.key
        fun serviceId(key: String): Int = requireNotNull(services[key]) { "Unsupported subscription service" }.serviceId

        fun canonicalUrl(key: String, url: String): String {
            val service = requireNotNull(services[key]) { "Unsupported subscription service" }
            require(url.length <= 2048) { "Invalid channel URL" }
            val parsed = URI(url)
            require(parsed.scheme in setOf("http", "https") && safeHost(key, parsed)) { "Invalid channel URL" }
            val secureUrl = if (parsed.scheme == "http") "https" + url.substring(4) else url
            val canonical = service.channelLHFactory.fromUrl(secureUrl).url
            val result = URI(canonical)
            require(result.scheme == "https" && safeHost(key, result)) { "Invalid channel URL" }
            return canonical
        }

        private fun safeHost(key: String, uri: URI): Boolean {
            val host = uri.host?.lowercase() ?: return false
            if (uri.userInfo != null || uri.port !in setOf(-1, 443)) return false
            val domain = when (key) {
                "youtube" -> "youtube.com"
                "bilibili" -> "bilibili.com"
                "niconico" -> "nicovideo.jp"
                else -> return false
            }
            return host == domain || host.endsWith(".$domain")
        }

        fun validOrigin(origin: String): Boolean =
            runCatching { UUID.fromString(origin).toString() == origin }.getOrDefault(false)

        fun decode(text: String): SubscriptionSnapshot {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Subscription snapshot is too large" }
            val document = JsonParser.`object`().from(text)
            require(document["type"] == "subscriptions" && document["version"] == 1) {
                "Unsupported subscription snapshot version"
            }
            val origin = document["origin"] as? String
            val revision = document["revision"]
            require(origin != null && validOrigin(origin) && (revision is Long || revision is Int)) {
                "Invalid subscription snapshot identity"
            }
            val sequence = (revision as Number).toLong()
            require(sequence > 0) { "Invalid subscription snapshot revision" }
            val rows = document["channels"] as? List<*>
            require(rows != null && rows.size <= MAX_CHANNELS) { "Invalid subscription snapshot channels" }
            val channels = rows.map {
                require(it is JsonObject) { "Invalid subscription snapshot channel" }
                val service = it["service"] as? String
                val url = it["url"] as? String
                val name = it["name"] as? String
                require(service != null && url != null && name != null && name.length <= 1024) {
                    "Invalid subscription snapshot channel"
                }
                SnapshotChannel(service, canonicalUrl(service, url), name)
            }
            require(channels.distinctBy { it.key }.size == channels.size) { "Duplicate channel in subscription snapshot" }
            return SubscriptionSnapshot(origin, sequence, channels)
        }

        fun read(input: InputStream): SubscriptionSnapshot {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_BYTES) { "Subscription snapshot is too large" }
                output.write(buffer, 0, read)
            }
            return decode(output.toString(Charsets.UTF_8.name()))
        }
    }
}
