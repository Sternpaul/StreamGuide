package com.sternpaul.streamguide.core

import java.io.InputStream
import java.security.MessageDigest

object M3uParser {
    private val attribute = Regex("([A-Za-z0-9_-]+)=\\\"([^\\\"]*)\\\"")

    fun parse(text: String): List<Channel> = text.byteInputStream().use(::parse)

    /** Parses incrementally so provider playlists can be much larger than device memory. */
    fun parse(input: InputStream): List<Channel> {
        val channels = mutableListOf<Channel>()
        val seenIds = mutableSetOf<String>()
        var pendingInfo: String? = null

        input.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> pendingInfo = line
                    line.isBlank() || line.startsWith("#") -> Unit
                    pendingInfo != null -> {
                        val info = pendingInfo!!
                        pendingInfo = null
                        if (!isStreamUrl(line) || isOnDemandUrl(line)) return@forEach

                        val attrs = attribute.findAll(info).associate {
                            it.groupValues[1].lowercase() to it.groupValues[2]
                        }
                        val name = info.substringAfter(',', "").trim()
                        if (name.isBlank()) return@forEach

                        val tvgId = attrs["tvg-id"].orEmpty()
                        val id = tvgId.ifBlank { stableId(line) }
                        if (!seenIds.add(id)) return@forEach

                        channels += Channel(
                            id = id,
                            name = name,
                            url = line,
                            group = attrs["group-title"].orEmpty().ifBlank { "Other" },
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
