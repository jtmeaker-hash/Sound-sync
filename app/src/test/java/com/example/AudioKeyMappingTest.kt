package com.example

import com.example.metadata.CamelotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioKeyMappingTest {
    @Test
    fun `maps major and minor keys to Camelot without confusing relative keys`() {
        assertEquals("8A", CamelotKey.fromMusicalKey("A minor"))
        assertEquals("11B", CamelotKey.fromMusicalKey("A major"))
        assertEquals("8B", CamelotKey.fromMusicalKey("C major"))
        assertEquals("5A", CamelotKey.fromMusicalKey("C minor"))
        assertTrue((CamelotKey.fromMusicalKey("unknown") ?: "").isBlank())
    }
}
