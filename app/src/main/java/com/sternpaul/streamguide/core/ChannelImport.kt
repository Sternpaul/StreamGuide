package com.sternpaul.streamguide.core

import java.net.URI
import java.security.MessageDigest

/** Where a set of channels came from; decides whether stream numbers may be compared. */
data class PlaylistSource(val kind: String, val host: String, val user: String = "") {
    fun encode(): String = "$kind|$host|$user"

    /**
     * Providers change their domain, and they renew logins, but rarely both at once. Either
     * one matching means "the same playlist", so neither change costs the saved channels.
     */
    fun sameAs(other: PlaylistSource): Boolean =
        (host.isNotBlank() && host.equals(other.host, ignoreCase = true)) || (user.isNotBlank() && user == other.user)

    companion object {
        fun decode(raw: String?): PlaylistSource? {
            val parts = raw?.split('|') ?: return null
            return if (parts.size < 3 || parts[0].isBlank()) null else PlaylistSource(parts[0], parts[1], parts.drop(2).joinToString("|"))
        }
        fun of(provider: ProviderConfig): PlaylistSource {
            XtreamChannels.account(provider)?.let { return PlaylistSource("xtream", hostOf(it.server), it.username) }
            val url = provider.playlistUrl.trim()
            return if (url.startsWith("content://")) PlaylistSource("file", url) else PlaylistSource("m3u", hostOf(url))
        }
        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: url
    }
}

/** A channel the provider no longer lists, kept so its favourite/order/name return with it. */
data class SavedChannel(val channel: Channel, val source: String)

data class ImportReport(
    val timeEpochMs: Long,
    val source: String,
    val received: Int,
    val channels: Int,
    val categories: Int,
    val kept: Int,
    val added: Int,
    val restored: Int,
    val missing: Int,
    val skippedOnDemand: Int = 0,
    val skippedInvalid: Int = 0,
    val skippedDuplicate: Int = 0,
    val newPlaylist: Boolean = false,
    val note: String = "",
    val durationMs: Long = 0
)

data class ImportResult(
    val channels: List<Channel>,
    val archive: List<SavedChannel>,
    val kept: Int,
    val added: Int,
    val restored: Int,
    val missing: Int
)

/**
 * Turns a freshly loaded channel list into the saved one. Every import, from the live API or
 * a playlist file, goes through here, so the rules are the same on every reload:
 *
 * 1. A loaded channel is recognised as a saved one by id, then by stream number (same
 *    playlist only), then by an unambiguous name + category. It keeps the saved id and with
 *    it favourite, order, hidden, lock and custom name/category.
 * 2. Saved channels the provider did not send are archived, not forgotten, and are restored
 *    by the same rules when they come back.
 * 3. A different playlist is never matched by stream number and never inherits stream
 *    addresses; only id and name + category can carry state across.
 */
object ChannelImport {
    const val ARCHIVE_LIMIT = 10_000

    class Options(
        /** Loaded ids are the stable ones a playlist file produces (tvg-id or URL hash). */
        val matchById: Boolean,
        val useStreamKeys: Boolean,
        /** Whether a saved stream address should be kept instead of the freshly built one. */
        val keepSavedUrl: (saved: Channel) -> Boolean = { false }
    )

    private class Known(val channel: Channel, val sameSource: Boolean, val archived: Boolean, val source: String)

    fun merge(
        saved: List<Channel>, savedSource: PlaylistSource, archive: List<SavedChannel>,
        current: PlaylistSource, incoming: List<Channel>, options: Options
    ): ImportResult {
        val savedSame = savedSource.sameAs(current)
        val known = ArrayList<Known>(saved.size + archive.size)
        saved.forEach { known += Known(it, savedSame, false, savedSource.encode()) }
        archive.forEach { known += Known(it.channel, PlaylistSource.decode(it.source)?.sameAs(current) == true, true, it.source) }

        val byId = HashMap<String, Known>()
        val byKey = HashMap<String, Known>()
        val nameCounts = HashMap<String, Int>()
        val byName = HashMap<String, Known>()
        for (entry in known) {
            if (entry.channel.id !in byId) byId[entry.channel.id] = entry
            if (entry.sameSource) XtreamChannels.streamKey(entry.channel.url)?.let { if (it !in byKey) byKey[it] = entry }
            val name = nameKey(entry.channel)
            nameCounts[name] = (nameCounts[name] ?: 0) + 1
            if (name !in byName) byName[name] = entry
        }
        val incomingNameCounts = HashMap<String, Int>()
        incoming.forEach { val name = nameKey(it); incomingNameCounts[name] = (incomingNameCounts[name] ?: 0) + 1 }

        val matches = arrayOfNulls<Known>(incoming.size)
        val matchedByKey = BooleanArray(incoming.size)
        val claimed = HashSet<String>()
        fun claim(index: Int, entry: Known?): Boolean {
            if (entry == null || matches[index] != null || !claimed.add(entry.channel.id)) return false
            matches[index] = entry
            return true
        }

        if (options.matchById) incoming.forEachIndexed { index, channel -> claim(index, byId[channel.id]) }
        if (options.useStreamKeys) {
            // If the provider renumbered its streams, numbers point at different channels now.
            // Trust them only while most numbered matches also agree on name or guide id.
            var hits = 0
            var agreeing = 0
            incoming.forEachIndexed { index, channel ->
                if (matches[index] != null) return@forEachIndexed
                val candidate = XtreamChannels.streamKey(channel.url)?.let(byKey::get) ?: return@forEachIndexed
                hits++
                if (candidate.channel.name.trim().equals(channel.name.trim(), ignoreCase = true) ||
                    (channel.tvgId.isNotBlank() && channel.tvgId == candidate.channel.tvgId)) agreeing++
            }
            if (hits == 0 || agreeing * 2 >= hits) incoming.forEachIndexed { index, channel ->
                if (matches[index] == null && claim(index, XtreamChannels.streamKey(channel.url)?.let(byKey::get))) matchedByKey[index] = true
            }
        }
        incoming.forEachIndexed { index, channel ->
            val name = nameKey(channel)
            if (matches[index] == null && incomingNameCounts[name] == 1 && nameCounts[name] == 1) claim(index, byName[name])
        }

        val usedIds = HashSet<String>(claimed)
        var nextRank = (known.mapNotNull { it.channel.manualRank }.maxOrNull() ?: 0L) + 1000L
        var kept = 0; var restored = 0; var added = 0
        val firstImport = known.isEmpty()
        val channels = incoming.mapIndexed { index, channel ->
            val match = matches[index]
            if (match != null) {
                if (match.archived) restored++ else kept++
                val old = match.channel
                channel.copy(
                    id = old.id,
                    url = if (matchedByKey[index] && match.sameSource && options.keepSavedUrl(old)) old.url else channel.url,
                    catchupSource = channel.catchupSource.ifBlank { if (match.sameSource) old.catchupSource else "" },
                    catchupDays = if (channel.catchupDays > 0) channel.catchupDays else old.catchupDays,
                    manualRank = old.manualRank, favorite = old.favorite, hidden = old.hidden, locked = old.locked,
                    customName = old.customName, customGroup = old.customGroup
                )
            } else {
                added++
                // A new channel must not take the id of a saved or archived one, or it would
                // silently take over that channel's place.
                val id = uniqueId(channel, usedIds, byId)
                channel.copy(id = id, manualRank = if (firstImport) channel.manualRank else nextRank.also { nextRank += 1000L })
            }
        }

        val resultIds = channels.mapTo(HashSet()) { it.id }
        val nextArchive = LinkedHashMap<String, SavedChannel>()
        archive.forEach { if (it.channel.id !in resultIds) nextArchive[it.channel.id] = it }
        var missing = 0
        saved.forEach { channel ->
            if (channel.id !in resultIds) { missing++; nextArchive.remove(channel.id); nextArchive[channel.id] = SavedChannel(channel, savedSource.encode()) }
        }
        return ImportResult(channels, trimArchive(nextArchive.values.toList()), kept, added, restored, missing)
    }

    fun hasUserState(channel: Channel): Boolean =
        channel.favorite || channel.hidden || channel.locked || channel.customName.isNotBlank() || channel.customGroup.isNotBlank()

    private fun trimArchive(entries: List<SavedChannel>): List<SavedChannel> {
        if (entries.size <= ARCHIVE_LIMIT) return entries
        // Oldest plain entries go first; anything the viewer customised is kept longest.
        val customised = entries.filter { hasUserState(it.channel) }.takeLast(ARCHIVE_LIMIT)
        val keep = customised.toHashSet()
        val plainBudget = ARCHIVE_LIMIT - customised.size
        entries.filter { it !in keep }.takeLast(plainBudget).forEach { keep += it }
        return entries.filter { it in keep }
    }

    private fun nameKey(channel: Channel): String = channel.name.trim().lowercase() + "\u0000" + channel.group.trim().lowercase()

    private fun uniqueId(channel: Channel, usedIds: MutableSet<String>, knownIds: Map<String, *>): String {
        fun free(id: String) = id !in knownIds && usedIds.add(id)
        if (free(channel.id)) return channel.id
        val suffix = XtreamChannels.streamKey(channel.url)?.let { "xt-$it" } ?: hash(channel.url)
        val base = "${channel.id.substringBefore('#')}#$suffix"
        if (free(base)) return base
        var counter = 2
        while (!free("$base-$counter")) counter++
        return "$base-$counter"
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
