package com.sternpaul.streamguide.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sternpaul.streamguide.BuildConfig
import com.sternpaul.streamguide.core.AppUpdatePolicy
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppRelease(val version: String, val downloadUrl: String, val size: Long, val sha256: String?)

class AppUpdateRepository(private val context: Context) {
    private val client = OkHttpClient.Builder().followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()

    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/Sternpaul/StreamGuide/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "StreamGuide/${BuildConfig.VERSION_NAME}").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            check(response.isSuccessful) { "Could not check for updates (HTTP ${response.code}). Try again later." }
            val json = JSONObject(response.body?.string() ?: error("Empty release response"))
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null
            val version = json.getString("tag_name")
            if (!AppUpdatePolicy.isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
            val assets = json.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == AppUpdatePolicy.assetName }
                ?: error("The new release has no Fire TV installer yet. Try again later.")
            val url = asset.getString("browser_download_url")
            check(AppUpdatePolicy.isTrustedDownload(url)) { "Unexpected update download address" }
            val size = asset.getLong("size")
            check(size in 1..200_000_000L) { "Invalid update size" }
            val digest = asset.optString("digest").takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }
                ?.removePrefix("sha256:")
            AppRelease(version.removePrefix("v"), url, size, digest)
        }
    }

    suspend fun download(release: AppRelease, onProgress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
        check(AppUpdatePolicy.isTrustedDownload(release.downloadUrl)) { "Unexpected update download address" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("update-", ".apk", directory)
        val apk = File(directory, AppUpdatePolicy.assetName)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(Request.Builder().url(release.downloadUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Update download failed (HTTP ${response.code})." }
                val body = response.body ?: error("Empty update download")
                var received = 0L
                var lastProgress = -1
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= release.size) { "Update size does not match the release" }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val progress = (received * 100 / release.size).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                        }
                    }
                }
                check(received == release.size) { "The update download was incomplete. Please retry." }
            }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            check(release.sha256 == null || actualDigest.equals(release.sha256, ignoreCase = true)) { "Update checksum did not match" }
            currentCoroutineContext().ensureActive()
            verifyPackage(partial, release)
            check(!apk.exists() || apk.delete()) { "Could not replace the downloaded update" }
            check(partial.renameTo(apk)) { "Could not save the update" }
            apk
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: AppRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid Android installer")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(archive.packageName == context.packageName) { "This update is for a different app" }
        val versionCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(versionCode > BuildConfig.VERSION_CODE) { "This installer is not newer than the installed app" }
        check(archive.versionName == release.version) { "Installer version does not match the release" }
        val downloadedSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        check(!downloadedSigners.isNullOrEmpty() && !installedSigners.isNullOrEmpty() &&
            downloadedSigners.toSet() == installedSigners.toSet()) { "Update signature does not match StreamGuide" }
    }
}
