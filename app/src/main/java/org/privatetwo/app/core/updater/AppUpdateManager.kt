package org.privatetwo.app.core.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)

object AppUpdateManager {
    private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/uditdemojira-design/PrivateTwo_AndroidApp/main/app_version.json"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/uditdemojira-design/PrivateTwo_AndroidApp/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        // 1. Try raw app_version.json first
        try {
            val request = Request.Builder().url(UPDATE_JSON_URL).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val downloadUrl = json.optString("downloadUrl", "")
                    val releaseNotes = json.optString("releaseNotes", "New update available with performance improvements and bug fixes.")

                    if (remoteVersionCode > currentVersionCode && downloadUrl.isNotBlank()) {
                        return@withContext UpdateInfo(
                            versionCode = remoteVersionCode,
                            versionName = remoteVersionName,
                            downloadUrl = downloadUrl,
                            releaseNotes = releaseNotes
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback to GitHub Releases API
        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_API)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "") // e.g. "v1.2.0"
                    val releaseNotes = json.optString("body", "New features & bug fixes.")
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    // Extract numeric version if possible
                    val versionNumbers = tagName.replace("[^0-9.]".toRegex(), "").split(".")
                    val derivedCode = if (versionNumbers.size >= 2) {
                        (versionNumbers[0].toIntOrNull() ?: 1) * 100 + (versionNumbers.getOrNull(1)?.toIntOrNull() ?: 0) * 10 + (versionNumbers.getOrNull(2)?.toIntOrNull() ?: 0)
                    } else 0

                    if (derivedCode > currentVersionCode && downloadUrl.isNotBlank()) {
                        return@withContext UpdateInfo(
                            versionCode = derivedCode,
                            versionName = tagName,
                            downloadUrl = downloadUrl,
                            releaseNotes = releaseNotes
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        null
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { onError("Download failed: HTTP ${response.code}") }
                return@withContext
            }

            val body = response.body ?: run {
                withContext(Dispatchers.Main) { onError("Download failed: Empty response") }
                return@withContext
            }

            val totalBytes = body.contentLength()
            val updateFile = File(context.cacheDir, "update.apk")
            if (updateFile.exists()) updateFile.delete()

            body.byteStream().use { input ->
                FileOutputStream(updateFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalDownloaded = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        if (totalBytes > 0) {
                            val progress = (totalDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(progress) }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(1f)
                launchInstaller(context, updateFile)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Failed to download update: ${e.message}")
            }
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
