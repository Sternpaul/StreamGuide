package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.BuildConfig
import com.sternpaul.streamguide.core.ProxySettings
import okhttp3.Request
import org.json.JSONObject

/** What the internet sees when StreamGuide connects through a proxy. */
data class PublicAddress(val ip: String, val location: String, val network: String)

object ProxyCheck {
    /** Contacted only when the user presses Test connection. */
    const val SERVICE = "ipinfo.io"
    private const val URL = "https://ipinfo.io/json"

    /** One request through [settings]; throws a [com.sternpaul.streamguide.core.ProxyException] for proxy faults. */
    fun publicAddress(http: HttpClients, settings: ProxySettings): PublicAddress = http.withTestClient(settings) { client ->
        val request = Request.Builder().url(URL).header("Accept", "application/json")
            .header("User-Agent", "StreamGuide/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "$SERVICE answered HTTP ${response.code}" }
            val json = JSONObject(response.body?.string() ?: error("Empty answer from $SERVICE"))
            val ip = json.optString("ip").ifBlank { error("$SERVICE sent no IP address") }
            val location = listOf("city", "region", "country").map { json.optString(it) }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            PublicAddress(ip, location, json.optString("org"))
        }
    }
}
