package com.sternpaul.streamguide.core

object ProviderHeaders {
    fun forProvider(provider: ProviderConfig): Map<String, String> = buildMap {
        put("User-Agent", provider.userAgent.ifBlank { "StreamGuide/0.4 FireTV" })
        provider.referer.trim().takeIf { it.isNotBlank() }?.let { put("Referer", it) }
    }
}
