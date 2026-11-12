package com.audeo.app.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareVersionsTest {

    @Test
    fun comparesEachNumberNotText() {
        assertTrue(compareVersions("2.3.21", "2.3.13") > 0)
        assertTrue(compareVersions("2.3.9", "2.3.13") < 0)
    }

    @Test
    fun treatsMissingPartsAsZero() {
        assertEquals(0, compareVersions("2.3", "2.3.0"))
    }

    @Test
    fun ignoresBuildSuffix() {
        assertEquals(0, compareVersions("4.1.29-release", "4.1.29"))
    }
}
