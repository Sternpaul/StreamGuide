package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.core.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
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
            val epg = runCatching { refreshEpgInternal(provider) }.getOrElse { error -> onProgress("Guide unavailable: ${error.message}. Keeping the previous guide."); store.getPrograms() }
            onProgress("Found ${epg.size} guide programmes")
            onProgress("Saving channels and guide")
            store.saveChannels(reconciled)
            store.savePrograms(epg)
            store.setLastRefresh(System.currentTimeMillis()); store.setLastError("")
            RefreshStatus(false, store.lastRefresh(), "Updated successfully", reconciled.size, epg.size)
        } catch (e: Exception) {
            val message = e.message?.take(180) ?: "Refresh failed"
            store.setLastError(message)
            throw IllegalStateException(message, e)
        }
    }

    suspend fun refreshEpg(): Int = withContext(Dispatchers.IO) {
        val provider = store.getProvider() ?: return@withContext 0
        val programs = refreshEpgInternal(provider)
        store.savePrograms(programs); store.setLastRefresh(System.currentTimeMillis()); programs.size
    }

    private fun refreshEpgInternal(provider: ProviderConfig): List<Program> {
        val url = ProviderEndpoints.epg(provider).ifBlank { return store.getPrograms() }
        val response = client.newCall(request(provider, url)).execute()
        response.use {
            if (!it.isSuccessful) error("EPG HTTP ${it.code}")
            val body = it.body ?: error("Empty EPG response")
            val raw = body.bytes()
            require(raw.size <= 80 * 1024 * 1024) { "EPG download is too large" }
            val stream: InputStream = if (url.endsWith(".gz", true) || it.header("Content-Encoding", "").orEmpty().contains("gzip", true) || raw.take(2) == listOf(0x1f.toByte(),0x8b.toByte())) GZIPInputStream(ByteArrayInputStream(raw)) else ByteArrayInputStream(raw)
            val parsed = stream.use(XmlTvParser::parse)
            require(parsed.isNotEmpty()) { "EPG contained no valid programmes" }
            return parsed
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
