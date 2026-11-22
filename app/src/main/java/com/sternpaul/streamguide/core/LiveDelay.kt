package com.sternpaul.streamguide.core

/**
 * Optional cushion between the live edge and what is shown. Once active, the player only
 * (re)starts when this much stream is buffered, so short network drops are played out of the
 * buffer instead of freezing the picture. It switches on after a set number of stalls, so a
 * channel change still starts right away; "always" (0 stalls) starts every channel with it.
 */
object LiveDelay {
    const val DEFAULT_SECONDS = 10
    val allowedSeconds = listOf(0, 5, 10, 20, 30)
    const val DEFAULT_ACTIVATION = 2
    val allowedActivation = listOf(0, 1, 2, 3)

    /** Upper bound for the in-memory stream buffer, cushion included. Nothing is written to storage. */
    const val MAX_BUFFER_BYTES = 64 * 1024 * 1024

    fun sanitizeSeconds(seconds: Int): Int = seconds.takeIf { it in allowedSeconds } ?: DEFAULT_SECONDS
    fun sanitizeActivation(stalls: Int): Int = stalls.takeIf { it in allowedActivation } ?: DEFAULT_ACTIVATION

    /** True once [stalls] buffering interruptions of the current stream warrant the cushion. */
    fun shouldActivate(seconds: Int, activation: Int, stalls: Int): Boolean = seconds > 0 && stalls >= activation

    /**
     * A live HLS/DASH playlist only keeps a short window; waiting longer than half of it would
     * fall out of the window. Streams without a known window (plain TS) get the full cushion.
     */
    fun effectiveCushionMs(cushionMs: Int, liveWindowMs: Long?): Int =
        if (liveWindowMs == null || liveWindowMs <= 0) cushionMs else minOf(cushionMs.toLong(), liveWindowMs / 2).toInt()

    /** Filling a bigger cushion takes longer; do not mistake that for a stalled stream. */
    fun bufferingTimeoutMs(baseMs: Long, cushionMs: Int): Long = baseMs + cushionMs

    fun waitingText(targetMs: Int, bufferedMs: Long): String {
        val target = (targetMs + 999) / 1000
        val ready = (bufferedMs / 1000).coerceIn(0, target.toLong())
        return "Waiting ${target}s to avoid further buffering · ${ready}s ready"
    }

    fun secondsLabel(seconds: Int): String = if (seconds == 0) "Off" else "${seconds}s"
    fun activationLabel(stalls: Int): String = when (stalls) {
        0 -> "Always"
        1 -> "After 1st stall"
        2 -> "After 2nd stall"
        3 -> "After 3rd stall"
        else -> "After ${stalls}th stall"
    }
}
