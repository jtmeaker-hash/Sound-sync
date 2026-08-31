package com.example

import com.example.model.GitHubReleaseAsset
import com.example.model.SemanticVersion
import com.example.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionAndUpdaterTest {

    @Test
    fun testSemanticVersionParsing() {
        val v1 = SemanticVersion.parse("1.0.0")
        assertEquals(1, v1.major)
        assertEquals(0, v1.minor)
        assertEquals(0, v1.patch)

        val v2 = SemanticVersion.parse("v2.4.1")
        assertEquals(2, v2.major)
        assertEquals(4, v2.minor)
        assertEquals(1, v2.patch)

        val vBeta = SemanticVersion.parse("1.1.0-beta.1")
        assertEquals(1, vBeta.major)
        assertEquals(1, vBeta.minor)
        assertEquals(0, vBeta.patch)
        assertEquals("beta.1", vBeta.preRelease)
    }

    @Test
    fun testSemanticVersionComparisons() {
        val v100 = SemanticVersion.parse("1.0.0")
        val v101 = SemanticVersion.parse("1.0.1")
        val v110 = SemanticVersion.parse("1.1.0")
        val v200 = SemanticVersion.parse("2.0.0")

        assertTrue(v101.isNewerThan(v100))
        assertTrue(v110.isNewerThan(v101))
        assertTrue(v200.isNewerThan(v110))
        assertFalse(v100.isNewerThan(v101))
        assertFalse(v100.isNewerThan(v100))

        // Compare string with 'v' prefix
        assertTrue(SemanticVersion.parse("v1.0.2").isNewerThan("1.0.1"))
        assertTrue(SemanticVersion.parse("1.2.0").isNewerThan("v1.1.9"))

        // Pre-release comparison (1.0.0 is newer than 1.0.0-rc1)
        val vRc = SemanticVersion.parse("1.0.0-rc.1")
        assertTrue(v100.isNewerThan(vRc))
    }

    @Test
    fun testFindApkAsset() {
        val assets = listOf(
            GitHubReleaseAsset(id = 1, name = "SoundSync-v1.0.1-release.apk.sha256", size = 64, browserDownloadUrl = "https://example.com/sha"),
            GitHubReleaseAsset(id = 2, name = "SoundSync-v1.0.1-release.apk", size = 25000000, browserDownloadUrl = "https://example.com/apk"),
            GitHubReleaseAsset(id = 3, name = "source_code.zip", size = 120000, browserDownloadUrl = "https://example.com/zip")
        )

        val apk = UpdateManager.findApkAsset(assets)
        assertNotNull(apk)
        assertEquals("SoundSync-v1.0.1-release.apk", apk?.name)

        val releaseAssets = listOf(
            GitHubReleaseAsset(id = 10, name = "SoundSync-v1.0.2.apk.sha256", size = 64, browserDownloadUrl = "https://github.com/jtmeaker-hash/Sound-sync/releases/download/v1.0.2/SoundSync-v1.0.2.apk.sha256"),
            GitHubReleaseAsset(id = 11, name = "SoundSync-v1.0.2.apk", size = 28000000, browserDownloadUrl = "https://github.com/jtmeaker-hash/Sound-sync/releases/download/v1.0.2/SoundSync-v1.0.2.apk")
        )
        val releaseApk = UpdateManager.findApkAsset(releaseAssets)
        assertNotNull(releaseApk)
        assertEquals("SoundSync-v1.0.2.apk", releaseApk?.name)
        assertEquals("https://github.com/jtmeaker-hash/Sound-sync/releases/download/v1.0.2/SoundSync-v1.0.2.apk", releaseApk?.browserDownloadUrl)
    }

    @Test
    fun testSha256Extraction() {
        val sha256Text = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  SoundSync-v1.0.1-release.apk\n"
        val hash = UpdateManager.extractSha256Hex(sha256Text)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }
}
