package org.schabi.newpipe

import com.grack.nanojson.JsonParser
import java.net.URI
import java.net.URISyntaxException

internal class LeadPipeRelease private constructor(
    val version: String,
    private val revision: Int,
    val downloadUrl: String?,
    val releaseUrl: String
) {
    // The fork counter increases across changes to the upstream version.
    fun compareToInstalled(installedVersion: String): Int? {
        return revisionOf(installedVersion)?.let(revision::compareTo)
    }

    companion object {
        private const val REPOSITORY = "darvilp/LeadPipe"
        private const val LEGACY_REPOSITORY = "darvilp/PipePipeClient"
        const val API_URL = "https://api.github.com/repositories/1316188248/releases/latest"
        const val NAMED_API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
        private const val TAG_PREFIX = "leadpipe/v"
        private const val LEGACY_TAG_PREFIX = "all-features/v"
        private const val BRIDGE_VERSION = "5.6.0.24"
        private const val LEGACY_BRIDGE_VERSION = "5.6.0-unofficial.24"
        private val versionPattern = Regex("""\d+\.\d+\.\d+\.(\d+)""")
        private val legacyVersionPattern = Regex("""\d+\.\d+\.\d+(?:-beta\d+)?-unofficial\.(\d+)""")

        @JvmStatic
        fun releasePageForVersion(version: String): String {
            val tag = when {
                version == BRIDGE_VERSION -> "$LEGACY_TAG_PREFIX$LEGACY_BRIDGE_VERSION"
                versionPattern.matches(version) -> "$TAG_PREFIX$version"
                legacyVersionPattern.matches(version) -> "$LEGACY_TAG_PREFIX$version"
                else -> return RELEASES_URL
            }
            return "$RELEASES_URL/tag/$tag"
        }

        fun fromJson(body: String, supportedAbis: Array<String>): LeadPipeRelease? {
            val release = JsonParser.`object`().from(body)
            if (release.getBoolean("draft", true) || release.getBoolean("prerelease", true)) {
                return null
            }
            val tag = release.getString("tag_name", "")
            val tagVersion: String
            val apkPrefix: String
            val pattern: Regex
            when {
                tag.startsWith(TAG_PREFIX) -> {
                    tagVersion = tag.removePrefix(TAG_PREFIX)
                    apkPrefix = "LeadPipe"
                    pattern = versionPattern
                }
                tag.startsWith(LEGACY_TAG_PREFIX) -> {
                    tagVersion = tag.removePrefix(LEGACY_TAG_PREFIX)
                    apkPrefix = "PipePipe-all-features"
                    pattern = legacyVersionPattern
                }
                else -> return null
            }
            val revision = pattern.matchEntire(tagVersion)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            val version = if (tagVersion == LEGACY_BRIDGE_VERSION) BRIDGE_VERSION else tagVersion

            val assets = release.getArray("assets")
            val uploadedAssets = (0 until assets.size)
                .mapNotNull { assets.getObject(it) }
                .filter { it.getString("state") == "uploaded" }
            val apk = supportedAbis.firstNotNullOfOrNull { abi ->
                val expected = Regex(
                    """$apkPrefix-${Regex.escape(tagVersion)}-[0-9a-f]{9}-\d{8}T\d{6}Z-${Regex.escape(abi)}\.apk"""
                )
                uploadedAssets.singleOrNull { it.getString("name")?.let(expected::matches) == true }
            }
            val downloadUrl = apk?.getString("name")?.let { name ->
                knownReleaseUrl(apk.getString("browser_download_url"), "download/$tag/$name")
                    ?: "$RELEASES_URL/download/${tag.replace("/", "%2F")}/$name"
            }
            return LeadPipeRelease(
                version,
                revision,
                downloadUrl,
                knownReleaseUrl(release.getString("html_url"), "tag/$tag")
                    ?: "$RELEASES_URL/tag/$tag"
            )
        }

        private fun knownReleaseUrl(url: String?, path: String): String? {
            if (url == null) return null
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return null
            }
            if (uri.scheme != "https" || uri.rawAuthority != "github.com"
                || uri.rawQuery != null || uri.rawFragment != null
            ) return null
            return url.takeIf {
                uri.path == "/$REPOSITORY/releases/$path"
                    || uri.path == "/$LEGACY_REPOSITORY/releases/$path"
            }
        }

        private fun revisionOf(version: String): Int? {
            return (versionPattern.matchEntire(version) ?: legacyVersionPattern.matchEntire(version))
                ?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
