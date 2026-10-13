package com.sternpaul.streamguide.core

import java.io.InputStream
import java.security.MessageDigest

object M3uParser {
    private val attribute = Regex("([A-Za-z0-9_-]+)=\\\"([^\\\"]*)\\\"")

    /** What happened to the playlist's entries, for the import log. */
    class Stats { var entries = 0; var onDemand = 0; var invalid = 0; var duplicates = 0 }

    fun parse(text: String): List<Channel> = text.byteInputStream().use(::parse)

    /** Parses incrementally so provider playlists can be much larger than device memory. */
    fun parse(input: InputStream): List<Channel> = parse(input, Stats())

    fun parse(input: InputStream, stats: Stats): List<Channel> {
        val channels = mutableListOf<Channel>()
        val seenIds = mutableSetOf<String>()
        val seenStreams = mutableSetOf<String>()
        var pendingInfo: String? = null
        var pendingGroup: String? = null

        input.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> { pendingInfo = line; pendingGroup = null }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                    line.isBlank() || line.startsWith("#") -> Unit
                    pendingInfo != null -> {
                        val info = pendingInfo!!
                        val extGroup = pendingGroup
                        pendingInfo = null
                        pendingGroup = null
                        stats.entries++
                        if (!isStreamUrl(line)) { stats.invalid++; return@forEach }
                        if (isOnDemandUrl(line)) { stats.onDemand++; return@forEach }

                        val attrs = attribute.findAll(info).associate {
                            it.groupValues[1].lowercase() to it.groupValues[2]
                        }
                        // Attribute values may contain commas; the name starts after the last attribute.
                        val name = attribute.replace(info, "").substringAfter(',', "").trim()
                        if (name.isBlank()) { stats.invalid++; return@forEach }

                        val tvgId = attrs["tvg-id"].orEmpty()
                        // Providers reuse one tvg-id for the SD/HD/backup variants of a channel. The
                        // first keeps the plain id (so saved favourites survive); the others get
                        // their own id instead of being dropped. Only the same stream twice is skipped.
                        if (!seenStreams.add("$tvgId\n$line")) { stats.duplicates++; return@forEach }
                        var id = tvgId.ifBlank { stableId(line) }
                        if (!seenIds.add(id)) {
                            id = "$tvgId#${stableId(line)}"
                            if (tvgId.isBlank() || !seenIds.add(id)) { stats.duplicates++; return@forEach }
                        }

                        channels += Channel(
                            id = id,
                            name = name,
                            url = line,
                            group = attrs["group-title"].orEmpty().trim().ifBlank { extGroup.orEmpty() }.ifBlank { "Other" },
                            tvgId = tvgId,
                            logoUrl = attrs["tvg-logo"].orEmpty(),
                            providerOrder = channels.size,
                            catchupSource = attrs["catchup-source"].orEmpty(),
                            catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"]).orEmpty().toIntOrNull() ?: 0
                        )
                    }
                }
            }
        }
        return channels
    }

    private fun isStreamUrl(value: String) =
        value.startsWith("http://", true) || value.startsWith("https://", true) || value.startsWith("rtsp://", true)

    private fun isOnDemandUrl(value: String): Boolean {
        val path = runCatching { java.net.URI(value).path.orEmpty() }.getOrDefault(value)
        return path.contains("/movie/", ignoreCase = true) || path.contains("/series/", ignoreCase = true)
    }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(10)
        .joinToString("") { "%02x".format(it) }
}
