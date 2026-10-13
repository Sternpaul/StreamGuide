package com.sternpaul.streamguide.core

import java.net.URI
import java.net.URLDecoder

data class XtreamAccount(val server: String, val username: String, val password: String)

/** One entry of the provider's live-stream list. */
data class XtreamStream(
    val streamId: String,
    val name: String,
    val categoryId: String = "",
    val epgChannelId: String = "",
    val icon: String = "",
    val archiveDays: Int = 0
)

/**
 * Reads the channel list from the Xtream live-stream API instead of the full playlist, which
 * also contains every movie and episode. Recognising saved channels is [ChannelImport]'s job.
 */
object XtreamChannels {
    /** Xtream logins, and M3U links that are really an Xtream `get.php` playlist. */
    fun account(provider: ProviderConfig): XtreamAccount? {
        if (provider.type == ProviderType.XTREAM) {
            val server = provider.serverUrl.trim().trimEnd('/')
            return if (server.isBlank() || provider.username.isBlank()) null else XtreamAccount(server, provider.username, provider.password)
        }
        val uri = runCatching { URI(provider.playlistUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme == null || uri.host == null || uri.rawPath?.endsWith("/get.php") != true) return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isBlank()) null else key to runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }.toMap()
        val username = query["username"].orEmpty()
        val password = query["password"].orEmpty()
        if (username.isBlank() || password.isBlank()) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return XtreamAccount("${uri.scheme}://${uri.host}$port${uri.rawPath.removeSuffix("/get.php")}", username, password)
    }

    /** The stream id of a live URL such as `http://host/live/user/pass/1234.ts`. */
    fun streamKey(url: String): String? {
        val segment = url.substringBefore('?').substringAfterLast('/').substringBefore('.')
        return segment.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    class Loaded(val channels: List<Channel>, val invalid: Int, val duplicates: Int)

    /**
     * Channels as the provider lists them, with provisional ids. [ChannelImport.merge] decides
     * which of them are saved channels. [sameSourceSaved] only supplies the URL shape.
     */
    fun load(account: XtreamAccount, streams: List<XtreamStream>, categories: Map<String, String>, sameSourceSaved: List<Channel>, previousServer: String? = null): Loaded {
        val (prefix, suffix) = urlPattern(account, sameSourceSaved, previousServer)
        val seenStreams = HashSet<String>()
        var invalid = 0
        var duplicates = 0
        val channels = ArrayList<Channel>(streams.size)
        for (stream in streams) {
            val name = stream.name.trim()
            if (stream.streamId.isBlank() || !stream.streamId.all(Char::isDigit) || name.isBlank()) { invalid++; continue }
            if (!seenStreams.add(stream.streamId)) { duplicates++; continue }
            channels += Channel(
                id = stream.epgChannelId.trim().ifBlank { "xt-${stream.streamId}" },
                name = name,
                url = "$prefix${stream.streamId}$suffix",
                group = categories[stream.categoryId].orEmpty().trim().ifBlank { "Other" },
                tvgId = stream.epgChannelId.trim(),
                logoUrl = stream.icon.trim(),
                providerOrder = channels.size,
                catchupDays = stream.archiveDays
            )
        }
        return Loaded(channels, invalid, duplicates)
    }

    /**
     * A saved stream address is kept only while it still carries the current login and was
     * not issued for a server address that has since been replaced.
     */
    fun savedUrlStillValid(account: XtreamAccount, previousServer: String?, url: String): Boolean {
        if (streamKey(url) == null || !hasCredentials(account, url)) return false
        val replaced = previousServer?.trimEnd('/')?.takeIf { it.isNotBlank() && it != account.server }
        return replaced == null || !url.startsWith("$replaced/")
    }

    private fun hasCredentials(account: XtreamAccount, url: String): Boolean =
        url.substringBefore('?').substringBeforeLast('/').plus("/").endsWith("/${account.username}/${account.password}/")

    /** New channels use the URL shape this provider's saved channels already play with. */
    private fun urlPattern(account: XtreamAccount, previous: List<Channel>, previousServer: String?): Pair<String, String> {
        val default = "${account.server}/live/${account.username}/${account.password}/" to ".ts"
        return previous.asSequence()
            .filter { savedUrlStillValid(account, previousServer, it.url) }
            .map { channel ->
                val path = channel.url.substringBefore('?')
                val last = path.substringAfterLast('/')
                path.substringBeforeLast('/') + "/" to (if ('.' in last) "." + last.substringAfter('.') else "")
            }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: default
    }
}
