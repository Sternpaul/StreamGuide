package com.sternpaul.streamguide

import android.content.Context
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.sternpaul.streamguide.core.ProviderConfig
import com.sternpaul.streamguide.core.ProviderHeaders

object PlaybackPlayerFactory {
    fun create(context: Context, provider: ProviderConfig?): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
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
