package ir.talayar.app.data.update

import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Failure classification and the copy contract behind it.
 *
 * Two guarantees the update UI depends on:
 *  1. every distinct failure has a distinct [UpdateErrorKind] — an unreachable
 *     update server while the device is online is never «no internet», a timeout
 *     is never «you are up to date», a rate limit says so;
 *  2. every kind carries plain Persian copy: no Latin jargon, no exception text,
 *     nothing a user cannot act on.
 */
class UpdateErrorMappingTest {

    // ------------------------------------------------------------------
    // classification
    // ------------------------------------------------------------------

    @Test
    fun `http status codes map onto distinct kinds`() {
        assertEquals(UpdateErrorKind.RATE_LIMITED, classifyUpdateFailure(httpException(403), online = true).kind)
        assertEquals(UpdateErrorKind.RATE_LIMITED, classifyUpdateFailure(httpException(429), online = true).kind)
        assertEquals(UpdateErrorKind.NOT_FOUND, classifyUpdateFailure(httpException(404), online = true).kind)
        assertEquals(UpdateErrorKind.SERVER_ERROR, classifyUpdateFailure(httpException(500), online = true).kind)
        assertEquals(UpdateErrorKind.SERVER_ERROR, classifyUpdateFailure(httpException(503), online = true).kind)
        assertEquals(UpdateErrorKind.HTTP_ERROR, classifyUpdateFailure(httpException(418), online = true).kind)
    }

    @Test
    fun `payload and transport failures map onto distinct kinds`() {
        assertEquals(
            UpdateErrorKind.INVALID_RESPONSE,
            classifyUpdateFailure(SerializationException("Unexpected JSON token"), online = true).kind,
        )
        assertEquals(
            UpdateErrorKind.DNS_FAILURE,
            classifyUpdateFailure(UnknownHostException("api.github.com"), online = true).kind,
        )
        assertEquals(
            UpdateErrorKind.TLS_FAILURE,
            classifyUpdateFailure(SSLException("Handshake failed"), online = true).kind,
        )
        assertEquals(
            UpdateErrorKind.TIMEOUT,
            classifyUpdateFailure(SocketTimeoutException("connect timed out"), online = true).kind,
        )
    }

    @Test
    fun `a generic io failure depends on whether the device is online`() {
        val online = classifyUpdateFailure(IOException("Connection reset by peer"), online = true)
        val offline = classifyUpdateFailure(IOException("Connection reset by peer"), online = false)

        assertEquals(UpdateErrorKind.CONNECTION_FAILED, online.kind)
        assertEquals(UpdateErrorKind.NO_INTERNET, offline.kind)
        assertNotEquals(online.userMessage, offline.userMessage)
        // The exact copy the reported bug demanded: internet up, server unreachable.
        assertEquals(
            "ارتباط با سرور بروزرسانی برقرار نشد. لطفاً دوباره تلاش کنید.",
            online.userMessage,
        )
    }

    @Test
    fun `a tls failure is never reported as being offline`() {
        val error = classifyUpdateFailure(SSLException("certificate unknown"), online = true)

        assertEquals(UpdateErrorKind.TLS_FAILURE, error.kind)
        assertTrue("TLS problems are worth one bounded retry", error.retryable)
    }

    @Test
    fun `unexpected throwables fall back to unknown but stay user presentable`() {
        val error = classifyUpdateFailure(RuntimeException("boom at ir.talayar.app.Secret"), online = true)

        assertEquals(UpdateErrorKind.UNKNOWN, error.kind)
        assertTrue(error.detail!!.contains("boom"))
        assertFalse("the raw message must never reach the user", error.userMessage.contains("boom"))
        assertFalse(error.userMessage.contains("ir.talayar"))
    }

    @Test
    fun `a null failure is unknown, never a crash`() {
        val error = classifyUpdateFailure(null, online = true)

        assertEquals(UpdateErrorKind.UNKNOWN, error.kind)
        assertNull(error.cause)
        assertTrue(error.userMessage.isNotBlank())
    }

    @Test
    fun `a download failure keeps the classification it was raised with`() {
        val original = UpdateError(UpdateErrorKind.CHECKSUM_MISMATCH, "sha256 mismatch")
        val classified = classifyUpdateFailure(UpdateDownloadException(original), online = true)

        assertSame(original, classified)
        assertEquals(UpdateErrorKind.CHECKSUM_MISMATCH, classified.kind)
    }

    @Test
    fun `details are truncated so a chatty exception cannot bloat logcat`() {
        val error = classifyUpdateFailure(IOException("x".repeat(4000)), online = true)

        assertTrue(error.detail!!.length < 400)
    }

    // ------------------------------------------------------------------
    // retry policy
    // ------------------------------------------------------------------

    @Test
    fun `only transient failures are retryable`() {
        for (kind in UpdateErrorKind.entries) {
            val expectedRetryable = kind !in NON_RETRYABLE
            assertEquals("$kind retryable flag", expectedRetryable, kind.retryable)
        }
    }

    // ------------------------------------------------------------------
    // copy contract
    // ------------------------------------------------------------------

    @Test
    fun `every kind has its own non blank persian message`() {
        val messages = UpdateErrorKind.entries.map { it.userMessage }

        assertEquals(UpdateErrorKind.entries.size, messages.size)
        assertEquals("each failure needs its own wording", messages.size, messages.toSet().size)
        messages.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `no message leaks latin jargon or technical identifiers`() {
        for (kind in UpdateErrorKind.entries) {
            val message = kind.userMessage
            assertFalse("$kind must stay Persian-only", message.any { it in 'a'..'z' || it in 'A'..'Z' })
            for (jargon in listOf("APK", "apk", "GitHub", "Release", "API", "HTTP", "Exception", "null")) {
                assertFalse("$kind must not mention '$jargon'", message.contains(jargon))
            }
        }
    }

    @Test
    fun `a classified error renders its kind message and a loggable toString`() {
        val error = classifyUpdateFailure(httpException(403), online = true)

        assertEquals(UpdateErrorKind.RATE_LIMITED.userMessage, error.userMessage)
        assertNotNull(error.cause)
        assertTrue("toString is for logcat and must carry the kind", error.toString().startsWith("RATE_LIMITED"))
        assertTrue(error.toString().contains("HTTP 403"))
    }

    private companion object {
        /** Permanent answers: retrying them immediately cannot help. */
        val NON_RETRYABLE = setOf(
            UpdateErrorKind.NO_INTERNET,
            UpdateErrorKind.NOT_FOUND,
            UpdateErrorKind.INVALID_RESPONSE,
            UpdateErrorKind.RELEASE_NOT_FOUND,
            UpdateErrorKind.APK_NOT_FOUND,
        )
    }
}
