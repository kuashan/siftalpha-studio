package com.siftalpha.studio.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TerminalInputComposerTest {

    @Test
    fun lineAppendsEnterAfterPendingUtf8Text() {
        assertArrayEquals(
            "echo 你好\n".toByteArray(Charsets.UTF_8),
            TerminalInputComposer.line("echo 你好"),
        )
    }

    @Test
    fun tabIsSentAfterPendingTextInSamePayload() {
        assertArrayEquals(
            byteArrayOf('e'.code.toByte(), 'c'.code.toByte(), 9),
            TerminalInputComposer.shortcut("ec", byteArrayOf(9)),
        )
    }

    @Test
    fun arrowSequenceIsSentAfterPendingTextInSamePayload() {
        assertArrayEquals(
            "echo AC\u001B[D".toByteArray(Charsets.US_ASCII),
            TerminalInputComposer.shortcut(
                "echo AC",
                "\u001B[D".toByteArray(Charsets.US_ASCII),
            ),
        )
    }

    @Test
    fun shortcutWithoutPendingTextPreservesRawControlBytes() {
        val raw = byteArrayOf(3)
        assertArrayEquals(raw, TerminalInputComposer.shortcut("", raw))
    }
}
