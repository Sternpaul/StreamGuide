package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.core.*
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlaylistRepository(private val store: AppStore) {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).followRedirects(true).build()
    private val refreshMutex = Mutex()

    suspend fun refreshAll(onProgress: suspend (String) -> Unit = {}): RefreshStatus = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
        val refreshStartedNs = System.nanoTime()
        val provider = store.getProvider() ?: error("Add a playlist first")
        try {
            onProgress("Connecting to ${provider.name}")
            val saved = store.getChannels()
            val source = PlaylistSource.of(provider)
            // Installs from before sources were recorded only ever had this one playlist.
            val savedSource = PlaylistSource.decode(store.channelSource()) ?: source
            val newPlaylist = saved.isNotEmpty() && !savedSource.sameAs(source)
            if (newPlaylist) onProgress("Different playlist detected · previous channels are archived")
            val loaded = loadChannels(provider, if (newPlaylist) emptyList() else saved, onProgress)
            require(loaded.channels.isNotEmpty()) { "The provider returned no valid live channels. Check the server address and credentials." }
            val merged = ChannelImport.merge(saved, savedSource, store.archivedChannels(), source, loaded.channels, loaded.options)
            val reconciled = merged.channels
            val report = ImportReport(
                timeEpochMs = System.currentTimeMillis(), source = loaded.label, received = loaded.received,
                channels = reconciled.size, categories = reconciled.mapTo(HashSet()) { it.group }.size,
                kept = merged.kept, added = merged.added, restored = merged.restored, missing = merged.missing,
                skippedOnDemand = loaded.onDemand, skippedInvalid = loaded.invalid, skippedDuplicate = loaded.duplicates,
                newPlaylist = newPlaylist, note = loaded.note, durationMs = elapsedMs(refreshStartedNs)
            )
            onProgress("Received ${report.received} entries · ${report.channels} live channels in ${report.categories} categories")
            onProgress("${report.kept} known · ${report.added} new · ${report.restored} restored · ${report.missing} no longer provided")
            onProgress("Downloading TV guide")
            var epgWarning: String? = null
            val epgStartedNs = System.nanoTime()
            val epgCount = runCatching {
                val refreshedCount = refreshEpgInternal(provider)
                if (refreshedCount != null) {
                    store.recordEpgRefresh(System.currentTimeMillis(), elapsedMs(epgStartedNs))
                    refreshedCount
                } else store.programCount()
            }.getOrElse { error ->
                epgWarning = "TV guide update failed: ${error.message?.take(140) ?: "unknown error"}. Previous guide kept."
                store.appendDiagnosticError("EPG update", error.message ?: error.javaClass.simpleName)
                onProgress(epgWarning!!)
                store.programCount()
            }
            onProgress("Found $epgCount guide programmes")
            onProgress("Saving channels and guide")
            // Archive first: if the app dies between the two writes, nothing is forgotten.
            store.saveArchivedChannels(merged.archive)
            store.saveChannels(reconciled)
            store.setChannelSource(source.encode(), XtreamChannels.account(provider)?.server.orEmpty())
            store.appendImportReport(report)
            store.setLastRefresh(System.currentTimeMillis())
            store.setLastFullRefreshDurationMs(elapsedMs(refreshStartedNs))
            store.setLastError(epgWarning.orEmpty())
            RefreshStatus(
                false,
                store.lastRefresh(),
                epgWarning ?: "Updated successfully",
                reconciled.size,
                epgCount
            )
        } catch (e: Exception) {
            store.setLastFullRefreshDurationMs(elapsedMs(refreshStartedNs))
            val message = e.message?.take(180) ?: "Refresh failed"
            store.setLastError(message)
            store.appendDiagnosticError("Playlist update", message)
            throw IllegalStateException(message, e)
        }
        }
    }

    suspend fun refreshEpg(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
        if (RefreshPolicy.canReuseRecentEpg(force, store.lastEpgRefresh(), System.currentTimeMillis())) {
            return@withLock store.programCount()
        }
        val provider = store.getProvider() ?: error("Add a playlist first")
        val startedNs = System.nanoTime()
        try {
            val count = refreshEpgInternal(provider) ?: error("No EPG URL is configured for this playlist")
            val now = System.currentTimeMillis()
            store.recordEpgRefresh(now, elapsedMs(startedNs))
            store.setLastError("")
            count
        } catch (error: Exception) {
            val message = error.message?.take(180) ?: "TV guide update failed"
            store.setLastError(message)
            store.appendDiagnosticError("EPG update", message)
            throw IllegalStateException(message, error)
        }
        }
    }

    private fun refreshEpgInternal(provider: ProviderConfig): Int? {
        val url = ProviderEndpoints.epg(provider).ifBlank { return null }
        val response = client.newCall(request(provider, url)).execute()
        response.use {
            if (!it.isSuccessful) error("EPG HTTP ${it.code}")
            val body = it.body ?: error("Empty EPG response")
            return store.savePrograms { emit ->
                EpgInput.open(body.byteStream(), url, it.header("Content-Encoding").orEmpty()).use { input ->
                    XmlTvParser.parse(input, emit)
                }
            }
        }
    }

    private class LoadedChannels(
        val channels: List<Channel>, val options: ChannelImport.Options, val label: String,
        val received: Int, val onDemand: Int, val invalid: Int, val duplicates: Int, val note: String = ""
    )

    /**
     * Xtream providers are asked for their live channels only. The full playlist also lists
     * every movie and episode and is the slow, fragile path; it stays as the fallback.
     * [sameSourceSaved] is empty when the playlist itself was replaced.
     */
    private suspend fun loadChannels(provider: ProviderConfig, sameSourceSaved: List<Channel>, onProgress: suspend (String) -> Unit): LoadedChannels {
        val account = XtreamChannels.account(provider)
        var note = ""
        if (account != null) {
            val previousServer = store.channelServer()
            runCatching {
                onProgress("Loading live channels")
                val categories = xtreamCategories(provider, account)
                val streams = xtreamStreams(provider, account)
                val loaded = XtreamChannels.load(account, streams, categories, sameSourceSaved, previousServer)
                // A much shorter list than before means the API is incomplete, not that channels vanished.
                check(loaded.channels.isNotEmpty() && loaded.channels.size * 2 >= sameSourceSaved.size) { "live list has ${loaded.channels.size} channels, ${sameSourceSaved.size} saved" }
                LoadedChannels(
                    loaded.channels,
                    ChannelImport.Options(matchById = false, useStreamKeys = true) { XtreamChannels.savedUrlStillValid(account, previousServer, it.url) },
                    "Live channel API", streams.size, 0, loaded.invalid, loaded.duplicates
                )
            }.onSuccess { return it }.onFailure { error ->
                note = "Live channel API failed: ${DiagnosticMessageSanitizer.sanitize(error.message ?: error.javaClass.simpleName).take(120)}"
                store.appendDiagnosticError("Live channel list", "$note · using the full playlist instead")
                onProgress("Live channel list unavailable · downloading full playlist")
            }
        } else onProgress("Streaming and parsing live channel list")
        val stats = M3uParser.Stats()
        val parsed = downloadPlaylist(provider, stats, onProgress)
        return LoadedChannels(
            parsed, ChannelImport.Options(matchById = true, useStreamKeys = account != null),
            "Full playlist", stats.entries, stats.onDemand, stats.invalid, stats.duplicates, note
        )
    }

    private fun xtreamCategories(provider: ProviderConfig, account: XtreamAccount): Map<String, String> {
        val categories = HashMap<String, String>()
        xtreamArray(provider, ProviderEndpoints.xtreamApi(account, "get_live_categories")) { fields ->
            val id = fields["category_id"].orEmpty()
            if (id.isNotBlank()) categories[id] = fields["category_name"].orEmpty()
        }
        return categories
    }

    private fun xtreamStreams(provider: ProviderConfig, account: XtreamAccount): List<XtreamStream> {
        val streams = ArrayList<XtreamStream>()
        xtreamArray(provider, ProviderEndpoints.xtreamApi(account, "get_live_streams")) { fields ->
            streams += XtreamStream(
                streamId = fields["stream_id"].orEmpty(),
                name = fields["name"].orEmpty(),
                categoryId = fields["category_id"].orEmpty(),
                epgChannelId = fields["epg_channel_id"].orEmpty(),
                icon = fields["stream_icon"].orEmpty(),
                archiveDays = if (fields["tv_archive"] == "1") fields["tv_archive_duration"]?.toIntOrNull() ?: 0 else 0
            )
        }
        return streams
    }

    /** Streams a JSON array of flat objects; values arrive as text whatever their JSON type. */
    private fun xtreamArray(provider: ProviderConfig, url: String, onObject: (Map<String, String>) -> Unit) {
        client.newCall(request(provider, url)).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty response")
            android.util.JsonReader(body.charStream()).use { reader ->
                reader.isLenient = true
                if (reader.peek() != android.util.JsonToken.BEGIN_ARRAY) error("unexpected response (login rejected?)")
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                    val fields = HashMap<String, String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        when (reader.peek()) {
                            android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> fields[name] = reader.nextString()
                            android.util.JsonToken.BOOLEAN -> fields[name] = if (reader.nextBoolean()) "1" else "0"
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    onObject(fields)
                }
                reader.endArray()
            }
        }
    }

    /**
     * Large providers build the playlist on request and can stay silent for minutes, or drop
     * the first attempt. Wait long, try twice, and say which step ran out of time.
     */
    private suspend fun downloadPlaylist(provider: ProviderConfig, stats: M3uParser.Stats, onProgress: suspend (String) -> Unit): List<Channel> {
        val url = ProviderEndpoints.playlist(provider)
        var lastError: java.io.IOException? = null
        repeat(PLAYLIST_ATTEMPTS) { attempt ->
            val startedNs = System.nanoTime()
            try {
                stats.entries = 0; stats.onDemand = 0; stats.invalid = 0; stats.duplicates = 0
                return download(provider, url).use { M3uParser.parse(it, stats) }
            } catch (error: java.io.IOException) {
                lastError = error
                val seconds = elapsedMs(startedNs) / 1_000
                val reason = if (error is java.net.SocketTimeoutException) "the provider sent no data for ${seconds}s" else (error.message ?: error.javaClass.simpleName)
                store.appendDiagnosticError("Playlist download", "Attempt ${attempt + 1}/$PLAYLIST_ATTEMPTS: $reason")
                if (attempt + 1 < PLAYLIST_ATTEMPTS) onProgress("Playlist download failed ($reason) · retrying")
            }
        }
        val error = lastError
        error(if (error is java.net.SocketTimeoutException) "The provider did not deliver the playlist in time ($PLAYLIST_ATTEMPTS attempts). Your channels are unchanged."
            else "Playlist download failed: ${error?.message ?: "unknown error"}")
    }

    private fun download(provider: ProviderConfig, url: String): InputStream {
        if (url.startsWith("content://")) return store.openContentUri(url)
        val response = client.newCall(request(provider, url)).execute()
        if (!response.isSuccessful) { response.close(); error("Playlist HTTP ${response.code}") }
        return response.body?.byteStream() ?: error("Empty playlist response")
    }

    private companion object { const val PLAYLIST_ATTEMPTS = 2 }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000L

    private fun request(provider: ProviderConfig, url: String): Request {
        val builder = Request.Builder().url(url)
        ProviderHeaders.forProvider(provider).forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}
