package com.sternpaul.streamguide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import com.sternpaul.streamguide.data.AppRelease
import com.sternpaul.streamguide.data.AppUpdateRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AppUpdateState(
    val automatic: Boolean = true,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val release: AppRelease? = null,
    val installer: File? = null,
    val showDialog: Boolean = false,
    val message: String = "",
    val error: String? = null
)

class AppUpdateController(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val repository = AppUpdateRepository(context.applicationContext)
    private var downloadJob: Job? = null
    private var waitingForInstallPermission = false
    var state by mutableStateOf(AppUpdateState(automatic = prefs.getBoolean("automatic", true)))
        private set

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        state = state.copy(automatic = enabled)
    }

    fun onAppOpen(activity: ComponentActivity) {
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
                install(activity)
            } else {
                state = state.copy(error = "Allow StreamGuide to install apps in Fire TV Settings, then select Install.")
            }
            return
        }
        if (state.automatic && !state.showDialog) checkForUpdates(automaticRequest = true)
    }

    fun check() = checkForUpdates(automaticRequest = false)

    private fun checkForUpdates(automaticRequest: Boolean) {
        if (state.checking || state.downloading) return
        state = state.copy(checking = true, message = "Checking for updates…", error = null)
        scope.launch {
            try {
                val release = repository.latest()
                state = state.copy(checking = false, release = release, installer = null,
                    showDialog = release != null && (!automaticRequest || state.automatic),
                    message = if (release == null) "StreamGuide is up to date" else "Version ${release.version} is available")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(checking = false, message = error.message ?: "Could not check for updates. Please retry.")
            }
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        waitingForInstallPermission = false
        state = state.copy(showDialog = false, downloading = false, error = null)
    }

    fun update(activity: ComponentActivity) {
        if (state.downloading) return
        if (state.installer != null) { install(activity); return }
        val release = state.release ?: return
        state = state.copy(downloading = true, progress = 0, error = null)
        downloadJob = scope.launch {
            try {
                val installer = repository.download(release) { progress -> state = state.copy(progress = progress) }
                state = state.copy(downloading = false, installer = installer)
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) install(activity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(downloading = false, error = error.message ?: "Download failed. Please retry.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun install(activity: ComponentActivity) {
        val apk = state.installer ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                waitingForInstallPermission = true
                state = state.copy(error = "Allow StreamGuide to install apps, then return here to continue.")
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
                return
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            state = state.copy(error = null)
            activity.startActivity(intent)
        } catch (_: Exception) {
            waitingForInstallPermission = false
            state = state.copy(error = "Could not open the installer. Enable Install unknown apps for StreamGuide in Fire TV Settings and try again.")
        }
    }
}
