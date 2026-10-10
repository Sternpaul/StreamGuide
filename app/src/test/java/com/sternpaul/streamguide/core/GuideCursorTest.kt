package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCursorTest {
    private val m = 60_000L
    private val channel = Channel("c", "C", "u")
    private fun program(title: String, start: Long, end: Long) = Program("c", title, startEpochMs = start * m, endEpochMs = end * m)
    // Window 0..180 min. Programmes: A -20..40, B 40..100, C 100..180, D 180..240, E 240..400
    private val programs = listOf(program("A", -20, 40), program("B", 40, 100), program("C", 100, 180), program("D", 180, 240), program("E", 240, 400))
    private fun slices(start: Long, end: Long) = GuideTimeline.slices(channel, programs, start * m, end * m)

    @Test
    fun nullCursorFocusesTheProgrammeAiringNow() {
        assertEquals(0, GuideCursor.focusedIndex(slices(0, 180), null, 10 * m, 0, 180 * m))
        assertEquals(1, GuideCursor.focusedIndex(slices(0, 180), null, 45 * m, 0, 180 * m))
    }

    @Test
    fun rightStepsToTheNextProgrammeStart() {
        val move = GuideCursor.right(slices(0, 180), null, 10 * m, 0, 180 * m, canShift = true)
        assertEquals(CursorMove(40 * m), move)
    }

    @Test
    fun rightAtTheEdgeScrollsAndLandsOnTheSuccessor() {
        val move = GuideCursor.right(slices(0, 180), 100 * m, 10 * m, 0, 180 * m, canShift = true)
        assertEquals(CursorMove(180 * m, GuideCursor.STEP_MS), move)
        val shifted = slices(30, 210)
        assertEquals("D", shifted[GuideCursor.focusedIndex(shifted, move.cursor, 10 * m, 30 * m, 210 * m)].program?.title)
    }

    @Test
    fun rightKeepsALongProgrammeFocusedWhileScrolling() {
        // Window 150..330: E runs past the edge.
        val window = slices(150, 330)
        val move = GuideCursor.right(window, 240 * m, 10 * m, 150 * m, 330 * m, canShift = true)
        assertEquals(GuideCursor.STEP_MS, move.shiftMs)
        val shifted = slices(180, 360)
        assertEquals("E", shifted[GuideCursor.focusedIndex(shifted, move.cursor, 10 * m, 180 * m, 360 * m)].program?.title)
    }

    @Test
    fun rightStopsWhenTheTimelineCannotScroll() {
        assertEquals(CursorMove(100 * m), GuideCursor.right(slices(0, 180), 100 * m, 10 * m, 0, 180 * m, canShift = false))
    }

    @Test
    fun leftOntoTheLiveProgrammeFollowsTheClockAgain() {
        val move = GuideCursor.left(slices(0, 180), 40 * m, 10 * m, 0, 180 * m, canShift = false)
        assertNull(move.cursor)
        assertFalse(move.leaveGrid)
    }

    @Test
    fun leftAtTheHomeEdgeLeavesTheGrid() {
        assertTrue(GuideCursor.left(slices(0, 180), null, 10 * m, 0, 180 * m, canShift = false).leaveGrid)
    }

    @Test
    fun leftAtAScrolledEdgeScrollsBackOntoThePredecessor() {
        // Window 180..360 starts exactly with D.
        val move = GuideCursor.left(slices(180, 360), 180 * m, 10 * m, 180 * m, 360 * m, canShift = true)
        assertEquals(-GuideCursor.STEP_MS, move.shiftMs)
        val shifted = slices(150, 330)
        assertEquals("C", shifted[GuideCursor.focusedIndex(shifted, move.cursor, 10 * m, 150 * m, 330 * m)].program?.title)
    }

    @Test
    fun gapsWithoutGuideDataAreStillReachable() {
        val sparse = GuideTimeline.slices(channel, listOf(program("B", 40, 100)), 0, 180 * m)
        assertEquals(3, sparse.size)
        assertEquals(0, GuideCursor.focusedIndex(sparse, null, 10 * m, 0, 180 * m))
        assertEquals(CursorMove(40 * m), GuideCursor.right(sparse, null, 10 * m, 0, 180 * m, canShift = true))
    }

    @Test
    fun listWindowKeepsFocusAwayFromTheEdges() {
        assertEquals(0, ListWindow.first(0, 0, 8, 100, 2))
        assertEquals(0, ListWindow.first(0, 5, 8, 100, 2))
        assertEquals(1, ListWindow.first(0, 6, 8, 100, 2))
        assertEquals(92, ListWindow.first(0, 99, 8, 100, 2))
        assertEquals(48, ListWindow.first(60, 50, 8, 100, 2))
        assertEquals(0, ListWindow.first(3, 2, 8, 5, 2))
    }
}
