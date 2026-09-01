package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgDiagnosticsCalculatorTest {
    @Test
    fun reportsNamedCoverageAndMismatchCounts() {
        val channels = listOf(
            Channel("internal-news", "News", "https://example/news", tvgId = "news.tv"),
            Channel("sports.tv", "Sports", "https://example/sports", tvgId = "sports.tv"),
            Channel("kids", "Kids", "https://example/kids"),
            Channel("hidden", "Hidden", "https://example/hidden", tvgId = "hidden.tv", hidden = true)
        )
        val result = EpgDiagnosticsCalculator.calculate(
            channels,
            EpgDiagnosticInput(
                countsByChannelId = mapOf("news.tv" to 20, "sports.tv" to 10, "unknown.tv" to 5, "hidden.tv" to 99),
                totalPrograms = 134,
                currentlyAiringPrograms = 2,
                upcomingPrograms24h = 25,
                guideStartEpochMs = 100,
                guideEndEpochMs = 200
            ),
            nowEpochMs = 150
        )

        assertEquals(3, result.totalChannels)
        assertEquals(2, result.channelsWithEpg)
        assertEquals(129, result.matchedPrograms)
        assertEquals(3, result.matchedEpgChannelIds)
        assertEquals(4, result.totalEpgChannelIds)
        assertEquals(listOf("unknown.tv"), result.unmatchedEpgIds.map { it.id })
        assertEquals(listOf("Kids"), result.channelsWithoutEpg)
        assertEquals(1, result.channelsWithoutTvgId)
        assertEquals(66, result.channelCoveragePercent)
    }

    @Test
    fun duplicateTvgIdsAreReportedWithoutDoubleCountingPrograms() {
        val channels = listOf(
            Channel("one", "One", "https://example/1", tvgId = "shared.tv"),
            Channel("two", "Two", "https://example/2", tvgId = "shared.tv")
        )
        val result = EpgDiagnosticsCalculator.calculate(
            channels,
            EpgDiagnosticInput(mapOf("shared.tv" to 12), 12, 1, 5, 100, 200),
            nowEpochMs = 150
        )

        assertEquals(2, result.channelsWithEpg)
        assertEquals(12, result.matchedPrograms)
        assertEquals(1, result.duplicateTvgIds)
    }
}
