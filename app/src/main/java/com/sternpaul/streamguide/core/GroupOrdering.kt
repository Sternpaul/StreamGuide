package com.sternpaul.streamguide.core

object GroupOrdering {
    fun apply(discovered: List<String>, preferred: List<String>): List<String> {
        val available = discovered.distinct()
        val preferredAvailable = preferred.filter { it in available }.distinct()
        return preferredAvailable + available.filterNot { it in preferredAvailable }
    }

    fun move(current: List<String>, group: String, delta: Int): List<String> {
        val ordered = current.distinct().toMutableList()
        val from = ordered.indexOf(group)
        if (from < 0) return ordered
        val to = (from + delta).coerceIn(0, ordered.lastIndex)
        if (from != to) ordered.add(to, ordered.removeAt(from))
        return ordered
    }

    fun moveToTop(current: List<String>, group: String): List<String> {
        if (group !in current) return current.distinct()
        return listOf(group) + current.filterNot { it == group }.distinct()
    }
}
