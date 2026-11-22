package com.sternpaul.streamguide

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sternpaul.streamguide.core.ProviderConfig
import com.sternpaul.streamguide.core.ProviderHeaders

object PlaybackPlayerFactory {
    @androidx.media3.common.util.UnstableApi
    fun create(context: Context, provider: ProviderConfig?, loadControl: CushionLoadControl = CushionLoadControl()): ExoPlayer {
        // Streams, playlists, segments and keys use the shared client, so they follow the proxy.
        // HTTP(S) only: no UDP/RTMP data source exists that could go around it.
        val http = (context.applicationContext as StreamGuideApp).container.http
        val httpFactory = OkHttpDataSource.Factory(http.callFactory)
        provider?.let { config ->
            val headers = ProviderHeaders.forProvider(config)
            if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()
    }

    /**
     * With a live delay, HLS/DASH streams play at normal speed instead of speeding up to catch
     * the live edge, which would eat the cushion again. "Always" also starts that far behind.
     */
    fun mediaItem(url: String, delaySeconds: Int = 0, alwaysDelayed: Boolean = false): MediaItem {
        if (delaySeconds <= 0) return MediaItem.fromUri(url)
        val live = MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f)
        if (alwaysDelayed) live.setTargetOffsetMs(delaySeconds * 1_000L)
        return MediaItem.Builder().setUri(url).setLiveConfiguration(live.build()).build()
    }
}
