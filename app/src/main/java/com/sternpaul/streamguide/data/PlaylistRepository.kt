package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.core.*
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlaylistRepository(private val store: AppStore) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).followRedirects(true).build()

    suspend fun refreshAll(onProgress: suspend (String) -> Unit = {}): RefreshStatus = withContext(Dispatchers.IO) {
        val provider = store.getProvider() ?: error("Add a playlist first")
        try {
            onProgress("Connecting to ${provider.name}")
            onProgress("Streaming and parsing live channel list")
            val parsed = download(provider, ProviderEndpoints.playlist(provider)).use(M3uParser::parse)
            require(parsed.isNotEmpty()) { "The provider returned no valid live channels. Check the server address and credentials." }
            onProgress("Found ${parsed.size} live channels")
            val reconciled = ChannelReconciler.reconcile(store.getChannels(), parsed)
            onProgress("Downloading TV guide")
            var epgWarning: String? = null
            val epgCount = runCatching {
                refreshEpgInternal(provider) ?: store.programCount()
            }.getOrElse { error ->
                epgWarning = "TV guide update failed: ${error.message?.take(140) ?: "unknown error"}. Previous guide kept."
                onProgress(epgWarning!!)
                store.programCount()
            }
            onProgress("Found $epgCount guide programmes")
            onProgress("Saving channels and guide")
            store.saveChannels(reconciled)
            store.setLastRefresh(System.currentTimeMillis())
            store.setLastError(epgWarning.orEmpty())
            RefreshStatus(
                false,
                store.lastRefresh(),
                epgWarning ?: "Updated successfully",
                reconciled.size,
                epgCount
            )
        } catch (e: Exception) {
            val message = e.message?.take(180) ?: "Refresh failed"
            store.setLastError(message)
            throw IllegalStateException(message, e)
        }
    }

    suspend fun refreshEpg(): Int = withContext(Dispatchers.IO) {
        val provider = store.getProvider() ?: return@withContext 0
        val count = refreshEpgInternal(provider) ?: return@withContext store.programCount()
        store.setLastRefresh(System.currentTimeMillis())
        count
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

    private fun download(provider: ProviderConfig, url: String): InputStream {
        if (url.startsWith("content://")) return store.openContentUri(url)
        val response = client.newCall(request(provider, url)).execute()
        if (!response.isSuccessful) { response.close(); error("Playlist HTTP ${response.code}") }
        return response.body?.byteStream() ?: error("Empty playlist response")
    }

    private fun request(provider: ProviderConfig, url: String): Request {
        val builder = Request.Builder().url(url)
        ProviderHeaders.forProvider(provider).forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}
