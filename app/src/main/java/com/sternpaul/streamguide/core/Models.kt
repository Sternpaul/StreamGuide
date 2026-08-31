package com.sternpaul.streamguide.core

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "Other",
    val tvgId: String = "",
    val logoUrl: String = "",
    val providerOrder: Int = 0,
    val manualRank: Long? = null,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    val catchupSource: String = "",
    val catchupDays: Int = 0,
    val customName: String = "",
    val customGroup: String = ""
) {
    val displayName: String get() = customName.ifBlank { name }
    val displayGroup: String get() = customGroup.ifBlank { group }
}

data class Program(
    val channelId: String,
    val title: String,
    val description: String = "",
    val startEpochMs: Long,
    val endEpochMs: Long
)

enum class ProviderType { M3U, XTREAM }

data class ProviderConfig(
    val type: ProviderType,
    val name: String,
    val playlistUrl: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = ""
)

data class RefreshStatus(
    val running: Boolean = false,
    val lastSuccessEpochMs: Long = 0,
    val message: String = "Not refreshed yet",
    val channelCount: Int = 0,
    val programCount: Int = 0
)
