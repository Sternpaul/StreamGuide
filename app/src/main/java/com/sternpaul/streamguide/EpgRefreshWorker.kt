package com.sternpaul.streamguide

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EpgRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val count = (applicationContext as StreamGuideApp).container.repository.refreshEpg()
        if (count > 0) Result.success() else Result.success()
    } catch (_: Exception) { Result.retry() }
}
