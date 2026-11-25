package io.github.chessoverlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun releasesAreComparedByTheirNumbers() {
        assertTrue(Versions.newer("v0.2.0", "0.1.0"))
        assertTrue(Versions.newer("v0.10.0", "0.9.3"))
        assertTrue(Versions.newer("v1.0", "0.9.9"))
        assertFalse(Versions.newer("v0.2.0", "0.2.0"))
        assertFalse(Versions.newer("v0.1.0", "0.2.0"))
        // A CI build of the same version is not older than its release.
        assertFalse(Versions.newer("v0.2.0", "0.2.0+61"))
        assertTrue(Versions.newer("v0.2.0", "0.1.0+57"))
        assertFalse("not a version", Versions.newer("nightly", "0.1.0"))
        assertFalse(Versions.newer("v0.2.0", ""))
    }
}
