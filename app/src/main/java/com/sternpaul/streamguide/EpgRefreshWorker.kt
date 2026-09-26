package com.sternpaul.streamguide

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EpgRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as StreamGuideApp).container
        if (container.store.getProvider() != null && container.store.epgAutoUpdate()) {
            container.repository.refreshEpg()
        }
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { Result.retry() }
}
