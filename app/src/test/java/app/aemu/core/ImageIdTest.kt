package app.aemu.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageIdTest {
    @Test
    fun acceptsIdsFromNewId() {
        assertTrue(validImageId("abc234"))
        assertTrue(validImageId("A-Z_09"))
        assertTrue(validImageId("x".repeat(64)))
    }

    @Test
    fun rejectsPathsAndEmptyIds() {
        assertFalse(validImageId(""))
        assertFalse(validImageId(".."))
        assertFalse(validImageId("../data"))
        assertFalse(validImageId("a/b"))
        assertFalse(validImageId("a\\b"))
        assertFalse(validImageId("/abs"))
        assertFalse(validImageId("x".repeat(65)))
        assertFalse(validImageId("id with space"))
    }
}
