package com.sternpaul.streamguide.core

import java.net.URLEncoder

object ProviderEndpoints {
    fun playlist(provider: ProviderConfig): String = when (provider.type) {
        ProviderType.M3U -> provider.playlistUrl.trim()
        ProviderType.XTREAM -> "${provider.serverUrl.trimEnd('/')}/get.php?username=${encode(provider.username)}&password=${encode(provider.password)}&type=m3u_plus&output=ts"
    }

    fun epg(provider: ProviderConfig): String = provider.epgUrl.trim().ifBlank {
        if (provider.type == ProviderType.XTREAM) "${provider.serverUrl.trimEnd('/')}/xmltv.php?username=${encode(provider.username)}&password=${encode(provider.password)}" else ""
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
