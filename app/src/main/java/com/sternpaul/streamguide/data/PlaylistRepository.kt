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
            onProgress("Streaming and parsing live channel list")
            val parsed = downloadPlaylist(provider, onProgress)
            require(parsed.isNotEmpty()) { "The provider returned no valid live channels. Check the server address and credentials." }
            onProgress("Found ${parsed.size} live channels")
            val reconciled = ChannelReconciler.reconcile(store.getChannels(), parsed)
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
            store.saveChannels(reconciled)
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

    /**
     * Large providers build the playlist on request and can stay silent for minutes, or drop
     * the first attempt. Wait long, try twice, and say which step ran out of time.
     */
    private suspend fun downloadPlaylist(provider: ProviderConfig, onProgress: suspend (String) -> Unit): List<Channel> {
        val url = ProviderEndpoints.playlist(provider)
        var lastError: java.io.IOException? = null
        repeat(PLAYLIST_ATTEMPTS) { attempt ->
            val startedNs = System.nanoTime()
            try {
                return download(provider, url).use(M3uParser::parse)
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
