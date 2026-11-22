package com.sternpaul.streamguide

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.sternpaul.streamguide.core.LiveDelay

/**
 * Media3's default buffering, plus a live-delay cushion that can be switched on while a stream
 * plays: with [cushionMs] > 0, playback only (re)starts once that much is buffered. The buffer
 * lives in memory and is capped at [LiveDelay.MAX_BUFFER_BYTES].
 */
@UnstableApi
class CushionLoadControl : LoadControl {
    private val delegate = DefaultLoadControl.Builder().setTargetBufferBytes(LiveDelay.MAX_BUFFER_BYTES).build()
    @Volatile var cushionMs = 0
    /** The cushion actually waited for last time; smaller than [cushionMs] for short live windows. */
    @Volatile var effectiveCushionMs = 0; private set
    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(
        playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection>
    ) = delegate.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(): Allocator = delegate.allocator
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val cushion = cushionMs
        if (cushion <= 0) return delegate.shouldStartPlayback(parameters)
        val target = LiveDelay.effectiveCushionMs(cushion, liveWindowMs(parameters))
        effectiveCushionMs = target
        if (parameters.bufferedDurationUs >= target * 1_000L) return true
        // Memory cap reached before the cushion filled (very high bitrate): play what there is.
        return delegate.allocator.totalBytesAllocated >= LiveDelay.MAX_BUFFER_BYTES / 10 * 9
    }

    private fun liveWindowMs(parameters: LoadControl.Parameters): Long? {
        val timeline = parameters.timeline
        if (timeline.isEmpty) return null
        val index = timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, period).windowIndex
        timeline.getWindow(index, window)
        return if (window.isLive() && window.durationUs != C.TIME_UNSET) window.durationUs / 1_000 else null
    }
}
