package ir.talayar.app.domain.model

/**
 * An available app update published on the official release channel.
 * UI copy must stay free of technical jargon (no "APK"/"GitHub"/"Release").
 */
data class AppUpdate(
    /** Latest published version, e.g. "1.0.2". */
    val latestVersion: String,
    /** Direct download URL of the official release asset of THIS repository. */
    val apkUrl: String,
    /** Published asset size in bytes (0 when unknown). */
    val apkSize: Long,
    /** URL of the published ".sha256" checksum asset, when present. */
    val sha256Url: String?,
    /**
     * Expected SHA-256 (hex) of the package, taken from the digest the release
     * service publishes for the asset itself. Fallback for a missing/unreadable
     * [sha256Url] sidecar; null when neither is published.
     */
    val sha256: String? = null,
    /** True when the installed version is below the release's supported minimum. */
    val forced: Boolean,
)

/**
 * Strict semantic-version comparison for tags like "v1.0.10" / "1.0.2".
 * Numeric per-component compare — 1.0.10 > 1.0.9 (never a string compare).
 */
object VersionComparator {

    private val VERSION = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

    /** "v1.0.10" -> [1, 0, 10]; null when no x.y.z triple is present. */
    fun parse(version: String?): IntArray? {
        if (version == null) return null
        val match = VERSION.find(version.trim()) ?: return null
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }

    /** Negative when a < b, 0 when equal, positive when a > b. Unparsable sorts lowest. */
    fun compare(a: String?, b: String?): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa == null && pb == null) return 0
        if (pa == null) return -1
        if (pb == null) return 1
        for (i in 0..2) if (pa[i] != pb[i]) return pa[i] - pb[i]
        return 0
    }

    /**
     * Canonical "x.y.z" form of any tag: "v1.0.3" / " 1.0.3 " / "v1.0.3-hotfix"
     * all become "1.0.3". Null when the text carries no x.y.z triple at all.
     */
    fun canonical(version: String?): String? =
        parse(version)?.let { "${it[0]}.${it[1]}.${it[2]}" }

    /**
     * True only when [candidate] is a parsable release strictly newer than
     * [installed] — i.e. never for an equal version (no update loop) and never
     * for an older one (no downgrade). An unparsable candidate is never "newer".
     */
    fun isNewer(candidate: String?, installed: String?): Boolean =
        parse(candidate) != null && compare(candidate, installed) > 0
}
