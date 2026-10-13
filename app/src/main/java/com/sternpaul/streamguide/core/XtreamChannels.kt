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
 * Builds the channel list from the Xtream live-stream API instead of the full playlist, which
 * also contains every movie and episode. Saved channels are recognised by the numeric stream
 * id at the end of their URL and keep their id and URL, so favourites, ordering, hidden state
 * and custom names survive the switch.
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

    fun build(account: XtreamAccount, streams: List<XtreamStream>, categories: Map<String, String>, previous: List<Channel>): List<Channel> {
        val previousByKey = HashMap<String, Channel>()
        previous.forEach { channel -> streamKey(channel.url)?.let { if (it !in previousByKey) previousByKey[it] = channel } }
        val (prefix, suffix) = urlPattern(account, previous)
        val usedIds = HashSet<String>()
        val seenStreams = HashSet<String>()
        // Saved ids are reserved first so a new channel can never take one over.
        streams.forEach { stream -> previousByKey[stream.streamId]?.let { usedIds += it.id } }
        val channels = ArrayList<Channel>(streams.size)
        for (stream in streams) {
            val name = stream.name.trim()
            if (stream.streamId.isBlank() || name.isBlank() || !seenStreams.add(stream.streamId)) continue
            val saved = previousByKey[stream.streamId]
            val id = saved?.id ?: newId(stream, usedIds)
            channels += Channel(
                id = id,
                name = name,
                url = saved?.url ?: "$prefix${stream.streamId}$suffix",
                group = categories[stream.categoryId].orEmpty().trim().ifBlank { "Other" },
                tvgId = stream.epgChannelId.trim(),
                logoUrl = stream.icon.trim(),
                providerOrder = channels.size,
                catchupSource = saved?.catchupSource.orEmpty(),
                catchupDays = saved?.catchupDays?.takeIf { it > 0 } ?: stream.archiveDays
            )
        }
        return channels
    }

    /**
     * For the playlist fallback: a channel first imported through the API has an id the
     * playlist parser would not produce. Recognise it by stream id and keep the saved id.
     */
    fun carryIds(previous: List<Channel>, parsed: List<Channel>): List<Channel> {
        val previousIds = previous.mapTo(HashSet()) { it.id }
        val previousByKey = HashMap<String, Channel>()
        previous.forEach { channel -> streamKey(channel.url)?.let { if (it !in previousByKey) previousByKey[it] = channel } }
        val taken = parsed.mapNotNullTo(HashSet()) { it.id.takeIf(previousIds::contains) }
        return parsed.map { channel ->
            if (channel.id in previousIds) return@map channel
            val saved = streamKey(channel.url)?.let(previousByKey::get) ?: return@map channel
            if (taken.add(saved.id)) channel.copy(id = saved.id) else channel
        }
    }

    private fun newId(stream: XtreamStream, usedIds: MutableSet<String>): String {
        val epg = stream.epgChannelId.trim()
        val candidates = if (epg.isBlank()) listOf("xt-${stream.streamId}") else listOf(epg, "$epg#xt-${stream.streamId}")
        return candidates.firstOrNull(usedIds::add) ?: "xt-${stream.streamId}-${usedIds.size}".also { usedIds += it }
    }

    /** New channels use the URL shape this provider's saved channels already play with. */
    private fun urlPattern(account: XtreamAccount, previous: List<Channel>): Pair<String, String> {
        val default = "${account.server}/live/${account.username}/${account.password}/" to ".ts"
        val credentials = "/${account.username}/${account.password}/"
        return previous.asSequence()
            .filter { streamKey(it.url) != null && it.url.substringBefore('?').substringBeforeLast('/').plus("/").endsWith(credentials) }
            .map { channel ->
                val path = channel.url.substringBefore('?')
                val last = path.substringAfterLast('/')
                path.substringBeforeLast('/') + "/" to (if ('.' in last) "." + last.substringAfter('.') else "")
            }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: default
    }
}
