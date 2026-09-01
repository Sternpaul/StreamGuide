package com.sternpaul.streamguide

import com.sternpaul.streamguide.core.Channel
import com.sternpaul.streamguide.core.Program
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UiStatePerformanceTest {
    @Test(timeout = 2_000)
    fun firstSearchCharacterDoesNotScanEveryProgrammeForEveryChannel() {
        val channels = (0 until 10_000).map { index ->
            Channel("channel-$index", "Channel $index", "https://live/$index", tvgId = "epg-$index", providerOrder = index)
        }
        val programmes = (0 until 20_000).map { index ->
            Program("epg-${index % channels.size}", if (index == 19_999) "Zebra News" else "Programme $index", startEpochMs = index.toLong(), endEpochMs = index + 1L)
        }

        val results = UiState(channels = channels, programs = programmes, query = "z").visibleChannels

        assertEquals(listOf("channel-9999"), results.map { it.id })
    }

    @Test
    fun typingAnotherSearchCharacterReusesTheProgrammeIndex() {
        val state = UiState(
            channels = listOf(Channel("one", "One", "https://live/one")),
            programs = listOf(Program("one", "News", startEpochMs = 1L, endEpochMs = 2L))
        )

        assertSame(state.programIndex, state.copy(query = "n").programIndex)
    }

    @Test(timeout = 2_000)
    fun longGuideUsesIndexedProgrammesForVisibleRows() {
        val channels = (0 until 10_000).map { index ->
            Channel("channel-$index", "Channel $index", "https://live/$index", tvgId = "epg-$index", providerOrder = index)
        }
        val programmes = (0 until 100_000).map { index ->
            Program("epg-${index % channels.size}", "Programme $index", startEpochMs = index.toLong(), endEpochMs = index + 10_000L)
        }
        val state = UiState(channels = channels, programs = programmes)

        val firstHundredProgrammeCounts = state.visibleChannels.take(100).map { channel ->
            state.programsFor(channel).size
        }

        assertEquals(100, firstHundredProgrammeCounts.size)
        assertEquals(10, firstHundredProgrammeCounts.first())
    }
}
