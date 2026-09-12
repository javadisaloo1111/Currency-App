package ir.talayar.app.domain.model

/**
 * Every way the in-app update flow can fail, as a *distinct* category.
 *
 * The old code collapsed all of these into one generic «بررسی بروزرسانی ناموفق بود»
 * (and the market code even reports every [java.io.IOException] as «no internet»),
 * which made a filtered/unreachable release service indistinguishable from an
 * offline device. Each kind therefore carries:
 *  - [userMessage]: plain Persian, RTL-safe, no jargon and never a raw exception;
 *  - [retryable]: whether an automatic bounded retry makes sense.
 */
enum class UpdateErrorKind(val userMessage: String, val retryable: Boolean) {

    /** The connectivity monitor says the device has no network at all. */
    NO_INTERNET(
        "اتصال اینترنت برقرار نیست. اینترنت دستگاه را روشن کنید و دوباره تلاش کنید.",
        retryable = false,
    ),

    /** Host name could not be resolved — commonly a filtered/blocked endpoint. */
    DNS_FAILURE(
        "آدرس سرور بروزرسانی پیدا نشد. ممکن است دسترسی به آن در شبکهٔ شما محدود باشد.",
        retryable = true,
    ),

    /** Connect / read / call timeout. */
    TIMEOUT(
        "پاسخ سرور بروزرسانی طولانی شد. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Handshake / certificate problem. Never worked around by disabling TLS. */
    TLS_FAILURE(
        "ارتباط امن با سرور بروزرسانی برقرار نشد. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Network is up, but the release service refused/reset/dropped the call. */
    CONNECTION_FAILED(
        "ارتباط با سرور بروزرسانی برقرار نشد. لطفاً دوباره تلاش کنید.",
        retryable = true,
    ),

    /** HTTP 403 / 429 — GitHub rate-limits unauthenticated API calls per IP. */
    RATE_LIMITED(
        "سرور بروزرسانی موقتاً درخواست‌ها را محدود کرده است. چند دقیقه دیگر دوباره تلاش کنید.",
        retryable = true,
    ),

    /** HTTP 404 — no published release (or it was removed). */
    NOT_FOUND(
        "نسخهٔ منتشرشده‌ای در سرور بروزرسانی پیدا نشد.",
        retryable = false,
    ),

    /** HTTP 5xx. */
    SERVER_ERROR(
        "سرور بروزرسانی موقتاً در دسترس نیست. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Any other non-2xx response. */
    HTTP_ERROR(
        "سرور بروزرسانی پاسخ ناموفق داد. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Body was not the release metadata we expect. */
    INVALID_RESPONSE(
        "پاسخ سرور بروزرسانی قابل خواندن نیست. دوباره تلاش کنید.",
        retryable = false,
    ),

    /** Payload parsed, but it is not a usable published release (draft/prerelease/no tag). */
    RELEASE_NOT_FOUND(
        "انتشار معتبری در سرور بروزرسانی پیدا نشد.",
        retryable = false,
    ),

    /** A newer release exists but carries no installable APK asset. */
    APK_NOT_FOUND(
        "نسخهٔ جدید پیدا شد، ولی فایل نصب آن منتشر نشده است.",
        retryable = false,
    ),

    /** Transport failure while downloading the APK. */
    DOWNLOAD_FAILED(
        "دانلود بروزرسانی ناموفق بود. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Download stopped early / size does not match the published asset. */
    INCOMPLETE_DOWNLOAD(
        "دانلود بروزرسانی کامل نشد. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Published SHA-256 does not match the downloaded bytes. */
    CHECKSUM_MISMATCH(
        "فایل دانلودشده سالم نیست. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** Not a readable Android package, wrong package, or a downgrade. */
    INVALID_APK(
        "فایل دانلودشده یک بستهٔ نصبی معتبر نیست. دوباره تلاش کنید.",
        retryable = true,
    ),

    /** The system package installer could not be opened. */
    INSTALL_FAILED(
        "نصب‌کنندهٔ اندروید باز نشد. اجازهٔ نصب برنامه‌های ناشناس را بررسی کنید.",
        retryable = true,
    ),

    /** Anything not classified above. */
    UNKNOWN(
        "بروزرسانی ناموفق بود. دوباره تلاش کنید.",
        retryable = true,
    ),
}

/**
 * A classified update failure: a category, an optional technical detail (logcat
 * only — never rendered) and the original cause for diagnostics.
 */
class UpdateError(
    val kind: UpdateErrorKind,
    val detail: String? = null,
    val cause: Throwable? = null,
) {

    /** Plain Persian, user-presentable copy. */
    val userMessage: String get() = kind.userMessage

    /** Whether a bounded automatic retry is worthwhile. */
    val retryable: Boolean get() = kind.retryable

    override fun toString(): String =
        if (detail.isNullOrBlank()) kind.name else "${kind.name}: $detail"
}
