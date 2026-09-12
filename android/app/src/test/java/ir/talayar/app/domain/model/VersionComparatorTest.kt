package ir.talayar.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
