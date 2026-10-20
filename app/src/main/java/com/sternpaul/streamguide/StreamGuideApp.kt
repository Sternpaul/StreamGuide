package com.sternpaul.streamguide

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sternpaul.streamguide.data.AppStore
import com.sternpaul.streamguide.data.HttpClients
import com.sternpaul.streamguide.data.PlaylistRepository
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    val store = AppStore(app)
    /** Created before anything that talks to the network, with the saved proxy already applied. */
    val http = HttpClients(store.proxySettings(), socksSupported = Build.VERSION.SDK_INT >= 24)
    val repository = PlaylistRepository(store, http)
}

class StreamGuideApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this); scheduleEpg() }
    /** Channel logos load through the shared client, so they follow the proxy setting too. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).callFactory { container.http.callFactory }.build()
    fun scheduleEpg() {
        val workManager = WorkManager.getInstance(this)
        if (!container.store.epgAutoUpdate()) {
            workManager.cancelUniqueWork("epg-refresh")
            return
        }
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(container.store.epgHours().toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork("epg-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
