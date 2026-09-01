package com.sternpaul.streamguide.core

data class EpgDiagnosticInput(
    val countsByChannelId: Map<String, Int>,
    val totalPrograms: Int,
    val currentlyAiringPrograms: Int,
    val upcomingPrograms24h: Int,
    val guideStartEpochMs: Long,
    val guideEndEpochMs: Long,
    val lastEpgSuccessEpochMs: Long = 0,
    val lastEpgDurationMs: Long = 0,
    val lastFullRefreshDurationMs: Long = 0,
    val lastError: String = ""
)

object EpgDiagnosticsCalculator {
    fun calculate(channels: List<Channel>, input: EpgDiagnosticInput, nowEpochMs: Long): EpgDiagnostics {
        val visibleChannels = channels.filterNot(Channel::hidden)
        val allKnownIds = channels.asSequence()
            .flatMap { sequenceOf(it.id, it.tvgId) }
            .filter(String::isNotBlank)
            .toSet()
        val matchedIds = input.countsByChannelId.keys.intersect(allKnownIds)
        val channelsWithEpg = visibleChannels.count { channel ->
            sequenceOf(channel.id, channel.tvgId).filter(String::isNotBlank).any(input.countsByChannelId::containsKey)
        }
        val duplicateTvgIds = visibleChannels.map(Channel::tvgId).filter(String::isNotBlank)
            .groupingBy { it }.eachCount().count { it.value > 1 }
        return EpgDiagnostics(
            totalChannels = visibleChannels.size,
            channelsWithEpg = channelsWithEpg,
            totalPrograms = input.totalPrograms,
            matchedPrograms = matchedIds.sumOf { input.countsByChannelId[it] ?: 0 },
            totalEpgChannelIds = input.countsByChannelId.size,
            matchedEpgChannelIds = matchedIds.size,
            currentlyAiringPrograms = input.currentlyAiringPrograms,
            upcomingPrograms24h = input.upcomingPrograms24h,
            guideStartEpochMs = input.guideStartEpochMs,
            guideEndEpochMs = input.guideEndEpochMs,
            channelsWithoutTvgId = visibleChannels.count { it.tvgId.isBlank() },
            duplicateTvgIds = duplicateTvgIds,
            unmatchedEpgIds = input.countsByChannelId.asSequence()
                .filter { it.key !in allKnownIds }
                .sortedByDescending { it.value }
                .take(10)
                .map { DiagnosticIdCount(it.key, it.value) }
                .toList(),
            channelsWithoutEpg = visibleChannels.asSequence()
                .filter { channel -> sequenceOf(channel.id, channel.tvgId).filter(String::isNotBlank).none(input.countsByChannelId::containsKey) }
                .map(Channel::displayName)
                .take(10)
                .toList(),
            lastEpgSuccessEpochMs = input.lastEpgSuccessEpochMs,
            lastEpgDurationMs = input.lastEpgDurationMs,
            lastFullRefreshDurationMs = input.lastFullRefreshDurationMs,
            lastError = input.lastError,
            generatedAtEpochMs = nowEpochMs
        )
    }
}
