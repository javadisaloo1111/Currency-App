package ir.talayar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `parses plain and v-prefixed tags`() {
        assertEquals(intArrayOf(1, 0, 1).contentToString(), VersionComparator.parse("1.0.1")!!.contentToString())
        assertEquals(intArrayOf(1, 0, 1).contentToString(), VersionComparator.parse("v1.0.1")!!.contentToString())
        assertEquals(intArrayOf(1, 2, 30).contentToString(), VersionComparator.parse(" v1.2.30 ")!!.contentToString())
    }

    @Test
    fun `same version compares equal`() {
        assertEquals(0, VersionComparator.compare("1.0.1", "v1.0.1"))
        assertEquals(0, VersionComparator.compare("1.0.1", "1.0.1"))
    }

    @Test
    fun `numeric comparison never treats 10 as less than 9`() {
        // String comparison would say "1.0.10" < "1.0.9" — semver must not.
        assertEquals(1, Integer.signum(VersionComparator.compare("1.0.10", "1.0.9")))
        assertEquals(-1, Integer.signum(VersionComparator.compare("1.0.9", "1.0.10")))
        assertEquals(0, VersionComparator.compare("v1.0.10", "1.0.10"))
    }

    @Test
    fun `newer patch, minor and major versions compare greater`() {
        assertEquals(1, Integer.signum(VersionComparator.compare("1.0.2", "1.0.1")))
        assertEquals(1, Integer.signum(VersionComparator.compare("1.1.0", "1.0.9")))
        assertEquals(1, Integer.signum(VersionComparator.compare("2.0.0", "1.9.9")))
    }

    @Test
    fun `older versions compare smaller`() {
        assertEquals(-1, Integer.signum(VersionComparator.compare("1.0.1", "1.0.2")))
        assertEquals(-1, Integer.signum(VersionComparator.compare("1.0.9", "1.1.0")))
    }

    @Test
    fun `unparsable tags sort lowest and null-safe`() {
        assertNull(VersionComparator.parse(null))
        assertNull(VersionComparator.parse("latest"))
        assertEquals(-1, Integer.signum(VersionComparator.compare("oops", "1.0.1")))
        assertEquals(0, VersionComparator.compare("oops", "also-oops"))
    }

    @Test
    fun `canonical strips the v prefix and any suffix`() {
        assertEquals("1.0.3", VersionComparator.canonical("v1.0.3"))
        assertEquals("1.0.3", VersionComparator.canonical(" 1.0.3 "))
        assertEquals("1.0.3", VersionComparator.canonical("v1.0.3-hotfix"))
        assertEquals("1.0.10", VersionComparator.canonical("V1.0.10"))
        assertNull(VersionComparator.canonical("latest"))
        assertNull(VersionComparator.canonical(null))
        assertNull(VersionComparator.canonical("1.0"))
    }

    @Test
    fun `isNewer is strictly greater than, never equal or older`() {
        assertTrue(VersionComparator.isNewer("v1.0.3", "1.0.2"))
        assertTrue(VersionComparator.isNewer("1.0.10", "1.0.9"))
        assertTrue(VersionComparator.isNewer("1.1.0", "1.0.99"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.9.9"))

        assertFalse("an equal version must never re-offer itself", VersionComparator.isNewer("1.0.2", "1.0.2"))
        assertFalse(VersionComparator.isNewer("v1.0.2", "1.0.2"))
        assertFalse("no downgrade", VersionComparator.isNewer("1.0.1", "1.0.2"))
        assertFalse(VersionComparator.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun `isNewer rejects unparsable candidates and tolerates an unparsable installed version`() {
        assertFalse(VersionComparator.isNewer("latest", "1.0.2"))
        assertFalse(VersionComparator.isNewer(null, "1.0.2"))
        assertFalse(VersionComparator.isNewer("", "1.0.2"))
        assertTrue("anything parsable beats an unparsable installed version", VersionComparator.isNewer("1.0.2", "oops"))
    }
}
