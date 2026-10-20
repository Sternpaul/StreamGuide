package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.BuildConfig
import com.sternpaul.streamguide.core.ProxyErrors
import com.sternpaul.streamguide.core.ProxySettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** What the internet sees when StreamGuide connects through a proxy. */
data class PublicAddress(val ip: String, val location: String, val network: String)

object ProxyCheck {
    /** Contacted only when the user presses Test connection. */
    const val SERVICE = "ipinfo.io"
    /** Asked only when [SERVICE] refuses, which it does for some VPN addresses; it reports no location. */
    const val FALLBACK_SERVICE = "api.ipify.org"

    /** Requests through [settings]; throws a [com.sternpaul.streamguide.core.ProxyException] for proxy faults. */
    fun publicAddress(http: HttpClients, settings: ProxySettings): PublicAddress = http.withTestClient(settings) { client ->
        try {
            ipinfo(client)
        } catch (error: Exception) {
            if (ProxyErrors.find(error) != null) throw error
            val reason = error.message?.take(60) ?: error.javaClass.simpleName
            runCatching { ipify(client) }.getOrElse { fallbackError ->
                if (ProxyErrors.find(fallbackError) != null) throw fallbackError
                error("$reason; $FALLBACK_SERVICE: ${fallbackError.message?.take(60) ?: fallbackError.javaClass.simpleName}")
            }.copy(location = "location unavailable ($SERVICE: $reason)")
        }
    }

    private fun ipinfo(client: OkHttpClient): PublicAddress {
        val json = getJson(client, "https://ipinfo.io/json", SERVICE)
        val ip = json.optString("ip").ifBlank { error("$SERVICE sent no IP address") }
        val location = listOf("city", "region", "country").map { json.optString(it) }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        return PublicAddress(ip, location, json.optString("org"))
    }

    private fun ipify(client: OkHttpClient): PublicAddress {
        val ip = getJson(client, "https://api.ipify.org?format=json", FALLBACK_SERVICE).optString("ip").ifBlank { error("$FALLBACK_SERVICE sent no IP address") }
        return PublicAddress(ip, "", "")
    }

    private fun getJson(client: OkHttpClient, url: String, service: String): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "StreamGuide/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "$service answered HTTP ${response.code}" }
            return JSONObject(response.body?.string() ?: error("Empty answer from $service"))
        }
    }
}
