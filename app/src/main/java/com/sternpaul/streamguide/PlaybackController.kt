package com.sternpaul.streamguide

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sternpaul.streamguide.core.ProviderConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the one main player for the whole app. It outlives individual screens, so the same
 * stream keeps running while it moves between full screen and the guide preview.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onError: (area: String, message: String) -> Unit
) {
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var info by mutableStateOf(""); private set
    var diagnostic by mutableStateOf(""); private set
    var failed by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    /** The viewer paused; a paused stream is not a failure and needs no recovery. */
    var paused by mutableStateOf(false); private set

    private var provider: ProviderConfig? = null
    private var url: String? = null
    private var label = ""
    private var retries = 0
    private var loadJob: Job? = null
    private var retryJob: Job? = null
    private var bufferingJob: Job? = null
    private var stableJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) {
            isPlaying = value
            val current = player ?: return
            if (current.playbackState == Player.STATE_READY) info = if (value) "" else "Paused"
            stableJob?.cancel()
            // Only a stream that has stayed up earns a fresh retry budget.
            if (value) stableJob = scope.launch { delay(30_000); retries = 0 }
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (url == null) return
            bufferingJob?.cancel()
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    info = "Buffering"
                    bufferingJob = scope.launch {
                        delay(PlaybackRecoveryPolicy.bufferingTimeoutMs)
                        if (player?.playbackState == Player.STATE_BUFFERING) scheduleRecovery("Buffering timed out")
                    }
                }
                Player.STATE_READY -> {
                    info = if (player?.isPlaying == true) "" else "Paused"
                    retryJob?.cancel(); retryJob = null
                    failed = false; diagnostic = ""
                }
                Player.STATE_ENDED -> scheduleRecovery("The live stream ended unexpectedly")
                else -> Unit
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            if (url == null) return
            val message = PlaybackRecoveryPolicy.diagnostic(error)
            onError("Playback · $label", message)
            scheduleRecovery(message)
        }
    }

    /** Starts [url]; calling it again for the stream that is already loaded changes nothing. */
    fun play(url: String, label: String, provider: ProviderConfig?) {
        if (this.url == url && this.provider == provider && player != null) return
        if (player == null || this.provider != provider) {
            releasePlayer()
            player = PlaybackPlayerFactory.create(context, provider).also { it.addListener(listener) }
            this.provider = provider
        }
        val zapping = this.url != null
        this.url = url; this.label = label
        cancelJobs()
        retries = 0; failed = false; paused = false; diagnostic = ""; info = "Connecting…"
        player?.stop()
        // Holding Up/Down flips through channels; only the one the viewer lands on is opened.
        loadJob = scope.launch { if (zapping) delay(250); load() }
    }

    fun stop() {
        cancelJobs()
        url = null; isPlaying = false; failed = false; paused = false; info = ""; diagnostic = ""
        player?.run { stop(); clearMediaItems() }
    }

    fun retry() {
        if (url == null) return
        cancelJobs()
        retries = 0; failed = false; paused = false; diagnostic = ""; info = "Connecting…"
        load()
    }

    fun togglePause() {
        val current = player ?: return
        if (url == null || failed) return
        if (current.isPlaying) { paused = true; current.pause() } else { paused = false; current.play() }
    }
    fun pause(value: Boolean) { if ((player?.isPlaying == true) == value) togglePause() }

    /** Returns false when the stream has no seekable window. */
    fun seek(forward: Boolean): Boolean {
        val current = player ?: return false
        if (!current.isCurrentMediaItemSeekable) return false
        if (forward) current.seekForward() else current.seekBack()
        return true
    }

    fun release() { cancelJobs(); url = null; releasePlayer() }

    private fun load() {
        val current = player ?: return
        val target = url ?: return
        current.stop()
        current.clearMediaItems()
        current.setMediaItem(MediaItem.fromUri(target))
        current.prepare()
        current.playWhenReady = true
    }

    private fun scheduleRecovery(message: String) {
        if (url == null || retryJob?.isActive == true) return
        val plan = PlaybackRecoveryPolicy.nextRetry(retries)
        diagnostic = message
        if (plan == null) {
            failed = true
            info = "Playback unavailable"
            return
        }
        retries = plan.attempt
        info = "Reconnecting · ${plan.attempt}/${PlaybackRecoveryPolicy.maxAutomaticRetries}"
        retryJob = scope.launch {
            delay(plan.delayMs)
            // Clear the guard before preparing: a failed prepare can report its error
            // synchronously, and an active job would make scheduleRecovery ignore it.
            retryJob = null
            if (!failed) load()
        }
    }

    private fun cancelJobs() {
        loadJob?.cancel(); retryJob?.cancel(); bufferingJob?.cancel(); stableJob?.cancel()
        loadJob = null; retryJob = null; bufferingJob = null; stableJob = null
    }

    private fun releasePlayer() {
        player?.run { removeListener(listener); release() }
        player = null
        isPlaying = false
    }
}
