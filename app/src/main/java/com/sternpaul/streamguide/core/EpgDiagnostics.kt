package com.sternpaul.streamguide.core

data class DiagnosticIdCount(val id: String, val count: Int)

data class EpgDiagnostics(
    val totalChannels: Int = 0,
    val channelsWithEpg: Int = 0,
    val totalPrograms: Int = 0,
    val matchedPrograms: Int = 0,
    val totalEpgChannelIds: Int = 0,
    val matchedEpgChannelIds: Int = 0,
    val currentlyAiringPrograms: Int = 0,
    val upcomingPrograms24h: Int = 0,
    val guideStartEpochMs: Long = 0,
    val guideEndEpochMs: Long = 0,
    val channelsWithoutTvgId: Int = 0,
    val duplicateTvgIds: Int = 0,
    val unmatchedEpgIds: List<DiagnosticIdCount> = emptyList(),
    val channelsWithoutEpg: List<String> = emptyList(),
    val lastEpgSuccessEpochMs: Long = 0,
    val lastEpgDurationMs: Long = 0,
    val lastFullRefreshDurationMs: Long = 0,
    val lastError: String = "",
    val generatedAtEpochMs: Long = 0
) {
    val channelCoveragePercent: Int get() = percent(channelsWithEpg, totalChannels)
    val programMatchPercent: Int get() = percent(matchedPrograms, totalPrograms)
    val epgIdMatchPercent: Int get() = percent(matchedEpgChannelIds, totalEpgChannelIds)

    private fun percent(numerator: Int, denominator: Int): Int =
        if (denominator <= 0) 0 else ((numerator.toLong() * 100L) / denominator).toInt()
}
