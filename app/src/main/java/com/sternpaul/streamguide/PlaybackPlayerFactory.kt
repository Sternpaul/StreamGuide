package com.sternpaul.streamguide

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sternpaul.streamguide.core.ProviderConfig
import com.sternpaul.streamguide.core.ProviderHeaders

object PlaybackPlayerFactory {
    @androidx.media3.common.util.UnstableApi
    fun create(context: Context, provider: ProviderConfig?): ExoPlayer {
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
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()
    }
}
