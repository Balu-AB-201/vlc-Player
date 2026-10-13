package org.videolan.vlc.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesMultipleLineTimestampsAndSortsThem() {
        val lines = LrcParser.parse(
            "[00:02.00]Second line\n[00:01.25][00:03.25]Repeated line"
        )

        assertEquals(listOf(1_250L, 2_000L, 3_250L), lines.map { it.timestampMs })
        assertEquals("Repeated line", lines.first().text)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun appliesOffsetAndParsesInlineWordTimestamps() {
        val lines = LrcParser.parse(
            "[offset:100]\n[00:01.00]<00:01.10>Hello <00:01.60>world"
        )

        assertEquals(1_100L, lines.single().timestampMs)
        assertEquals("Hello world", lines.single().text)
        assertEquals(listOf(1_200L, 1_700L), lines.single().words.map { it.timestampMs })
        assertEquals(listOf("Hello ", "world"), lines.single().words.map { it.text })
    }

    @Test
    fun ignoresInvalidAndUntimedLines() {
        val lines = LrcParser.parse(
            "[00:75.00]Invalid seconds\nMetadata line\n[01:02.3]Valid"
        )

        assertEquals(1, lines.size)
        assertEquals(62_300L, lines.single().timestampMs)
        assertTrue(lines.single().words.isEmpty())
    }
}
