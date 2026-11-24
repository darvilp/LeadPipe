package org.schabi.newpipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadPipeReleaseTest {
    private val armAbis = arrayOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun opensTheInstalledVersionNotesWithAForkReleasesFallback() {
        assertEquals(
            "https://github.com/darvilp/LeadPipe/releases/tag/all-features/v5.6.0-unofficial.22",
            LeadPipeRelease.releasePageForVersion("5.6.0-unofficial.22")
        )
        assertEquals(
            "https://github.com/darvilp/LeadPipe/releases",
            LeadPipeRelease.releasePageForVersion("unknown")
        )
    }

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
    fun recognizesTheBridgeWithItsLegacyTagAndAssetName() {
        val tag = "all-features/v5.6.0-unofficial.24"
        val apk = "PipePipe-all-features-5.6.0-unofficial.24-466db9b96-20261123T020515Z-arm64-v8a.apk"
        val bridge = LeadPipeRelease.fromJson(releaseJson(tag, assets = "[${assetJson(apk)}]"), armAbis)!!

        assertEquals("5.6.0.24", bridge.version)
        assertTrue(bridge.compareToInstalled("5.6.0-unofficial.23")!! > 0)
        assertEquals(0, bridge.compareToInstalled("5.6.0.24"))
        assertEquals("https://github.com/darvilp/LeadPipe/releases/tag/$tag", bridge.releaseUrl)
        assertEquals(bridge.releaseUrl, LeadPipeRelease.releasePageForVersion("5.6.0.24"))
        assertEquals(
            "https://github.com/darvilp/LeadPipe/releases/download/all-features%2Fv5.6.0-unofficial.24/$apk",
            bridge.downloadUrl
        )
    }

    @Test
    fun recognizesLeadPipeTagsAndAssetsAfterTheBridgeAcrossUpstreamChanges() {
        val tag = "leadpipe/v5.7.0.25"
        val apk = "LeadPipe-5.7.0.25-466db9b96-20261123T020515Z-arm64-v8a.apk"
        val next = LeadPipeRelease.fromJson(releaseJson(tag, assets = "[${assetJson(apk)}]"), armAbis)!!

        assertEquals("5.7.0.25", next.version)
        assertTrue(next.compareToInstalled("5.6.0.24")!! > 0)
        assertEquals(0, next.compareToInstalled("5.7.0.25"))
        assertEquals(next.releaseUrl, LeadPipeRelease.releasePageForVersion("5.7.0.25"))
        assertEquals(
            "https://github.com/darvilp/LeadPipe/releases/download/leadpipe%2Fv5.7.0.25/$apk",
            next.downloadUrl
        )
        assertNull(LeadPipeRelease.fromJson(releaseJson("leadpipe/v5.7.0-unofficial.25"), armAbis))
        assertNull(LeadPipeRelease.fromJson(releaseJson("all-features/v5.7.0.25"), armAbis))
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
            "https://github.com/darvilp/LeadPipe/releases/download/all-features%2F" +
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
            "https://github.com/darvilp/LeadPipe/releases/tag/all-features/v5.6.0-unofficial.22",
            release.releaseUrl
        )
    }

    @Test
    fun usesGitHubReleaseAndDownloadUrlsAfterRepositoryRename() {
        val tag = "leadpipe/v5.6.0.26"
        val apk = "LeadPipe-5.6.0.26-466db9b96-20261124T020515Z-arm64-v8a.apk"
        val page = "https://github.com/darvilp/LeadPipe/releases/tag/$tag"
        val download = "https://github.com/darvilp/LeadPipe/releases/download/leadpipe%2Fv5.6.0.26/$apk"
        val release = LeadPipeRelease.fromJson(
            releaseJson(tag, assets = "[${assetJson(apk, url = download)}]", htmlUrl = page),
            armAbis
        )!!

        assertTrue(release.compareToInstalled("5.6.0.25")!! > 0)
        assertEquals(page, release.releaseUrl)
        assertEquals(download, release.downloadUrl)
    }

    @Test
    fun stillAcceptsReleaseMetadataUsingTheOriginalRepositoryName() {
        val tag = "leadpipe/v5.6.0.25"
        val apk = "LeadPipe-5.6.0.25-466db9b96-20261124T020515Z-arm64-v8a.apk"
        val page = "https://github.com/darvilp/PipePipeClient/releases/tag/$tag"
        val download = "https://github.com/darvilp/PipePipeClient/releases/download/$tag/$apk"
        val release = LeadPipeRelease.fromJson(
            releaseJson(tag, assets = "[${assetJson(apk, url = download)}]", htmlUrl = page),
            armAbis
        )!!

        assertEquals(page, release.releaseUrl)
        assertEquals(download, release.downloadUrl)
    }

    @Test
    fun ignoresReleaseUrlsOutsideTheLeadPipeReleaseAndSelectedAsset() {
        val tag = "leadpipe/v5.6.0.26"
        val apk = "LeadPipe-5.6.0.26-466db9b96-20261124T020515Z-arm64-v8a.apk"
        val wrongPage = "https://github.com/darvilp/LeadPipe/releases/tag/leadpipe/v9.0.0.99"
        val wrongDownload = "https://github.com/other/LeadPipe/releases/download/$tag/$apk"
        val release = LeadPipeRelease.fromJson(
            releaseJson(
                tag,
                assets = "[${assetJson(apk, url = wrongDownload)}]",
                htmlUrl = wrongPage
            ),
            armAbis
        )!!

        assertEquals("https://github.com/darvilp/LeadPipe/releases/tag/$tag", release.releaseUrl)
        assertEquals(
            "https://github.com/darvilp/LeadPipe/releases/download/leadpipe%2Fv5.6.0.26/$apk",
            release.downloadUrl
        )
    }

    private fun releaseJson(
        tag: String = "all-features/v5.6.0-unofficial.22",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = "[]",
        htmlUrl: String = ""
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":$assets,"html_url":"$htmlUrl"}"""

    private fun asset(abi: String, state: String = "uploaded"): String {
        val name = "PipePipe-all-features-5.6.0-unofficial.22-466db9b96-20261123T020515Z-$abi.apk"
        return assetJson(name, state)
    }

    private fun assetJson(
        name: String,
        state: String = "uploaded",
        url: String = "https://example.org/"
    ): String {
        return """{"name":"$name","state":"$state","browser_download_url":"$url"}"""
    }
}
