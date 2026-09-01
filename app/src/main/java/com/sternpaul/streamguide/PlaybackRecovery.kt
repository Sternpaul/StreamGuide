package com.sternpaul.streamguide

import androidx.media3.common.PlaybackException

object PlaybackRecoveryPolicy {
    const val maxAutomaticRetries = 3
    const val bufferingTimeoutMs = 15_000L
    private val retryDelaysMs = longArrayOf(1_000L, 2_500L, 5_000L)

    fun delayForRetry(attempt: Int): Long? = retryDelaysMs.getOrNull(attempt - 1)

    fun diagnostic(error: PlaybackException): String {
        val cause = error.cause?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
        return listOfNotNull(error.errorCodeName, cause).distinct().joinToString(" · ")
    }
}
