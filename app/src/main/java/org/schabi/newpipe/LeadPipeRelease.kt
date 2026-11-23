package org.schabi.newpipe

import com.grack.nanojson.JsonParser

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
        private const val REPOSITORY = "darvilp/PipePipeClient"
        const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
        private const val TAG_PREFIX = "all-features/v"
        private val versionPattern = Regex("""\d+\.\d+\.\d+(?:-beta\d+)?-unofficial\.(\d+)""")

        @JvmStatic
        fun releasePageForVersion(version: String): String {
            if (revisionOf(version) == null) return RELEASES_URL
            return "$RELEASES_URL/tag/$TAG_PREFIX$version"
        }

        fun fromJson(body: String, supportedAbis: Array<String>): LeadPipeRelease? {
            val release = JsonParser.`object`().from(body)
            if (release.getBoolean("draft", true) || release.getBoolean("prerelease", true)) {
                return null
            }
            val tag = release.getString("tag_name", "")
            if (!tag.startsWith(TAG_PREFIX)) return null
            val version = tag.removePrefix(TAG_PREFIX)
            val revision = revisionOf(version) ?: return null

            val assets = release.getArray("assets")
            val uploadedNames = (0 until assets.size)
                .mapNotNull { assets.getObject(it) }
                .filter { it.getString("state") == "uploaded" }
                .mapNotNull { it.getString("name") }
            val apkName = supportedAbis.firstNotNullOfOrNull { abi ->
                val expected = Regex(
                    """PipePipe-all-features-${Regex.escape(version)}-[0-9a-f]{9}-\d{8}T\d{6}Z-${Regex.escape(abi)}\.apk"""
                )
                uploadedNames.singleOrNull(expected::matches)
            }
            return LeadPipeRelease(
                version,
                revision,
                apkName?.let { "$RELEASES_URL/download/${tag.replace("/", "%2F")}/$it" },
                releasePageForVersion(version)
            )
        }

        private fun revisionOf(version: String): Int? {
            return versionPattern.matchEntire(version)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
