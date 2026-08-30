package com.sternpaul.streamguide.core

import java.security.MessageDigest

object M3uParser {
    private val attribute = Regex("([A-Za-z0-9_-]+)=\\\"([^\\\"]*)\\\"")

    fun parse(text: String): List<Channel> {
        val lines = text.lineSequence().map(String::trim).toList()
        val channels = mutableListOf<Channel>()
        var i = 0
        while (i < lines.size) {
            val info = lines[i]
            if (info.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = attribute.findAll(info).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                val name = info.substringAfter(',', "").trim()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (name.isNotBlank() && j < lines.size && isStreamUrl(lines[j])) {
                    val url = lines[j]
                    val tvgId = attrs["tvg-id"].orEmpty()
                    val id = tvgId.ifBlank { stableId(url) }
                    channels += Channel(
                        id = id,
                        name = name,
                        url = url,
                        group = attrs["group-title"].orEmpty().ifBlank { "Other" },
                        tvgId = tvgId,
                        logoUrl = attrs["tvg-logo"].orEmpty(),
                        providerOrder = channels.size
                    )
                    i = j
                }
            }
            i++
        }
        return channels.distinctBy { it.id }
    }

    private fun isStreamUrl(value: String) = value.startsWith("http://", true) || value.startsWith("https://", true) || value.startsWith("rtsp://", true)
    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(10).joinToString("") { "%02x".format(it) }
}
