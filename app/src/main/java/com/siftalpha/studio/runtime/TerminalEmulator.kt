package com.siftalpha.studio.runtime

import java.util.ArrayDeque

/**
 * Incremental, cell-based terminal state machine for SiftAlpha Studio.
 *
 * This core deliberately has no Android UI dependency. Runtime Agent output bytes are fed incrementally,
 * including UTF-8 sequences split across network reads. The emulator owns the visible cell grid, cursor,
 * scroll region, scrollback and terminal style state. Android rendering remains a separate concern.
 */
class TerminalEmulator(
    initialCols: Int,
    initialRows: Int,
    private val scrollbackLimit: Int = DEFAULT_SCROLLBACK_LIMIT,
) {

    enum class ColorMode {
        DEFAULT,
        INDEXED,
        RGB,
    }

    data class TerminalColor(
        val mode: ColorMode = ColorMode.DEFAULT,
        val value: Int = 0,
    ) {
        companion object {
            val Default = TerminalColor()
            fun indexed(index: Int) = TerminalColor(ColorMode.INDEXED, index.coerceIn(0, 255))
            fun rgb(red: Int, green: Int, blue: Int) = TerminalColor(
                ColorMode.RGB,
                (red.coerceIn(0, 255) shl 16) or
                    (green.coerceIn(0, 255) shl 8) or
                    blue.coerceIn(0, 255),
            )
        }
    }

    data class Style(
        val bold: Boolean = false,
        val faint: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val blink: Boolean = false,
        val inverse: Boolean = false,
        val hidden: Boolean = false,
        val strike: Boolean = false,
        val foreground: TerminalColor = TerminalColor.Default,
        val background: TerminalColor = TerminalColor.Default,
    )

    data class Cell(
        val text: String = " ",
        val style: Style = Style(),
        val width: Int = 1,
        val continuation: Boolean = false,
    )

    data class Snapshot(
        val cols: Int,
        val rows: Int,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val alternateScreen: Boolean,
        val title: String?,
        val screen: List<List<Cell>>,
        val scrollback: List<List<Cell>>,
    )

    private enum class ParserState {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESCAPE,
        IGNORE_STRING,
        IGNORE_STRING_ESCAPE,
    }

    private data class SavedScreen(
        val screen: List<List<Cell>>,
        val cursorRow: Int,
        val cursorCol: Int,
        val savedRow: Int,
        val savedCol: Int,
        val scrollTop: Int,
        val scrollBottom: Int,
        val wrapPending: Boolean,
        val style: Style,
        val cursorVisible: Boolean,
        val autoWrap: Boolean,
    )

    var cols: Int = sanitizeCols(initialCols)
        private set
    var rows: Int = sanitizeRows(initialRows)
        private set

    private var screen: MutableList<MutableList<Cell>> = newScreen(rows, cols)
    private val scrollback = ArrayDeque<List<Cell>>()
    private var cursorRow: Int = 0
    private var cursorCol: Int = 0
    private var savedRow: Int = 0
    private var savedCol: Int = 0
    private var scrollTop: Int = 0
    private var scrollBottom: Int = rows - 1
    private var wrapPending: Boolean = false
    private var currentStyle: Style = Style()
    private var cursorVisible: Boolean = true
    private var autoWrap: Boolean = true
    private var alternateScreen: Boolean = false
    private var primaryScreenBackup: SavedScreen? = null
    private var title: String? = null

    private var parserState: ParserState = ParserState.GROUND
    private val csiBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var utf8Pending: ByteArray = ByteArray(0)

    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val data = if (utf8Pending.isEmpty()) bytes else utf8Pending + bytes
        var index = 0
        while (index < data.size) {
            val first = data[index].toInt() and 0xFF
            val expected = utf8SequenceLength(first)
            if (expected == 1) {
                acceptCodePoint(first)
                index++
                continue
            }
            if (expected == 0) {
                acceptCodePoint(REPLACEMENT_CODE_POINT)
                index++
                continue
            }
            if (index + expected > data.size) break

            var codePoint = first and when (expected) {
                2 -> 0x1F
                3 -> 0x0F
                else -> 0x07
            }
            var valid = true
            for (offset in 1 until expected) {
                val next = data[index + offset].toInt() and 0xFF
                if (next and 0xC0 != 0x80) {
                    valid = false
                    break
                }
                codePoint = (codePoint shl 6) or (next and 0x3F)
            }
            if (
                !valid ||
                codePoint < minimumCodePoint(expected) ||
                codePoint in 0xD800..0xDFFF ||
                codePoint > 0x10FFFF
            ) {
                acceptCodePoint(REPLACEMENT_CODE_POINT)
                index++
            } else {
                acceptCodePoint(codePoint)
                index += expected
            }
        }
        utf8Pending = if (index < data.size) data.copyOfRange(index, data.size) else ByteArray(0)
    }

    fun feed(text: String) {
        feed(text.toByteArray(Charsets.UTF_8))
    }

    fun resize(newCols: Int, newRows: Int) {
        val targetCols = sanitizeCols(newCols)
        val targetRows = sanitizeRows(newRows)
        if (targetCols == cols && targetRows == rows) return

        // Full-screen applications commonly use the alternate buffer. Keep the hidden primary buffer at
        // the same geometry so leaving top/vim after an Android viewport resize cannot restore stale rows.
        primaryScreenBackup = primaryScreenBackup?.let { resizeSavedScreen(it, targetCols, targetRows) }

        if (targetCols != cols) {
            screen = screen.map { resizeRow(it, targetCols) }.toMutableList()
            cols = targetCols
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            savedCol = savedCol.coerceIn(0, cols - 1)
            wrapPending = false
        }

        if (targetRows < rows) {
            val removeCount = rows - targetRows
            repeat(removeCount) {
                if (screen.isNotEmpty()) {
                    val removed = screen.removeAt(0)
                    if (!alternateScreen) pushScrollback(removed)
                }
            }
            cursorRow = (cursorRow - removeCount).coerceAtLeast(0)
            savedRow = (savedRow - removeCount).coerceAtLeast(0)
        } else if (targetRows > rows) {
            repeat(targetRows - rows) { screen.add(blankRow(cols)) }
        }

        rows = targetRows
        while (screen.size < rows) screen.add(blankRow(cols))
        while (screen.size > rows) screen.removeAt(0)
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        savedRow = savedRow.coerceIn(0, rows - 1)
        scrollTop = 0
        scrollBottom = rows - 1
    }

    fun reset() {
        screen = newScreen(rows, cols)
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        currentStyle = Style()
        cursorVisible = true
        autoWrap = true
        alternateScreen = false
        primaryScreenBackup = null
        title = null
        parserState = ParserState.GROUND
        csiBuffer.setLength(0)
        oscBuffer.setLength(0)
        utf8Pending = ByteArray(0)
    }

    fun snapshot(): Snapshot = Snapshot(
        cols = cols,
        rows = rows,
        cursorRow = cursorRow,
        cursorCol = cursorCol,
        cursorVisible = cursorVisible,
        alternateScreen = alternateScreen,
        title = title,
        screen = screen.map { it.toList() },
        scrollback = scrollback.toList().map { it.toList() },
    )

    fun renderText(maxScrollbackLines: Int = DEFAULT_RENDER_SCROLLBACK_LINES): String {
        val history = if (alternateScreen || maxScrollbackLines <= 0) {
            emptyList()
        } else {
            scrollback.toList().takeLast(maxScrollbackLines)
        }
        val lines = (history + screen.map { it.toList() }).map(::rowToText).toMutableList()
        while (lines.size > 1 && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
    }

    private fun acceptCodePoint(codePoint: Int) {
        when (parserState) {
            ParserState.GROUND -> acceptGround(codePoint)
            ParserState.ESCAPE -> acceptEscape(codePoint)
            ParserState.CSI -> acceptCsi(codePoint)
            ParserState.OSC -> acceptOsc(codePoint)
            ParserState.OSC_ESCAPE -> {
                if (codePoint == '\\'.code) {
                    finishOsc()
                    parserState = ParserState.GROUND
                } else {
                    oscBuffer.appendCodePoint(0x1B)
                    oscBuffer.appendCodePoint(codePoint)
                    parserState = ParserState.OSC
                }
            }
            ParserState.IGNORE_STRING -> {
                if (codePoint == 0x1B) parserState = ParserState.IGNORE_STRING_ESCAPE
            }
            ParserState.IGNORE_STRING_ESCAPE -> {
                parserState = if (codePoint == '\\'.code) ParserState.GROUND else ParserState.IGNORE_STRING
            }
        }
    }

    private fun acceptGround(codePoint: Int) {
        when (codePoint) {
            0x00, 0x07 -> Unit
            0x08 -> {
                cursorCol = (cursorCol - 1).coerceAtLeast(0)
                wrapPending = false
            }
            0x09 -> horizontalTab()
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> {
                cursorCol = 0
                wrapPending = false
            }
            0x1B -> parserState = ParserState.ESCAPE
            else -> if (codePoint >= 0x20 && codePoint != 0x7F) putCodePoint(codePoint)
        }
    }

    private fun acceptEscape(codePoint: Int) {
        when (codePoint) {
            '['.code -> {
                csiBuffer.setLength(0)
                parserState = ParserState.CSI
            }
            ']'.code -> {
                oscBuffer.setLength(0)
                parserState = ParserState.OSC
            }
            'P'.code, '^'.code, '_'.code -> parserState = ParserState.IGNORE_STRING
            '7'.code -> {
                savedRow = cursorRow
                savedCol = cursorCol
                parserState = ParserState.GROUND
            }
            '8'.code -> {
                moveCursor(savedRow, savedCol)
                parserState = ParserState.GROUND
            }
            'D'.code -> {
                lineFeed()
                parserState = ParserState.GROUND
            }
            'E'.code -> {
                lineFeed()
                cursorCol = 0
                parserState = ParserState.GROUND
            }
            'M'.code -> {
                reverseIndex()
                parserState = ParserState.GROUND
            }
            'c'.code -> {
                reset()
                parserState = ParserState.GROUND
            }
            else -> parserState = ParserState.GROUND
        }
    }

    private fun acceptCsi(codePoint: Int) {
        if (codePoint in 0x40..0x7E) {
            handleCsi(csiBuffer.toString(), codePoint.toChar())
            csiBuffer.setLength(0)
            parserState = ParserState.GROUND
        } else if (csiBuffer.length < MAX_CONTROL_SEQUENCE_CHARS) {
            csiBuffer.appendCodePoint(codePoint)
        } else {
            csiBuffer.setLength(0)
            parserState = ParserState.GROUND
        }
    }

    private fun acceptOsc(codePoint: Int) {
        when (codePoint) {
            0x07 -> {
                finishOsc()
                parserState = ParserState.GROUND
            }
            0x1B -> parserState = ParserState.OSC_ESCAPE
            else -> if (oscBuffer.length < MAX_OSC_CHARS) oscBuffer.appendCodePoint(codePoint)
        }
    }

    private fun finishOsc() {
        val raw = oscBuffer.toString()
        val separator = raw.indexOf(';')
        if (separator > 0) {
            val command = raw.substring(0, separator).toIntOrNull()
            if (command == 0 || command == 2) title = raw.substring(separator + 1).take(MAX_TITLE_CHARS)
        }
        oscBuffer.setLength(0)
    }

    private fun handleCsi(raw: String, finalChar: Char) {
        val privateMode = raw.startsWith('?')
        val normalized = raw.trimStart('?', '>', '!', '=')
        val params = if (normalized.isBlank()) {
            emptyList()
        } else {
            normalized.split(';').map { it.toIntOrNull() ?: 0 }
        }
        fun p(index: Int, default: Int = 1): Int {
            val value = params.getOrNull(index) ?: return default
            return if (value == 0) default else value
        }

        when (finalChar) {
            'A' -> moveCursor(cursorRow - p(0), cursorCol)
            'B' -> moveCursor(cursorRow + p(0), cursorCol)
            'C' -> moveCursor(cursorRow, cursorCol + p(0))
            'D' -> moveCursor(cursorRow, cursorCol - p(0))
            'E' -> moveCursor(cursorRow + p(0), 0)
            'F' -> moveCursor(cursorRow - p(0), 0)
            'G', '`' -> moveCursor(cursorRow, p(0) - 1)
            'd' -> moveCursor(p(0) - 1, cursorCol)
            'H', 'f' -> moveCursor(p(0) - 1, p(1) - 1)
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'm' -> applySgr(if (params.isEmpty()) listOf(0) else params)
            's' -> {
                savedRow = cursorRow
                savedCol = cursorCol
            }
            'u' -> moveCursor(savedRow, savedCol)
            '@' -> insertChars(p(0))
            'P' -> deleteChars(p(0))
            'X' -> eraseChars(p(0))
            'L' -> insertLines(p(0))
            'M' -> deleteLines(p(0))
            'S' -> scrollUp(p(0))
            'T' -> scrollDown(p(0))
            'r' -> setScrollRegion(params)
            'h' -> setModes(privateMode, params, true)
            'l' -> setModes(privateMode, params, false)
        }
    }

    private fun setModes(privateMode: Boolean, params: List<Int>, enabled: Boolean) {
        if (!privateMode) return
        params.forEach { mode ->
            when (mode) {
                7 -> autoWrap = enabled
                25 -> cursorVisible = enabled
                47, 1047, 1049 -> if (enabled) enterAlternateScreen() else exitAlternateScreen()
            }
        }
    }

    private fun enterAlternateScreen() {
        if (alternateScreen) return
        primaryScreenBackup = SavedScreen(
            screen = screen.map { it.toList() },
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            savedRow = savedRow,
            savedCol = savedCol,
            scrollTop = scrollTop,
            scrollBottom = scrollBottom,
            wrapPending = wrapPending,
            style = currentStyle,
            cursorVisible = cursorVisible,
            autoWrap = autoWrap,
        )
        screen = newScreen(rows, cols)
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        alternateScreen = true
    }

    private fun exitAlternateScreen() {
        val backup = primaryScreenBackup ?: return
        screen = backup.screen.map { it.toMutableList() }.toMutableList()
        cursorRow = backup.cursorRow.coerceIn(0, rows - 1)
        cursorCol = backup.cursorCol.coerceIn(0, cols - 1)
        savedRow = backup.savedRow.coerceIn(0, rows - 1)
        savedCol = backup.savedCol.coerceIn(0, cols - 1)
        scrollTop = backup.scrollTop.coerceIn(0, rows - 1)
        scrollBottom = backup.scrollBottom.coerceIn(scrollTop, rows - 1)
        wrapPending = backup.wrapPending
        currentStyle = backup.style
        cursorVisible = backup.cursorVisible
        autoWrap = backup.autoWrap
        alternateScreen = false
        primaryScreenBackup = null
    }

    private fun resizeSavedScreen(source: SavedScreen, targetCols: Int, targetRows: Int): SavedScreen {
        val sourceCols = source.screen.firstOrNull()?.size ?: targetCols
        val resized = source.screen.map { resizeRow(it, targetCols) }.toMutableList()
        var removedFromTop = 0
        if (resized.size > targetRows) {
            removedFromTop = resized.size - targetRows
            repeat(removedFromTop) {
                if (resized.isNotEmpty()) pushScrollback(resized.removeAt(0))
            }
        } else {
            while (resized.size < targetRows) resized.add(blankRow(targetCols))
        }
        while (resized.size > targetRows) resized.removeAt(0)

        return source.copy(
            screen = resized.map { it.toList() },
            cursorRow = (source.cursorRow - removedFromTop).coerceIn(0, targetRows - 1),
            cursorCol = source.cursorCol.coerceIn(0, targetCols - 1),
            savedRow = (source.savedRow - removedFromTop).coerceIn(0, targetRows - 1),
            savedCol = source.savedCol.coerceIn(0, targetCols - 1),
            scrollTop = 0,
            scrollBottom = targetRows - 1,
            wrapPending = source.wrapPending && sourceCols == targetCols,
        )
    }

    private fun setScrollRegion(params: List<Int>) {
        val top = ((params.getOrNull(0) ?: 1).let { if (it == 0) 1 else it } - 1).coerceIn(0, rows - 1)
        val bottom = ((params.getOrNull(1) ?: rows).let { if (it == 0) rows else it } - 1).coerceIn(0, rows - 1)
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
            moveCursor(0, 0)
        }
    }

    private fun putCodePoint(codePoint: Int) {
        val width = codePointWidth(codePoint)
        if (width == 0) {
            appendCombining(codePoint)
            return
        }
        if (wrapPending) {
            if (autoWrap) {
                lineFeed()
                cursorCol = 0
            }
            wrapPending = false
        }
        if (width == 2 && cursorCol == cols - 1) {
            if (autoWrap) {
                lineFeed()
                cursorCol = 0
            } else {
                clearCellForWrite(cursorRow, cursorCol)
                screen[cursorRow][cursorCol] = Cell(" ", currentStyle)
                return
            }
        }

        clearCellForWrite(cursorRow, cursorCol)
        if (width == 2) clearCellForWrite(cursorRow, cursorCol + 1)
        val text = String(Character.toChars(codePoint))
        screen[cursorRow][cursorCol] = Cell(text, currentStyle, width = width)
        if (width == 2) {
            screen[cursorRow][cursorCol + 1] = Cell("", currentStyle, width = 0, continuation = true)
        }

        val next = cursorCol + width
        if (next >= cols) {
            cursorCol = cols - 1
            wrapPending = autoWrap
        } else {
            cursorCol = next
        }
    }

    private fun appendCombining(codePoint: Int) {
        var targetCol = when {
            wrapPending -> cols - 1
            cursorCol > 0 -> cursorCol - 1
            else -> -1
        }
        if (targetCol < 0) return
        if (screen[cursorRow][targetCol].continuation) targetCol--
        if (targetCol < 0) return
        val cell = screen[cursorRow][targetCol]
        screen[cursorRow][targetCol] = cell.copy(text = cell.text + String(Character.toChars(codePoint)))
    }

    private fun horizontalTab() {
        val target = (((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH).coerceAtMost(cols - 1)
        cursorCol = target
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else {
            cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
        }
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else {
            cursorRow = (cursorRow - 1).coerceAtLeast(0)
        }
    }

    private fun moveCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun scrollUp(count: Int) {
        repeat(count.coerceAtLeast(1)) {
            val removed = screen.removeAt(scrollTop)
            if (scrollTop == 0 && scrollBottom == rows - 1 && !alternateScreen) pushScrollback(removed)
            screen.add(scrollBottom, blankRow(cols))
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceAtLeast(1)) {
            screen.removeAt(scrollBottom)
            screen.add(scrollTop, blankRow(cols))
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtLeast(1).coerceAtMost(scrollBottom - cursorRow + 1)) {
            screen.removeAt(scrollBottom)
            screen.add(cursorRow, blankRow(cols))
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count.coerceAtLeast(1).coerceAtMost(scrollBottom - cursorRow + 1)) {
            screen.removeAt(cursorRow)
            screen.add(scrollBottom, blankRow(cols))
        }
    }

    private fun insertChars(count: Int) {
        val amount = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        val row = screen[cursorRow]
        repeat(amount) {
            row.add(cursorCol, Cell(style = currentStyle))
            row.removeAt(row.lastIndex)
        }
        normalizeWideCells(row)
        wrapPending = false
    }

    private fun deleteChars(count: Int) {
        val amount = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        val row = screen[cursorRow]
        repeat(amount) {
            row.removeAt(cursorCol)
            row.add(Cell(style = currentStyle))
        }
        normalizeWideCells(row)
        wrapPending = false
    }

    private fun eraseChars(count: Int) {
        val amount = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
        for (col in cursorCol until cursorCol + amount) {
            clearCellForWrite(cursorRow, col)
            screen[cursorRow][col] = Cell(style = currentStyle)
        }
        wrapPending = false
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            1 -> for (col in 0..cursorCol) screen[cursorRow][col] = Cell(style = currentStyle)
            2 -> screen[cursorRow] = blankRow(cols, currentStyle)
            else -> for (col in cursorCol until cols) screen[cursorRow][col] = Cell(style = currentStyle)
        }
        normalizeWideCells(screen[cursorRow])
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            1 -> {
                for (row in 0 until cursorRow) screen[row] = blankRow(cols, currentStyle)
                eraseLine(1)
            }
            2 -> {
                for (row in 0 until rows) screen[row] = blankRow(cols, currentStyle)
                wrapPending = false
            }
            3 -> scrollback.clear()
            else -> {
                eraseLine(0)
                for (row in cursorRow + 1 until rows) screen[row] = blankRow(cols, currentStyle)
            }
        }
    }

    private fun applySgr(params: List<Int>) {
        var index = 0
        while (index < params.size) {
            when (val code = params[index]) {
                0 -> currentStyle = Style()
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(faint = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                5, 6 -> currentStyle = currentStyle.copy(blink = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                8 -> currentStyle = currentStyle.copy(hidden = true)
                9 -> currentStyle = currentStyle.copy(strike = true)
                22 -> currentStyle = currentStyle.copy(bold = false, faint = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                25 -> currentStyle = currentStyle.copy(blink = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                28 -> currentStyle = currentStyle.copy(hidden = false)
                29 -> currentStyle = currentStyle.copy(strike = false)
                in 30..37 -> currentStyle = currentStyle.copy(foreground = TerminalColor.indexed(code - 30))
                39 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Default)
                in 40..47 -> currentStyle = currentStyle.copy(background = TerminalColor.indexed(code - 40))
                49 -> currentStyle = currentStyle.copy(background = TerminalColor.Default)
                in 90..97 -> currentStyle = currentStyle.copy(foreground = TerminalColor.indexed(code - 90 + 8))
                in 100..107 -> currentStyle = currentStyle.copy(background = TerminalColor.indexed(code - 100 + 8))
                38, 48 -> {
                    val parsed = parseExtendedColor(params, index + 1)
                    if (parsed != null) {
                        if (code == 38) currentStyle = currentStyle.copy(foreground = parsed.first)
                        else currentStyle = currentStyle.copy(background = parsed.first)
                        index = parsed.second
                    }
                }
            }
            index++
        }
    }

    private fun parseExtendedColor(params: List<Int>, start: Int): Pair<TerminalColor, Int>? {
        return when (params.getOrNull(start)) {
            5 -> {
                val value = params.getOrNull(start + 1) ?: return null
                TerminalColor.indexed(value) to (start + 1)
            }
            2 -> {
                val red = params.getOrNull(start + 1) ?: return null
                val green = params.getOrNull(start + 2) ?: return null
                val blue = params.getOrNull(start + 3) ?: return null
                TerminalColor.rgb(red, green, blue) to (start + 3)
            }
            else -> null
        }
    }

    private fun clearCellForWrite(row: Int, col: Int) {
        if (row !in screen.indices || col !in 0 until cols) return
        val current = screen[row][col]
        when {
            current.continuation && col > 0 -> {
                screen[row][col - 1] = Cell()
                screen[row][col] = Cell()
            }
            current.width == 2 -> {
                screen[row][col] = Cell()
                if (col + 1 < cols) screen[row][col + 1] = Cell()
            }
        }
    }

    private fun normalizeWideCells(row: MutableList<Cell>) {
        for (col in row.indices) {
            val cell = row[col]
            if (cell.continuation) {
                if (col == 0 || row[col - 1].width != 2) row[col] = Cell()
            } else if (cell.width == 2) {
                if (col + 1 >= row.size) {
                    row[col] = Cell()
                } else {
                    row[col + 1] = Cell("", cell.style, width = 0, continuation = true)
                }
            }
        }
    }

    private fun resizeRow(source: List<Cell>, targetCols: Int): MutableList<Cell> {
        val result = MutableList(targetCols) { Cell() }
        val count = minOf(source.size, targetCols)
        for (index in 0 until count) result[index] = source[index]
        normalizeWideCells(result)
        return result
    }

    private fun pushScrollback(row: List<Cell>) {
        if (scrollbackLimit <= 0) return
        scrollback.addLast(row.toList())
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    private fun rowToText(row: List<Cell>): String {
        val value = buildString {
            row.forEach { cell -> if (!cell.continuation) append(cell.text) }
        }
        return value.trimEnd()
    }

    companion object {
        const val MIN_COLS = 20
        const val MAX_COLS = 300
        const val MIN_ROWS = 5
        const val MAX_ROWS = 120
        private const val DEFAULT_SCROLLBACK_LIMIT = 2000
        private const val DEFAULT_RENDER_SCROLLBACK_LINES = 500
        private const val TAB_WIDTH = 8
        private const val MAX_CONTROL_SEQUENCE_CHARS = 256
        private const val MAX_OSC_CHARS = 4096
        private const val MAX_TITLE_CHARS = 512
        private const val REPLACEMENT_CODE_POINT = 0xFFFD

        private fun sanitizeCols(value: Int): Int = value.coerceIn(MIN_COLS, MAX_COLS)
        private fun sanitizeRows(value: Int): Int = value.coerceIn(MIN_ROWS, MAX_ROWS)

        private fun blankRow(cols: Int, style: Style = Style()): MutableList<Cell> =
            MutableList(cols) { Cell(style = style) }

        private fun newScreen(rows: Int, cols: Int): MutableList<MutableList<Cell>> =
            MutableList(rows) { blankRow(cols) }

        private fun utf8SequenceLength(first: Int): Int = when {
            first <= 0x7F -> 1
            first in 0xC2..0xDF -> 2
            first in 0xE0..0xEF -> 3
            first in 0xF0..0xF4 -> 4
            else -> 0
        }

        private fun minimumCodePoint(length: Int): Int = when (length) {
            2 -> 0x80
            3 -> 0x800
            4 -> 0x10000
            else -> 0
        }

        internal fun codePointWidth(codePoint: Int): Int {
            if (codePoint == 0) return 0
            val type = Character.getType(codePoint)
            if (
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.FORMAT.toInt()
            ) return 0
            if (codePoint < 0x20 || codePoint in 0x7F..0x9F) return 0

            return if (
                codePoint >= 0x1100 && (
                    codePoint <= 0x115F ||
                        codePoint == 0x2329 || codePoint == 0x232A ||
                        codePoint in 0x2E80..0x303E ||
                        codePoint in 0x3040..0xA4CF ||
                        codePoint in 0xAC00..0xD7A3 ||
                        codePoint in 0xF900..0xFAFF ||
                        codePoint in 0xFE10..0xFE19 ||
                        codePoint in 0xFE30..0xFE6F ||
                        codePoint in 0xFF00..0xFF60 ||
                        codePoint in 0xFFE0..0xFFE6 ||
                        codePoint in 0x1F300..0x1FAFF ||
                        codePoint in 0x20000..0x3FFFD
                    )
            ) 2 else 1
        }
    }
}
