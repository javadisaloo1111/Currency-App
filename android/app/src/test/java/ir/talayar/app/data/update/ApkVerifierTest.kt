package ir.talayar.app.data.update

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
}
