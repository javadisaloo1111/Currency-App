package ir.talayar.app.data.update

import ir.talayar.app.domain.model.UpdateErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A minimal fake APK: ZIP magic + padding so the file is non-empty. */
    private fun fakeApk(bytes: Int = 2048): File {
        val f = tmp.newFile("update.apk")
        val content = ByteArray(bytes) { 0x11 }
        content[0] = 'P'.code.toByte()
        content[1] = 'K'.code.toByte()
        content[2] = 3
        content[3] = 4
        f.writeBytes(content)
        return f
    }

    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `accepts a complete apk with matching size and checksum`() {
        val apk = fakeApk()
        assertTrue(ApkVerifier.verify(apk, apk.length(), sha256Of(apk)))
    }

    @Test
    fun `accepts a valid apk when no checksum is published`() {
        val apk = fakeApk()
        assertTrue(ApkVerifier.verify(apk, apk.length(), null))
    }

    @Test
    fun `rejects an incomplete download with the wrong size`() {
        val apk = fakeApk()
        assertFalse(ApkVerifier.verify(apk, apk.length() + 500, null))
    }

    @Test
    fun `rejects a file that is not a zip archive`() {
        val notZip = tmp.newFile("not-an-apk.bin")
        notZip.writeBytes(ByteArray(4096) { 0x00 })
        assertFalse(ApkVerifier.verify(notZip, 4096, null))
    }

    @Test
    fun `rejects a corrupted download by checksum`() {
        val apk = fakeApk()
        val wrongChecksum = "0".repeat(64)
        assertFalse(ApkVerifier.verify(apk, apk.length(), wrongChecksum))
    }

    @Test
    fun `rejects missing or empty files`() {
        assertFalse(ApkVerifier.verify(File(tmp.root, "missing.apk"), 100, null))
        val empty = tmp.newFile("empty.apk")
        empty.writeBytes(ByteArray(0))
        assertFalse(ApkVerifier.verify(empty, 0, null))
    }

    // ------------------------------------------------------------------
    // check(): the precise rejection reason that drives the user-facing copy
    // ------------------------------------------------------------------

    @Test
    fun `check accepts a complete apk with a matching checksum`() {
        val apk = fakeApk()

        assertEquals(ApkCheck.Ok, ApkVerifier.check(apk, apk.length(), sha256Of(apk)))
    }

    @Test
    fun `check reports an incomplete download when the size differs`() {
        val apk = fakeApk()

        val rejected = ApkVerifier.check(apk, apk.length() + 500, null)

        assertTrue("expected a rejection, got $rejected", rejected is ApkCheck.Rejected)
        assertEquals(UpdateErrorKind.INCOMPLETE_DOWNLOAD, (rejected as ApkCheck.Rejected).kind)
    }

    @Test
    fun `check reports a checksum mismatch for corrupted bytes`() {
        val apk = fakeApk()

        val rejected = ApkVerifier.check(apk, apk.length(), "0".repeat(64))

        assertTrue(rejected is ApkCheck.Rejected)
        assertEquals(UpdateErrorKind.CHECKSUM_MISMATCH, (rejected as ApkCheck.Rejected).kind)
    }

    @Test
    fun `check reports an invalid apk when the zip magic is missing`() {
        val notZip = tmp.newFile("not-an-apk.bin")
        notZip.writeBytes(ByteArray(4096) { 0x00 })

        val rejected = ApkVerifier.check(notZip, 4096, null)

        assertTrue(rejected is ApkCheck.Rejected)
        assertEquals(UpdateErrorKind.INVALID_APK, (rejected as ApkCheck.Rejected).kind)
    }

    @Test
    fun `check reports an invalid apk for a missing or empty file`() {
        val missing = ApkVerifier.check(File(tmp.root, "missing.apk"), 100, null)
        val empty = tmp.newFile("empty.apk").also { it.writeBytes(ByteArray(0)) }
        val emptyResult = ApkVerifier.check(empty, 0, null)

        assertEquals(UpdateErrorKind.INVALID_APK, (missing as ApkCheck.Rejected).kind)
        assertEquals(UpdateErrorKind.INVALID_APK, (emptyResult as ApkCheck.Rejected).kind)
    }

    @Test
    fun `the size check runs before hashing so a truncated file is reported as incomplete`() {
        val apk = fakeApk()

        val rejected = ApkVerifier.check(apk, apk.length() * 2, "0".repeat(64))

        assertEquals(UpdateErrorKind.INCOMPLETE_DOWNLOAD, (rejected as ApkCheck.Rejected).kind)
    }

    @Test
    fun `an unknown expected size or checksum skips those checks instead of failing`() {
        val apk = fakeApk()

        assertEquals(ApkCheck.Ok, ApkVerifier.check(apk, 0L, null))
        assertEquals(ApkCheck.Ok, ApkVerifier.check(apk, -1L, "   "))
    }

    @Test
    fun `a checksum published in upper case still matches`() {
        val apk = fakeApk()

        assertEquals(ApkCheck.Ok, ApkVerifier.check(apk, apk.length(), sha256Of(apk).uppercase()))
    }

    @Test
    fun `a rejected file never passes the boolean gate handed to the installer`() {
        val apk = fakeApk()

        assertFalse(ApkVerifier.verify(apk, apk.length() + 1, null))
        assertFalse(ApkVerifier.verify(apk, apk.length(), "0".repeat(64)))
        assertTrue(ApkVerifier.verify(apk, apk.length(), sha256Of(apk)))
    }
}
