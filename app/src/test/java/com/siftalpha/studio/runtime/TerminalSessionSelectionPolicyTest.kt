package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalSessionSelectionPolicyTest {

    @Test
    fun preferredExistingSessionWins() {
        val sessions = listOf(
            TerminalSessionSelectionPolicy.Item("a", 1, true),
            TerminalSessionSelectionPolicy.Item("b", 2, true),
        )

        assertEquals("a", TerminalSessionSelectionPolicy.choose("a", "b", sessions))
    }

    @Test
    fun currentExistingSessionWinsWhenPreferredIsMissing() {
        val sessions = listOf(
            TerminalSessionSelectionPolicy.Item("a", 1, true),
            TerminalSessionSelectionPolicy.Item("b", 2, true),
        )

        assertEquals("a", TerminalSessionSelectionPolicy.choose("missing", "a", sessions))
    }

    @Test
    fun newestRunningSessionIsChosenWhenNoExistingPreference() {
        val sessions = listOf(
            TerminalSessionSelectionPolicy.Item("old-running", 2, true),
            TerminalSessionSelectionPolicy.Item("new-stopped", 4, false),
            TerminalSessionSelectionPolicy.Item("new-running", 3, true),
        )

        assertEquals(
            "new-running",
            TerminalSessionSelectionPolicy.choose(null, null, sessions),
        )
    }

    @Test
    fun newestStoppedSessionIsFallbackWhenNoneAreRunning() {
        val sessions = listOf(
            TerminalSessionSelectionPolicy.Item("old", 1, false),
            TerminalSessionSelectionPolicy.Item("new", 2, false),
        )

        assertEquals("new", TerminalSessionSelectionPolicy.choose(null, null, sessions))
    }

    @Test
    fun emptySessionListReturnsNull() {
        assertNull(TerminalSessionSelectionPolicy.choose(null, null, emptyList()))
    }
}
