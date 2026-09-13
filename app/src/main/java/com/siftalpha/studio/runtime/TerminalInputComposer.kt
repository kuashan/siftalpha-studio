package com.siftalpha.studio.runtime

/**
 * Composes Android-side pending text with PTY control bytes so shortcuts operate on the same
 * remote Bash edit buffer the user sees in Studio.
 */
object TerminalInputComposer {
    fun line(pendingText: String): ByteArray =
        (pendingText + "\n").toByteArray(Charsets.UTF_8)

    fun shortcut(pendingText: String, shortcutBytes: ByteArray): ByteArray =
        pendingText.toByteArray(Charsets.UTF_8) + shortcutBytes
}
