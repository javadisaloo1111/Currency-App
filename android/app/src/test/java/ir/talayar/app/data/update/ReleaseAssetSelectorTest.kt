package ir.talayar.app.data.update

import ir.talayar.app.data.remote.ReleaseAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Asset selection must always resolve to the installable package of THIS
 * repository's release, and must never pick a sidecar, a source archive, a debug
 * build or a file served from a foreign host.
 */
class ReleaseAssetSelectorTest {

    private fun select(assets: List<ReleaseAssetDto>, tag: String = "v1.0.3") =
        ReleaseAssetSelector.select(tag, assets)?.name

    @Test
    fun `picks the official release apk`() {
        val assets = listOf(apkAsset("1.0.3"), checksumAsset("1.0.3"))

        assertEquals("app-release-v1.0.3.apk", select(assets))
    }

    @Test
    fun `never picks a checksum sidecar even when it is the only asset`() {
        assertNull(select(listOf(checksumAsset("1.0.3"))))
    }

    @Test
    fun `never picks source archives, mappings or text files`() {
        val assets = listOf(
            ReleaseAssetDto(
                name = "Source code (zip)",
                browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/Source-code.zip",
                size = 250_000,
                contentType = "application/zip",
            ),
            ReleaseAssetDto(
                name = "Source code (tar.gz)",
                browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/Source-code.tar.gz",
                size = 240_000,
                contentType = "application/gzip",
            ),
            ReleaseAssetDto(
                name = "mapping.txt",
                browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/mapping.txt",
                size = 1_200_000,
                contentType = "text/plain",
            ),
            ReleaseAssetDto(
                name = "release-notes.txt",
                browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/release-notes.txt",
                size = 900,
                contentType = "text/plain",
            ),
        )

        assertNull(select(assets))
    }

    @Test
    fun `never picks a debug or unsigned build`() {
        val assets = listOf(
            ReleaseAssetDto(
                name = "app-debug-v1.0.3.apk",
                browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/app-debug-v1.0.3.apk",
                size = 9_000_000,
                contentType = "application/vnd.android.package-archive",
            ),
            ReleaseAssetDto(
                name = "app-release-unsigned.apk",
                browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/app-release-unsigned.apk",
                size = 9_000_000,
                contentType = "application/vnd.android.package-archive",
            ),
        )

        assertNull(select(assets))
    }

    @Test
    fun `a legitimate name containing the letters of a rejected token is still selectable`() {
        // "latest.apk" contains the substring "test" — token matching must not reject it.
        val latest = ReleaseAssetDto(
            name = "latest.apk",
            browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/latest.apk",
            size = 8_700_000,
            contentType = "application/vnd.android.package-archive",
        )

        assertEquals("latest.apk", select(listOf(latest)))
    }

    @Test
    fun `an asset from a foreign host is rejected`() {
        val foreign = ReleaseAssetDto(
            name = "app-release-v1.0.3.apk",
            browserDownloadUrl = "https://evil.example.com/app-release-v1.0.3.apk",
            size = 8_700_000,
            contentType = "application/vnd.android.package-archive",
        )

        assertNull(select(listOf(foreign)))
    }

    @Test
    fun `an http (non https) url is rejected`() {
        val insecure = ReleaseAssetDto(
            name = "app-release-v1.0.3.apk",
            browserDownloadUrl = "http://github.com/javadisaloo1111/Currency-App/releases/download/v1.0.3/app-release-v1.0.3.apk",
            size = 8_700_000,
            contentType = "application/vnd.android.package-archive",
        )

        assertNull(select(listOf(insecure)))
    }

    @Test
    fun `the content type and an exact name match decide between two plausible apks`() {
        val renamed = ReleaseAssetDto(
            name = "talayar.apk",
            browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.3/talayar.apk",
            size = 8_700_000,
            contentType = "application/octet-stream",
        )
        val official = apkAsset("1.0.3")

        assertEquals("app-release-v1.0.3.apk", select(listOf(renamed, official)))
        assertEquals("app-release-v1.0.3.apk", select(listOf(official, renamed)))
    }

    @Test
    fun `selection is deterministic regardless of asset order`() {
        val assets = listOf(
            apkAsset("1.0.3", name = "app-release-v1.0.3.apk"),
            checksumAsset("1.0.3"),
            apkAsset("1.0.3", name = "app-release-v1.0.3-old.apk", size = 1_000_000),
        )

        val forward = select(assets)
        val backward = select(assets.reversed())

        assertNotNull(forward)
        assertEquals(forward, backward)
    }

    @Test
    fun `an empty asset list yields nothing`() {
        assertNull(select(emptyList()))
    }

    @Test
    fun `an asset without a name or url is ignored`() {
        assertNull(
            select(
                listOf(
                    ReleaseAssetDto(name = "", browserDownloadUrl = "", size = 0, contentType = null),
                    ReleaseAssetDto(
                        name = "app-release-v1.0.3.apk",
                        browserDownloadUrl = "",
                        size = 8_700_000,
                        contentType = "application/vnd.android.package-archive",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `an unparsable tag still selects by name and content type`() {
        assertEquals("app-release-v1.0.3.apk", select(listOf(apkAsset("1.0.3")), tag = "latest"))
    }
}
