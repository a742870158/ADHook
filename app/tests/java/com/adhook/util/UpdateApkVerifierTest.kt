package com.adhook.util

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31, 35], shadows = [UpdateApkVerifierTest.CertificateMetadata::class])
class UpdateApkVerifierTest {
    @Implements(SigningInfo::class)
    class CertificateMetadata {
        companion object {
            var current: Array<Signature>? = null
        }

        @Implementation
        protected fun getApkContentsSigners(): Array<Signature>? = current
    }

    private val local = UpdateApkIdentity("com.adhook", "1.0.1", 100, setOf("aa".repeat(32)))
    private val release = ReleaseInfo("v1.0.2", "1.0.2", "", "", "${UpdateDownloadPolicy.REPO}/releases/download/v1.0.2/ADHook.apk",
        260, "ADHook.apk", "${UpdateDownloadPolicy.REPO}/releases/tag/v1.0.2", "cc".repeat(32))
    @Test fun installedReadsSigningCertificatesAcrossSupportedApiLevels() {
        val context = RuntimeEnvironment.getApplication()
        val certBytes = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x0A)
        CertificateMetadata.current = arrayOf(Signature(certBytes))
        try {
            val info = PackageInfo().apply {
                packageName = context.packageName
                versionName = "1.0.1"
                longVersionCode = 100L
                signingInfo = ReflectionHelpers.callConstructor(SigningInfo::class.java)
            }
            Shadows.shadowOf(context.packageManager).installPackage(info)
            val identity = UpdateApkVerifier.installed(context)
            assertEquals(context.packageName, identity.packageName)
            assertEquals("1.0.1", identity.versionName)
            assertEquals(100L, identity.versionCode)
            val expectedHash = UpdateApkVerifier.hex(MessageDigest.getInstance("SHA-256").digest(certBytes))
            assertEquals(setOf(expectedHash), identity.currentSigners)
        } finally {
            CertificateMetadata.current = null
        }
    }
    @Test fun sameExistingCertificateRemainsUsableWithoutAnyReplacementKeystore() {
        UpdateApkVerifier.verify(local.copy(versionName = "1.0.2", versionCode = 101), local, release)
        val multi = local.copy(currentSigners = setOf("aa".repeat(32), "bb".repeat(32)))
        UpdateApkVerifier.verify(multi.copy(versionName = "1.0.2", versionCode = 101), multi, release)
    }
    @Test fun missingForeignOrChangedIdentityAndDowngradeOrWrongReleaseNameFailClosed() {
        val good = local.copy(versionName = "1.0.2", versionCode = 101)
        for (invalid in listOf(good.copy(packageName = "other.app"), good.copy(currentSigners = emptySet()),
            good.copy(currentSigners = setOf("bb".repeat(32))), good.copy(versionCode = 100), good.copy(versionName = "1.0.3"))) {
            assertThrows(IOException::class.java) { UpdateApkVerifier.verify(invalid, local, release) }
        }
    }
    private fun metadata(digest: String = "sha256:" + "cc".repeat(32)): String = """{
        "tag_name":"v1.0.2","name":"Update","body":"quoted braces { [ are text",
        "html_url":"https://evil.invalid/path", "assets":[{
        "name":"ADHook.apk","size":260,"digest":"$digest",
        "browser_download_url":"${UpdateDownloadPolicy.REPO}/releases/download/v1.0.2/ADHook.apk"}]}"""
    @Test fun metadataPinIsOfficialAndDigestMissingOnlyPermitsExplicitManualReleaseNavigation() {
        val parsed = UpdateDownloadPolicy.parse(metadata())
        assertEquals("cc".repeat(32), parsed.sha256)
        assertEquals("${UpdateDownloadPolicy.REPO}/releases/tag/v1.0.2", parsed.htmlUrl)
        UpdateDownloadPolicy.validateRelease(parsed)
        val missing = UpdateDownloadPolicy.parse(metadata("null"))
        assertNull(missing.sha256)
        assertThrows(IOException::class.java) { UpdateDownloadPolicy.validateRelease(missing) }
    }
    @Test fun malformedDigestAndNestedOrOversizedJsonAreRejectedBeforeParsingCanExpandThem() {
        assertThrows(IOException::class.java) { UpdateDownloadPolicy.parse(metadata("sha1:" + "aa".repeat(20))) }
        val nested = "{\"value\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertThrows(IOException::class.java) { UpdateDownloadPolicy.parse(nested) }
        assertThrows(IOException::class.java) { UpdateDownloadPolicy.parse(" ".repeat(1024 * 1024 + 1)) }
    }
    @Test fun unknownAndMalformedVersionCannotBeMisreportedAsNumericUpdates() {
        assertTrue(UpdateChecker.isNewerVersion("v1.0.261111.1400", "1.0.261111.1300"))
        assertFalse(UpdateChecker.isNewerVersion("v1.0.future.999", "1.0.1"))
        assertFalse(UpdateChecker.isNewerVersion("v1.0.1", "1.0.2"))
    }
}
