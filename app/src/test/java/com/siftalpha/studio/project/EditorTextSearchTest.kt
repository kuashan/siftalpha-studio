package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTextSearchTest {
    @Test
    fun `findAll is case insensitive by default contract`() {
        val matches = EditorTextSearch.findAll("Alpha alpha ALPHA", "alpha", caseSensitive = false)
        assertEquals(listOf(0, 6, 12), matches.map { it.start })
    }

    @Test
    fun `findAll respects case sensitive mode`() {
        val matches = EditorTextSearch.findAll("Alpha alpha ALPHA", "alpha", caseSensitive = true)
        assertEquals(listOf(6), matches.map { it.start })
    }

    @Test
    fun `findAll returns non overlapping ranges`() {
        val matches = EditorTextSearch.findAll("aaaa", "aa", caseSensitive = true)
        assertEquals(
            listOf(
                EditorTextSearch.Match(0, 2),
                EditorTextSearch.Match(2, 4),
            ),
            matches,
        )
    }

    @Test
    fun `empty query never matches`() {
        assertTrue(EditorTextSearch.findAll("content", "", caseSensitive = false).isEmpty())
    }

    @Test
    fun `indexAtOrAfter uses cursor anchor and wraps`() {
        val matches = listOf(
            EditorTextSearch.Match(2, 4),
            EditorTextSearch.Match(10, 12),
        )
        assertEquals(1, EditorTextSearch.indexAtOrAfter(matches, 5))
        assertEquals(0, EditorTextSearch.indexAtOrAfter(matches, 20))
    }

    @Test
    fun `stepIndex wraps in both directions`() {
        assertEquals(0, EditorTextSearch.stepIndex(3, 2, forward = true))
        assertEquals(2, EditorTextSearch.stepIndex(3, 0, forward = false))
        assertEquals(0, EditorTextSearch.stepIndex(3, -1, forward = true))
        assertEquals(2, EditorTextSearch.stepIndex(3, -1, forward = false))
    }
}
