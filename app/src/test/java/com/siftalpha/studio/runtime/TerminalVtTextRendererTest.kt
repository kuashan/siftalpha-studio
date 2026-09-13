package com.siftalpha.studio.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalVtTextRendererTest {

    @Test
    fun stripsSgrWhileKeepingText() {
        val rendered = TerminalVtTextRenderer.render("\u001B[31mRED\u001B[0m")
        assertEquals("RED", rendered)
    }

    @Test
    fun carriageReturnOverwritesCurrentLine() {
        val rendered = TerminalVtTextRenderer.render("progress 10%\rprogress 90%")
        assertEquals("progress 90%", rendered)
    }

    @Test
    fun backspaceMovesCursorForOverwrite() {
        val rendered = TerminalVtTextRenderer.render("abc\bX")
        assertEquals("abX", rendered)
    }

    @Test
    fun cursorUpAndEraseLineReplacePriorContent() {
        val rendered = TerminalVtTextRenderer.render("first\r\nsecond\u001B[1A\r\u001B[2Kupdated")
        assertEquals("updated\nsecond", rendered)
    }

    @Test
    fun oscTitleSequenceIsNotShown() {
        val rendered = TerminalVtTextRenderer.render("before\u001B]0;hidden title\u0007after")
        assertEquals("beforeafter", rendered)
    }
}
