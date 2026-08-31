package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTimelineTest {
    private val channel = Channel("c1", "News", "https://example/live")

    @Test fun includesProgramsThatOverlapTheVisibleWindowAndClipsTheirBounds() {
        val programs = listOf(
            Program("c1", "Before", startEpochMs = 0, endEpochMs = 2_000),
            Program("c1", "Middle", startEpochMs = 2_000, endEpochMs = 4_000),
            Program("c1", "After", startEpochMs = 4_000, endEpochMs = 8_000),
            Program("other", "Wrong channel", startEpochMs = 2_000, endEpochMs = 4_000)
        )

        val slices = GuideTimeline.slices(channel, programs, windowStart = 1_000, windowEnd = 5_000)

        assertEquals(listOf("Before", "Middle", "After"), slices.map { it.program?.title })
        assertEquals(listOf(1_000L, 2_000L, 4_000L), slices.map { it.visibleStart })
        assertEquals(listOf(2_000L, 4_000L, 5_000L), slices.map { it.visibleEnd })
    }

    @Test fun createsGapSlicesSoTheTimelineAlwaysFillsTheWindow() {
        val programs = listOf(Program("c1", "News", startEpochMs = 2_000, endEpochMs = 3_000))

        val slices = GuideTimeline.slices(channel, programs, windowStart = 1_000, windowEnd = 4_000)

        assertEquals(3, slices.size)
        assertTrue(slices[0].program == null)
        assertEquals("News", slices[1].program?.title)
        assertTrue(slices[2].program == null)
        assertEquals(3_000L, slices.sumOf { it.visibleEnd - it.visibleStart })
    }

    @Test fun matchesXmlTvIdBeforeStableChannelId() {
        val mapped = channel.copy(tvgId = "xml-news")
        val programs = listOf(Program("xml-news", "Mapped", startEpochMs = 1_000, endEpochMs = 2_000))

        assertEquals("Mapped", GuideTimeline.slices(mapped, programs, 1_000, 2_000).single().program?.title)
    }
}
