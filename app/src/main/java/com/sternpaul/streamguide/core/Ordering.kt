package com.sternpaul.streamguide.core

object ChannelOrdering {
    val manual = compareBy<Channel> { it.manualRank ?: Long.MAX_VALUE }.thenBy { it.providerOrder }.thenBy { it.name.lowercase() }
    val alphabetical = compareBy<Channel> { it.name.lowercase() }
    val provider = compareBy<Channel> { it.providerOrder }
}

object ChannelReconciler {
    fun reconcile(old: List<Channel>, refreshed: List<Channel>): List<Channel> {
        val previous = old.associateBy { it.id }
        var nextRank = (old.mapNotNull { it.manualRank }.maxOrNull() ?: 0L) + 1000L
        return refreshed.map { incoming ->
            val saved = previous[incoming.id]
            if (saved != null) incoming.copy(
                manualRank = saved.manualRank,
                favorite = saved.favorite,
                hidden = saved.hidden,
                locked = saved.locked
            ) else incoming.copy(manualRank = nextRank.also { nextRank += 1000L })
        }
    }

    fun move(channels: List<Channel>, channelId: String, delta: Int): List<Channel> {
        val ordered = channels.sortedWith(ChannelOrdering.manual).toMutableList()
        val from = ordered.indexOfFirst { it.id == channelId }
        if (from < 0) return channels
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (from == to) return ordered
        val item = ordered.removeAt(from)
        ordered.add(to, item)
        return ordered.mapIndexed { index, channel -> channel.copy(manualRank = (index + 1) * 1000L) }
    }
}

object AppSettings {
    const val DEFAULT_EPG_HOURS = 24
    val allowedEpgHours = setOf(4, 6, 12, 24, 48)
}
