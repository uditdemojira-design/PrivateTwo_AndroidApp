package org.privatetwo.app.core.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileUtils {

    /**
     * Resolves the real original file name and extension from a content:// or file:// URI.
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme.equals("content", ignoreCase = true)) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (result.isNullOrBlank()) {
            val path = uri.path
            val cut = path?.lastIndexOf('/') ?: -1
            if (cut != -1 && path != null) {
                result = path.substring(cut + 1)
            }
        }
        return if (!result.isNullOrBlank()) {
            result!!
        } else {
            "file_${System.currentTimeMillis()}"
        }
    }

    /**
     * Accurately determines the MIME type of a file based on its extension or fallback name.
     */
    fun getMimeType(file: File, fallbackFileName: String? = null): String {
        val nameToCheck = if (file.extension.isNotBlank() && !file.extension.equals("bin", ignoreCase = true)) {
            file.name
        } else {
            fallbackFileName ?: file.name
        }

        val dotIndex = nameToCheck.lastIndexOf('.')
        val ext = if (dotIndex != -1 && dotIndex < nameToCheck.length - 1) {
            nameToCheck.substring(dotIndex + 1).lowercase()
        } else {
            ""
        }

        if (ext.isNotBlank()) {
            val systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!systemMime.isNullOrBlank()) {
                return systemMime
            }
        }

        return when (ext) {
            // Video types
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp", "3gpp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"

            // Audio types
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "amr" -> "audio/amr"

            // Image types
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"

            // Documents
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt", "csv", "log" -> "text/plain"

            // Compressed & Executables
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"

            else -> "*/*"
        }
    }

    /**
     * Opens the file using Android's Intent.ACTION_VIEW with the exact MIME type,
     * proper FileProvider URI permissions, and an app chooser that prioritizes matching viewer apps.
     */
    fun openFile(context: Context, filePath: String?, fallbackFileName: String? = null) {
        if (filePath.isNullOrBlank()) {
            Toast.makeText(context, "File path is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        var targetFile = File(filePath)
        if (!targetFile.exists() || !targetFile.canRead()) {
            Toast.makeText(context, "File not found or cannot be read", Toast.LENGTH_SHORT).show()
            return
        }

        // If the target file ends in .bin or has no extension, but fallbackFileName has a valid extension,
        // create a friendly named link/copy in cache so external apps (like video players) accept it.
        val targetExt = targetFile.extension.lowercase()
        val fallbackExt = fallbackFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        if ((targetExt == "bin" || targetExt.isBlank()) && fallbackExt.isNotBlank() && fallbackExt != "bin") {
            try {
                val renamedTarget = File(targetFile.parentFile, "${targetFile.nameWithoutExtension}.$fallbackExt")
                if (!renamedTarget.exists()) {
                    targetFile.copyTo(renamedTarget, overwrite = true)
                }
                if (renamedTarget.exists()) {
                    targetFile = renamedTarget
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            targetFile.setReadable(true, false)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                targetFile
            )

            val mimeType = getMimeType(targetFile, fallbackFileName)

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri(targetFile.name, uri)
            }

            // Explicitly grant read URI permission to all potential resolver activities
            val resolvedActivities = context.packageManager.queryIntentActivities(
                viewIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (info in resolvedActivities) {
                context.grantUriPermission(
                    info.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val chooserTitle = when {
                mimeType.startsWith("video/") -> "Play video with"
                mimeType.startsWith("audio/") -> "Play audio with"
                mimeType.startsWith("image/") -> "Open image with"
                mimeType == "application/pdf" -> "Open PDF with"
                else -> "Open file with"
            }

            val chooserIntent = Intent.createChooser(viewIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback attempt with generic */* if specific MIME failed to find an activity
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile
                )
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    clipData = ClipData.newRawUri(targetFile.name, uri)
                }
                context.startActivity(Intent.createChooser(fallbackIntent, "Open file with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (fallbackEx: Exception) {
                Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
