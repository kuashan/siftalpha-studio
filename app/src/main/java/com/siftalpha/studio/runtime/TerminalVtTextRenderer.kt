package com.siftalpha.studio.runtime

/**
 * Compatibility wrapper retained for focused text-renderer tests and callers that only have a complete
 * string. v0.5.3 Terminal UI uses [TerminalEmulator] directly so parser, cursor and UTF-8 state survive
 * across incremental Runtime Agent output reads.
 */
object TerminalVtTextRenderer {

    fun render(value: String): String {
        val emulator = TerminalEmulator(
            initialCols = TerminalEmulator.MAX_COLS,
            initialRows = TerminalEmulator.MAX_ROWS,
            scrollbackLimit = 1000,
        )
        emulator.feed(value)
        return emulator.renderText(maxScrollbackLines = 1000)
    }
}
