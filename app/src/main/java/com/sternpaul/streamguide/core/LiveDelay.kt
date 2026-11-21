package com.sternpaul.streamguide.core

/**
 * Optional cushion between the live edge and what is shown. The player waits until this much
 * stream is buffered before starting (and again after a stall), so short network hiccups are
 * played out of the buffer instead of freezing the picture. 0 turns it off.
 */
object LiveDelay {
    const val DEFAULT_SECONDS = 10
    val allowedSeconds = listOf(0, 5, 10, 20, 30)

    // Media3's DefaultLoadControl defaults, used when the delay is off.
    const val DEFAULT_MIN_BUFFER_MS = 50_000
    const val DEFAULT_MAX_BUFFER_MS = 50_000
    const val DEFAULT_START_MS = 2_500
    const val DEFAULT_REBUFFER_MS = 5_000

    data class BufferPlan(val minBufferMs: Int, val maxBufferMs: Int, val startMs: Int, val rebufferMs: Int, val liveOffsetMs: Long?)

    fun sanitize(seconds: Int): Int = seconds.takeIf { it in allowedSeconds } ?: DEFAULT_SECONDS

    fun plan(seconds: Int): BufferPlan {
        val delayMs = sanitize(seconds) * 1_000
        if (delayMs == 0) return BufferPlan(DEFAULT_MIN_BUFFER_MS, DEFAULT_MAX_BUFFER_MS, DEFAULT_START_MS, DEFAULT_REBUFFER_MS, null)
        // Keep loading well ahead of the cushion; the start thresholds must not exceed minBuffer.
        val minBuffer = maxOf(DEFAULT_MIN_BUFFER_MS, delayMs * 2)
        return BufferPlan(minBuffer, maxOf(DEFAULT_MAX_BUFFER_MS, minBuffer), delayMs, delayMs, delayMs.toLong())
    }

    /** Filling a bigger cushion takes longer; do not mistake that for a stalled stream. */
    fun bufferingTimeoutMs(baseMs: Long, seconds: Int): Long = baseMs + sanitize(seconds) * 1_000L

    fun label(seconds: Int): String = if (seconds == 0) "Off" else "${seconds}s"
}
