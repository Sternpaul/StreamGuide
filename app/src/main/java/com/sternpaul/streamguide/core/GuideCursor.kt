package com.sternpaul.streamguide.core

/** Keeps a focused index inside a fixed-size window of rows that scrolls underneath it. */
object ListWindow {
    fun first(currentFirst: Int, focus: Int, visible: Int, size: Int, margin: Int = 1): Int {
        if (size <= visible || visible <= 0) return 0
        val edge = margin.coerceIn(0, (visible - 1) / 2)
        var first = currentFirst
        if (focus < first + edge) first = focus - edge
        if (focus > first + visible - 1 - edge) first = focus - (visible - 1 - edge)
        return first.coerceIn(0, size - visible)
    }
}

/**
 * Result of a Left/Right press in the guide. [cursor] null means "follow the current time".
 */
data class CursorMove(val cursor: Long?, val shiftMs: Long = 0L, val leaveGrid: Boolean = false)

/**
 * The guide is navigated with a time cursor rather than by screen geometry: Up/Down keep the
 * time and change the row, Left/Right step through one row's programmes and scroll the
 * timeline at its edges.
 */
object GuideCursor {
    const val STEP_MS = 1_800_000L

    fun time(cursor: Long?, now: Long, windowStart: Long, windowEnd: Long): Long =
        (cursor ?: now).coerceIn(windowStart, windowEnd - 1)

    fun focusedIndex(slices: List<TimelineSlice>, cursor: Long?, now: Long, windowStart: Long, windowEnd: Long): Int {
        val t = time(cursor, now, windowStart, windowEnd)
        return slices.indexOfFirst { t >= it.visibleStart && t < it.visibleEnd }.coerceAtLeast(0)
    }

    private fun onto(slice: TimelineSlice, now: Long): Long? =
        if (now >= slice.visibleStart && now < slice.visibleEnd) null else slice.visibleStart

    fun right(slices: List<TimelineSlice>, cursor: Long?, now: Long, windowStart: Long, windowEnd: Long, canShift: Boolean): CursorMove {
        if (slices.isEmpty()) return CursorMove(cursor)
        val index = focusedIndex(slices, cursor, now, windowStart, windowEnd)
        val slice = slices[index]
        if (index < slices.lastIndex) return CursorMove(onto(slices[index + 1], now))
        if (!canShift) return CursorMove(cursor)
        val programEnd = slice.program?.endEpochMs ?: windowEnd
        val t = time(cursor, now, windowStart, windowEnd)
        // A programme that ends at the edge hands over to its successor; a longer one stays
        // focused while the timeline scrolls underneath it.
        return CursorMove(if (programEnd <= windowEnd) windowEnd else maxOf(t, windowStart + STEP_MS), STEP_MS)
    }

    fun left(slices: List<TimelineSlice>, cursor: Long?, now: Long, windowStart: Long, windowEnd: Long, canShift: Boolean): CursorMove {
        if (slices.isEmpty()) return CursorMove(cursor, leaveGrid = !canShift)
        val index = focusedIndex(slices, cursor, now, windowStart, windowEnd)
        val slice = slices[index]
        if (index > 0) return CursorMove(onto(slices[index - 1], now))
        if (!canShift) return CursorMove(cursor, leaveGrid = true)
        val programStart = slice.program?.startEpochMs ?: windowStart
        val t = time(cursor, now, windowStart, windowEnd)
        return CursorMove(if (programStart >= windowStart) windowStart - 1 else minOf(t, windowEnd - STEP_MS - 1), -STEP_MS)
    }
}
