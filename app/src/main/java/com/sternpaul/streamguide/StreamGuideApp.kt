package com.sternpaul.streamguide

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sternpaul.streamguide.data.AppStore
import com.sternpaul.streamguide.data.PlaylistRepository
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    val store = AppStore(app)
    val repository = PlaylistRepository(store)
}

class StreamGuideApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this); scheduleEpg() }
    fun scheduleEpg() {
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(container.store.epgHours().toLong(), TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("epg-refresh", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
