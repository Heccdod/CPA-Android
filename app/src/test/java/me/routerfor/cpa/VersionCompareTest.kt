package me.routerfor.cpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two comparisons the update channels hinge on.
 *
 * compareVersions() replaced an equality test, and equality is why the app offered to install
 * v2.5.3 over versionName 2.7.1: "2.5.3" is not equal to "2.7.1", so the check could not tell a
 * downgrade from an upgrade. The cases below are the ones the published tags actually produce —
 * GitHub tags carry a leading v, CI kernels carry a fixed-width timestamp, and a release may
 * carry build metadata.
 */
class VersionCompareTest {

    // ---------------------------------------------------------------- compareVersions

    @Test
    fun remoteOlderThanLocalIsNotAnUpdate() {
        // The shipped regression: /releases/latest was v2.5.3 while the build was 2.7.1.
        assertTrue(UpdateManager.compareVersions("2.7.1", "v2.5.3") > 0)
        assertTrue(UpdateManager.compareVersions("v2.5.3", "2.7.1") < 0)
    }

    @Test
    fun sameVersionInDifferentNotationIsEqual() {
        assertEquals(0, UpdateManager.compareVersions("2.7.1", "2.7.1"))
        assertEquals(0, UpdateManager.compareVersions("v2.7.1", "2.7.1"))
        assertEquals(0, UpdateManager.compareVersions(" 2.7.1 ", "v2.7.1\n"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertTrue(UpdateManager.compareVersions("2.7", "2.7.1") < 0)
        assertTrue(UpdateManager.compareVersions("2.7.1", "2.7") > 0)
        assertEquals(0, UpdateManager.compareVersions("2.7", "2.7.0"))
    }

    @Test
    fun segmentsCompareNumericallyNotAsText() {
        // 2.10 is newer than 2.9 even though "10" < "9" as text.
        assertTrue(UpdateManager.compareVersions("2.10.0", "2.9.0") > 0)
        assertTrue(UpdateManager.compareVersions("2.7.10", "2.7.9") > 0)
    }

    @Test
    fun prereleaseSortsBelowItsRelease() {
        assertTrue(UpdateManager.compareVersions("v2.7.1-beta", "2.7.1") < 0)
        assertTrue(UpdateManager.compareVersions("2.7.1", "v2.7.1-beta") > 0)
    }

    @Test
    fun twoPrereleasesCompareBySuffixText() {
        assertTrue(UpdateManager.compareVersions("2.7.1-beta", "2.7.1-alpha") > 0)
        assertEquals(0, UpdateManager.compareVersions("2.7.1-rc1", "v2.7.1-rc1"))
    }

    @Test
    fun buildMetadataNeverAffectsOrder() {
        assertEquals(0, UpdateManager.compareVersions("2.7.1+build5", "2.7.1"))
        assertEquals(0, UpdateManager.compareVersions("2.7.1+build5", "v2.7.1"))
    }

    @Test
    fun emptyLocalVersionIsTreatedAsOlderThanAnyRelease() {
        // versionName is a nullable platform type; the caller passes orEmpty().
        assertTrue(UpdateManager.compareVersions("v2.7.1", "") > 0)
    }

    // ---------------------------------------------------------------- trimTagFromPending

    @Test
    fun pendingNameYieldsItsTag() {
        assertEquals(
            "upstream-20261101-213407",
            UpdateManager.trimTagFromPending("libgojni-upstream-20261101-213407.so")
        )
        assertEquals(
            "upstream-20261101-213407",
            UpdateManager.trimTagFromPending("  libgojni-upstream-20261101-213407.so\n")
        )
    }

    @Test
    fun unparsablePendingNameYieldsNullSoAnyCandidateIsAllowed() {
        // null means "nothing usable is staged" — a corrupt file must not pin the channel shut.
        assertNull(UpdateManager.trimTagFromPending(null))
        assertNull(UpdateManager.trimTagFromPending(""))
        assertNull(UpdateManager.trimTagFromPending("pending.txt"))
        assertNull(UpdateManager.trimTagFromPending("libgojni.so"))
        assertNull(UpdateManager.trimTagFromPending("libgojni-.so"))
        assertNull(UpdateManager.trimTagFromPending("libgojni-upstream-20261101-213407.apk"))
    }

    @Test
    fun kernelTagsOrderLexicographicallyAtFixedWidth() {
        // The downgrade guard in checkKernelRelease relies on plain string order; this pins the
        // assumption so a tag-format change cannot silently make the guard a no-op.
        assertTrue(UpdateManager.trimTagFromPending("libgojni-upstream-20261102-010000.so")!! >
            UpdateManager.trimTagFromPending("libgojni-upstream-20261101-213407.so")!!)
        assertTrue(UpdateManager.trimTagFromPending("libgojni-upstream-20261101-213407.so")!! <=
            "upstream-20261101-213407")
    }
}
