package org.schabi.newpipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadPipeReleaseTest {
    private val armAbis = arrayOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun comparesForkCounterIncludingAcrossUpstreamVersions() {
        val current = LeadPipeRelease.fromJson(releaseJson(), armAbis)!!
        assertEquals(0, current.compareToInstalled("5.6.0-unofficial.22"))
        assertTrue(current.compareToInstalled("5.6.0-beta1-unofficial.19")!! > 0)
        assertTrue(current.compareToInstalled("5.6.1-unofficial.23")!! < 0)
        assertNull(current.compareToInstalled("5.6.0"))
        val next = LeadPipeRelease.fromJson(
            releaseJson(tag = "all-features/v5.7.0-beta2-unofficial.23"), armAbis
        )!!
        assertTrue(next.compareToInstalled(current.version)!! > 0)
    }

    @Test
    fun onlyAcceptsPublishedRegularForkReleases() {
        assertNull(LeadPipeRelease.fromJson(releaseJson(draft = true), armAbis))
        assertNull(LeadPipeRelease.fromJson(releaseJson(prerelease = true), armAbis))
        assertNull(LeadPipeRelease.fromJson(releaseJson(tag = "v5.7.0"), armAbis))
        assertNull(LeadPipeRelease.fromJson(
            releaseJson(tag = "all-features/v5.6.0-unofficial.23/anything"), armAbis
        ))
    }

    @Test
    fun selectsPublishedApkByDeviceAbiOrderAndUsesTheForkDownloadUrl() {
        val arm32 = asset("armeabi-v7a")
        val arm64 = asset("arm64-v8a")
        val release = LeadPipeRelease.fromJson(
            releaseJson(assets = "[$arm32,$arm64]"), armAbis
        )!!
        assertEquals(
            "https://github.com/darvilp/PipePipeClient/releases/download/all-features%2F" +
                "v5.6.0-unofficial.22/PipePipe-all-features-5.6.0-unofficial.22-" +
                "466db9b96-20261123T020515Z-arm64-v8a.apk",
            release.downloadUrl
        )
    }

    @Test
    fun fallsBackToForkReleasePageWithoutACompatibleUploadedApk() {
        val unavailable = asset("arm64-v8a", state = "new")
        val incompatible = asset("x86_64")
        val release = LeadPipeRelease.fromJson(
            releaseJson(assets = "[$unavailable,$incompatible]"), armAbis
        )!!
        assertNull(release.downloadUrl)
        assertEquals(
            "https://github.com/darvilp/PipePipeClient/releases/tag/all-features/v5.6.0-unofficial.22",
            release.releaseUrl
        )
    }

    private fun releaseJson(
        tag: String = "all-features/v5.6.0-unofficial.22",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = "[]"
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":$assets}"""

    private fun asset(abi: String, state: String = "uploaded"): String {
        val name = "PipePipe-all-features-5.6.0-unofficial.22-466db9b96-20261123T020515Z-$abi.apk"
        return """{"name":"$name","state":"$state","browser_download_url":"https://example.org/"}"""
    }
}
