package com.example.lyrics

import java.util.LinkedList

data class LyricsEditorSnapshot(
    val lines: List<LyricLine>,
    val plainText: String,
    val offsetMs: Long,
    val activeLineIndex: Int
)

class LyricsTimestampEditor(
    initialLines: List<LyricLine>,
    initialPlainText: String = "",
    initialOffsetMs: Long = 0L
) {
    private val undoStack = LinkedList<LyricsEditorSnapshot>()
    private val redoStack = LinkedList<LyricsEditorSnapshot>()

    private var currentLines: MutableList<LyricLine> = initialLines.toMutableList()
    var plainText: String = initialPlainText
        private set
    var offsetMs: Long = initialOffsetMs
        private set
    var activeLineIndex: Int = 0

    init {
        // If initial lines are empty but plainText is provided, split plainText into untimed lines
        if (currentLines.isEmpty() && initialPlainText.isNotBlank()) {
            currentLines = initialPlainText.lines()
                .filter { it.isNotBlank() }
                .mapIndexed { idx, line -> LyricLine(timeMs = (idx * 3000).toLong(), text = line.trim()) }
                .toMutableList()
        }
    }

    val lines: List<LyricLine>
        get() = currentLines.toList()

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    val canRedo: Boolean
        get() = redoStack.isNotEmpty()

    private fun createSnapshot(): LyricsEditorSnapshot {
        return LyricsEditorSnapshot(
            lines = currentLines.map { it.copy() },
            plainText = plainText,
            offsetMs = offsetMs,
            activeLineIndex = activeLineIndex
        )
    }

    private fun pushSnapshot() {
        undoStack.push(createSnapshot())
        if (undoStack.size > 50) {
            undoStack.removeLast()
        }
        redoStack.clear()
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        redoStack.push(createSnapshot())
        val previous = undoStack.pop()
        restoreSnapshot(previous)
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        undoStack.push(createSnapshot())
        val next = redoStack.pop()
        restoreSnapshot(next)
        return true
    }

    private fun restoreSnapshot(snapshot: LyricsEditorSnapshot) {
        currentLines = snapshot.lines.map { it.copy() }.toMutableList()
        plainText = snapshot.plainText
        offsetMs = snapshot.offsetMs
        activeLineIndex = snapshot.activeLineIndex.coerceIn(0, (currentLines.size - 1).coerceAtLeast(0))
    }

    /**
     * Stamps the current playback position (ms) to the active line.
     * Automatically advances to the next line for seamless live syncing during playback.
     */
    fun stampCurrentTime(playbackPositionMs: Long) {
        if (currentLines.isEmpty()) return
        pushSnapshot()
        val targetIdx = activeLineIndex.coerceIn(0, currentLines.lastIndex)
        val line = currentLines[targetIdx]
        currentLines[targetIdx] = line.copy(timeMs = playbackPositionMs.coerceAtLeast(0L))

        // Sort lines up to current index or keep order
        if (activeLineIndex < currentLines.lastIndex) {
            activeLineIndex++
        }
    }

    /**
     * Shifts a specific line's timestamp by deltaMs (+/- 100ms, +/- 500ms, etc.).
     */
    fun adjustLineTimestamp(index: Int, deltaMs: Long) {
        if (index in currentLines.indices) {
            pushSnapshot()
            val old = currentLines[index]
            val newTime = (old.timeMs + deltaMs).coerceAtLeast(0L)
            currentLines[index] = old.copy(timeMs = newTime)
        }
    }

    /**
     * Updates the timestamp of a specific line directly.
     */
    fun setLineTimestamp(index: Int, newTimeMs: Long) {
        if (index in currentLines.indices) {
            pushSnapshot()
            currentLines[index] = currentLines[index].copy(timeMs = newTimeMs.coerceAtLeast(0L))
        }
    }

    /**
     * Updates the lyric text of a specific line.
     */
    fun setLineText(index: Int, newText: String) {
        if (index in currentLines.indices) {
            pushSnapshot()
            currentLines[index] = currentLines[index].copy(text = newText)
        }
    }

    /**
     * Shifts all lines forward or backward by deltaMs (Global Offset).
     */
    fun shiftAllTimestamps(deltaMs: Long) {
        if (currentLines.isEmpty()) return
        pushSnapshot()
        for (i in currentLines.indices) {
            val old = currentLines[i]
            currentLines[i] = old.copy(timeMs = (old.timeMs + deltaMs).coerceAtLeast(0L))
        }
    }

    /**
     * Inserts a new line at the specified position.
     */
    fun insertLine(index: Int, text: String = "", timeMs: Long? = null) {
        pushSnapshot()
        val insertTime = timeMs ?: if (index in currentLines.indices) currentLines[index].timeMs else 0L
        val newLine = LyricLine(timeMs = insertTime, text = text)
        val pos = index.coerceIn(0, currentLines.size)
        currentLines.add(pos, newLine)
        activeLineIndex = pos
    }

    /**
     * Deletes a line at the specified position.
     */
    fun deleteLine(index: Int) {
        if (index in currentLines.indices) {
            pushSnapshot()
            currentLines.removeAt(index)
            if (activeLineIndex >= currentLines.size) {
                activeLineIndex = (currentLines.size - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * Splits untimed raw lyrics text into a fresh set of lines with default spacing.
     */
    fun parseRawText(rawText: String) {
        pushSnapshot()
        plainText = rawText
        currentLines = rawText.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { idx, line -> LyricLine(timeMs = (idx * 3000).toLong(), text = line.trim()) }
            .toMutableList()
        activeLineIndex = 0
    }
}
