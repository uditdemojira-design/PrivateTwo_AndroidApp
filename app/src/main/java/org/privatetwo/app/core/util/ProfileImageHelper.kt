package org.privatetwo.app.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ProfileImageHelper {

    /**
     * Reads a chosen image URI safely, handles EXIF rotation,
     * scales down to a standard 512x512 avatar to prevent OutOfMemory errors,
     * and saves as a clean JPEG in internal storage.
     */
    fun saveAndOptimizeAvatar(context: Context, uri: Uri): String? {
        return try {
            // 1. Measure dimensions without allocating full bitmap memory
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // Target size for profile avatars (1024x1024 crystal clear full HD)
            val targetSize = 1024
            var sampleSize = 1
            while ((origWidth / (sampleSize * 2)) >= targetSize && (origHeight / (sampleSize * 2)) >= targetSize) {
                sampleSize *= 2
            }

            // 2. Decode sampled bitmap into ARGB_8888 for maximum sharpness
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // 3. Inspect and correct EXIF rotation
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (_: Throwable) {}

            val rotatedBitmap = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    val matrix = Matrix().apply { postRotate(90f) }
                    Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true)
                }
                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    val matrix = Matrix().apply { postRotate(180f) }
                    Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    val matrix = Matrix().apply { postRotate(270f) }
                    Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true)
                }
                else -> sampledBitmap
            }

            // 4. Center-crop to 1:1 square
            val minDim = minOf(rotatedBitmap.width, rotatedBitmap.height)
            val xOffset = (rotatedBitmap.width - minDim) / 2
            val yOffset = (rotatedBitmap.height - minDim) / 2
            val squareBitmap = Bitmap.createBitmap(rotatedBitmap, xOffset, yOffset, minDim, minDim)

            // 5. Final scale to exact 512x512
            val finalBitmap = if (minDim > targetSize) {
                Bitmap.createScaledBitmap(squareBitmap, targetSize, targetSize, true)
            } else {
                squareBitmap
            }

            // 6. Save with timestamped filename (high quality JPEG 92%)
            val destFile = File(context.filesDir, "profile_avatar_${System.currentTimeMillis()}.jpg")
            FileOutputStream(destFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            // Clean up any older avatars
            context.filesDir.listFiles { _, name -> name.startsWith("profile_avatar_") && name != destFile.name }?.forEach {
                try { it.delete() } catch (_: Throwable) {}
            }

            destFile.absolutePath
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    /**
     * Safely loads a bitmap from disk without throwing OutOfMemoryError
     */
    fun loadAvatarBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    /**
     * Compresses the avatar to a small ~10-15KB thumbnail for fast E2EE sync over WebRTC/Signaling.
     */
    fun getAvatarBytesForSync(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return try {
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val targetSize = 1024
            val scaled = if (original.width > targetSize || original.height > targetSize) {
                val minDim = minOf(original.width, original.height)
                val x = (original.width - minDim) / 2
                val y = (original.height - minDim) / 2
                val square = Bitmap.createBitmap(original, x, y, minDim, minDim)
                Bitmap.createScaledBitmap(square, targetSize, targetSize, true)
            } else {
                original
            }
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, baos)
            baos.toByteArray()
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }
}
