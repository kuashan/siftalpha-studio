package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    @Test
    fun utf8SplitAcrossFeedsKeepsWideCellIntegrity() {
        val emulator = TerminalEmulator(20, 5)
        val bytes = "你".toByteArray(Charsets.UTF_8)

        emulator.feed(bytes.copyOfRange(0, 2))
        assertEquals("", emulator.renderText())

        emulator.feed(bytes.copyOfRange(2, 3))
        val snapshot = emulator.snapshot()
        assertEquals("你", emulator.renderText())
        assertEquals("你", snapshot.screen[0][0].text)
        assertEquals(2, snapshot.screen[0][0].width)
        assertTrue(snapshot.screen[0][1].continuation)
    }

    @Test
    fun combiningMarkStaysOnPreviousCell() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("e\u0301")

        val snapshot = emulator.snapshot()
        assertEquals("e\u0301", snapshot.screen[0][0].text)
        assertEquals(1, snapshot.screen[0][0].width)
        assertEquals(1, snapshot.cursorCol)
    }

    @Test
    fun sgrStyleIsStoredPerCellAndResetForFollowingText() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("\u001B[1;31mR\u001B[0mN")

        val snapshot = emulator.snapshot()
        val red = snapshot.screen[0][0]
        val normal = snapshot.screen[0][1]
        assertTrue(red.style.bold)
        assertEquals(TerminalEmulator.ColorMode.INDEXED, red.style.foreground.mode)
        assertEquals(1, red.style.foreground.value)
        assertFalse(normal.style.bold)
        assertEquals(TerminalEmulator.ColorMode.DEFAULT, normal.style.foreground.mode)
        assertEquals("RN", emulator.renderText())
    }

    @Test
    fun trueColorSgrIsStoredWithoutRenderingEscapeText() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("\u001B[38;2;12;34;56mX")

        val cell = emulator.snapshot().screen[0][0]
        assertEquals(TerminalEmulator.ColorMode.RGB, cell.style.foreground.mode)
        assertEquals((12 shl 16) or (34 shl 8) or 56, cell.style.foreground.value)
        assertEquals("X", emulator.renderText())
    }

    @Test
    fun csiSequenceMayBeSplitAcrossNetworkFeeds() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("old")
        emulator.feed(byteArrayOf(0x1B, '['.code.toByte()))
        emulator.feed("2J\u001B[Hnew")

        assertEquals("new", emulator.renderText())
    }

    @Test
    fun eraseDisplayDoesNotImplicitlyHomeCursor() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("abc\u001B[2JX")

        assertEquals("   X", emulator.renderText())
        assertEquals(4, emulator.snapshot().cursorCol)
    }

    @Test
    fun carriageReturnAndCursorEditingMutateExistingCells() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("abc\rXYZ")
        emulator.feed("\u001B[2D!")

        assertEquals("X!Z", emulator.renderText())
    }

    @Test
    fun fullScreenScrollMovesOldRowsIntoBoundedScrollback() {
        val emulator = TerminalEmulator(20, 5, scrollbackLimit = 2)
        emulator.feed("one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix\r\nseven")

        val snapshot = emulator.snapshot()
        assertEquals(2, snapshot.scrollback.size)
        assertEquals("one", rowText(snapshot.scrollback[0]))
        assertEquals("two", rowText(snapshot.scrollback[1]))
        assertEquals("one\ntwo\nthree\nfour\nfive\nsix\nseven", emulator.renderText())
    }

    @Test
    fun alternateScreenRestoresPrimaryScreenAndCursor() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("PRIMARY")
        val primaryCursor = emulator.snapshot().cursorCol

        emulator.feed("\u001B[?1049hALT")
        assertTrue(emulator.snapshot().alternateScreen)
        assertEquals("ALT", emulator.renderText())

        emulator.feed("\u001B[?1049l")
        val restored = emulator.snapshot()
        assertFalse(restored.alternateScreen)
        assertEquals("PRIMARY", emulator.renderText())
        assertEquals(primaryCursor, restored.cursorCol)
    }

    @Test
    fun alternateScreenResizeAlsoResizesHiddenPrimaryGeometry() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("PRIMARY")
        emulator.feed("\u001B[?1049hALT")

        emulator.resize(30, 6)
        val alternate = emulator.snapshot()
        assertTrue(alternate.alternateScreen)
        assertEquals(30, alternate.cols)
        assertEquals(6, alternate.rows)
        assertEquals(6, alternate.screen.size)
        assertTrue(alternate.screen.all { it.size == 30 })

        emulator.feed("\u001B[?1049l")
        val restored = emulator.snapshot()
        assertFalse(restored.alternateScreen)
        assertEquals(30, restored.cols)
        assertEquals(6, restored.rows)
        assertEquals(6, restored.screen.size)
        assertTrue(restored.screen.all { it.size == 30 })
        assertEquals("PRIMARY", emulator.renderText())
    }

    @Test
    fun cursorVisibilityModeIsTracked() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("\u001B[?25l")
        assertFalse(emulator.snapshot().cursorVisible)
        emulator.feed("\u001B[?25h")
        assertTrue(emulator.snapshot().cursorVisible)
    }

    @Test
    fun resizePreservesVisibleContentAndUpdatesGeometry() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("alpha\r\nbeta")
        emulator.resize(30, 6)

        val snapshot = emulator.snapshot()
        assertEquals(30, snapshot.cols)
        assertEquals(6, snapshot.rows)
        assertTrue(emulator.renderText().contains("alpha\nbeta"))
    }

    @Test
    fun insertDeleteAndEraseCharacterControlsMutateCells() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("ABCDE")
        emulator.feed("\r\u001B[2C\u001B[@X")
        assertEquals("ABXCDE", emulator.renderText())

        emulator.feed("\r\u001B[2C\u001B[P")
        assertEquals("ABCDE", emulator.renderText())

        emulator.feed("\r\u001B[2C\u001B[2X")
        assertEquals("AB  E", emulator.renderText())
    }

    @Test
    fun oscTitleIsMetadataNotVisibleTerminalText() {
        val emulator = TerminalEmulator(20, 5)
        emulator.feed("before\u001B]0;SiftAlpha\u0007after")

        assertEquals("SiftAlpha", emulator.snapshot().title)
        assertEquals("beforeafter", emulator.renderText())
    }

    private fun rowText(row: List<TerminalEmulator.Cell>): String = buildString {
        row.forEach { cell -> if (!cell.continuation) append(cell.text) }
    }.trimEnd()
}
