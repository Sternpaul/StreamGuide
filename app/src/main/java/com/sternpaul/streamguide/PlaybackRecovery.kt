package com.sternpaul.streamguide

import androidx.media3.common.PlaybackException

object PlaybackRecoveryPolicy {
    const val maxAutomaticRetries = 3
    const val bufferingTimeoutMs = 15_000L
    private val retryDelaysMs = longArrayOf(1_000L, 2_500L, 5_000L)

    data class RetryPlan(val attempt: Int, val delayMs: Long)

    /** Returns the next automatic retry, or null once the retry budget is exhausted. */
    fun nextRetry(completedRetries: Int): RetryPlan? {
        val attempt = completedRetries + 1
        return delayForRetry(attempt)?.let { RetryPlan(attempt, it) }
    }

    fun delayForRetry(attempt: Int): Long? = retryDelaysMs.getOrNull(attempt - 1)

    fun diagnostic(error: PlaybackException): String {
        val cause = error.cause?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
        return listOfNotNull(error.errorCodeName, cause).distinct().joinToString(" · ")
    }
}
