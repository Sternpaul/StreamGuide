package com.sternpaul.streamguide.core

object AppUpdatePolicy {
    const val assetName = "StreamGuide-firetv.apk"
    private val releaseVersion = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

    fun version(value: String): List<Int>? {
        val match = releaseVersion.matchEntire(value.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    fun isNewer(candidate: String, installed: String): Boolean {
        val next = version(candidate) ?: return false
        val current = version(installed) ?: return false
        for (index in next.indices) {
            if (next[index] != current[index]) return next[index] > current[index]
        }
        return false
    }

    fun isTrustedDownload(url: String): Boolean = url.startsWith(
        "https://github.com/Sternpaul/StreamGuide/releases/download/"
    ) && url.substringAfterLast('/') == assetName
}
