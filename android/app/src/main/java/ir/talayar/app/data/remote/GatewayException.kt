package ir.talayar.app.data.remote

import java.io.IOException

/**
 * A classified gateway failure carrying a short, user-presentable message.
 *
 * Technical details (HTTP status, exception type, host) are written to logcat
 * by the repository — they never reach the UI.
 */
class GatewayException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Maps a low-level network / HTTP / parsing failure to a user-facing category.
 *
 * No internet      -> «اتصال به اینترنت برقرار نیست»
 * HTTP error       -> «سرور در دسترس نیست»
 * Malformed body   -> «پاسخ سرور نامعتبر است»
 * Anything else    -> «اتصال به سرور برقرار نشد»
 */
fun classifyGatewayFailure(e: Throwable?): GatewayException {
    if (e is GatewayException) return e
    return when (e) {
        is retrofit2.HttpException -> GatewayException("سرور در دسترس نیست", e)
        is IOException -> GatewayException("اتصال به اینترنت برقرار نیست", e)
        is kotlinx.serialization.SerializationException,
        is IllegalArgumentException,
        -> GatewayException("پاسخ سرور نامعتبر است", e)

        else -> GatewayException("اتصال به سرور برقرار نشد", e)
    }
}
