package com.sternpaul.streamguide

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sternpaul.streamguide.core.LiveDelay
import com.sternpaul.streamguide.core.ProviderConfig
import com.sternpaul.streamguide.core.ProviderHeaders

object PlaybackPlayerFactory {
    @androidx.media3.common.util.UnstableApi
    fun create(context: Context, provider: ProviderConfig?, delaySeconds: Int = liveDelaySeconds(context)): ExoPlayer {
        // Streams, playlists, segments and keys use the shared client, so they follow the proxy.
        // HTTP(S) only: no UDP/RTMP data source exists that could go around it.
        val http = (context.applicationContext as StreamGuideApp).container.http
        val httpFactory = OkHttpDataSource.Factory(http.callFactory)
        provider?.let { config ->
            val headers = ProviderHeaders.forProvider(config)
            if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
        }
        val plan = LiveDelay.plan(delaySeconds)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(plan.minBufferMs, plan.maxBufferMs, plan.startMs, plan.rebufferMs)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()
    }

    fun liveDelaySeconds(context: Context): Int = (context.applicationContext as StreamGuideApp).container.store.liveDelaySeconds()

    /** HLS/DASH streams also sit [delaySeconds] behind the live edge instead of right at it. */
    fun mediaItem(url: String, delaySeconds: Int): MediaItem {
        val offset = LiveDelay.plan(delaySeconds).liveOffsetMs ?: return MediaItem.fromUri(url)
        return MediaItem.Builder().setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(offset).build())
            .build()
    }
}
