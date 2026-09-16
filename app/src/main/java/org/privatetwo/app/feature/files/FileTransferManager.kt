package org.privatetwo.app.feature.files

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.privatetwo.app.core.crypto.CryptoEngine
import org.privatetwo.app.core.crypto.MessageEnvelope
import org.privatetwo.app.core.crypto.MessageType
import org.privatetwo.app.core.database.PrivateTwoDatabase
import org.privatetwo.app.core.database.TransferEntity
import org.privatetwo.app.core.security.SecureStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID

sealed class TransferProgressState {
    data class Progress(val transferId: String, val progressFraction: Float, val status: String) : TransferProgressState()
    data class Completed(val transferId: String, val filePath: String) : TransferProgressState()
    data class Failed(val transferId: String, val error: String) : TransferProgressState()
}

/**
 * Handles end-to-end encrypted chunked file & photo streaming,
 * SHA-256 checksum integrity verification, and path traversal mitigation.
 */
class FileTransferManager(
    private val context: Context,
    private val database: PrivateTwoDatabase,
    private val secureStorage: SecureStorage
) {
    companion object {
        const val CHUNK_SIZE_BYTES = 16 * 1024 // 16 KB optimal chunk size for WebRTC DataChannel

        /**
         * Sanitizes incoming filenames to prevent path traversal attacks (e.g. "../../../etc/passwd").
         */
        fun sanitizeFileName(rawFileName: String): String {
            val baseName = File(rawFileName.replace('\\', '/')).name // strip any path separators
            val sanitized = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return if (sanitized.isBlank() || sanitized.trim('.').isBlank() || sanitized.all { it == '.' || it == '_' }) {
                "file_${System.currentTimeMillis()}.bin"
            } else {
                sanitized
            }
        }
    }

    private val transfersDir: File by lazy {
        File(context.filesDir, "transfers").apply {
            if (!exists()) mkdirs()
        }
    }

    private val incomingAssemblies = mutableMapOf<String, IncomingFileAssembly>()

    private val _transferState = MutableStateFlow<TransferProgressState?>(null)
    val transferState: StateFlow<TransferProgressState?> = _transferState.asStateFlow()

    data class CompletedTransfer(
        val transferId: String,
        val file: File,
        val isPhoto: Boolean
    )

    data class IncomingFileAssembly(
        val transferId: String,
        val fileName: String,
        val totalSize: Long,
        val totalChunks: Int,
        val expectedChecksum: String,
        val isPhoto: Boolean,
        val tempFile: File,
        var receivedChunks: Int = 0
    )

    suspend fun sendFile(
        file: File,
        isPhoto: Boolean,
        transferId: String = UUID.randomUUID().toString(),
        nextSequenceNumber: () -> Long,
        onChunkReady: suspend (MessageEnvelope) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val totalSize = file.length()
        val totalChunks = ((totalSize + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt().coerceAtLeast(1)
        val fileBytes = file.readBytes()
        val checksumSha256 = CryptoEngine.computeSha256(fileBytes)

        val localDeviceId = secureStorage.getLocalDeviceId()
        val peerDeviceId = secureStorage.getPairedPeerDeviceId() ?: throw IllegalStateException("Not paired")
        val encryptionKey = secureStorage.getOutboundSessionKey() ?: throw IllegalStateException("Session key not available")

        val transferEntity = TransferEntity(
            id = transferId,
            fileName = sanitizeFileName(file.name),
            fileSize = totalSize,
            mimeType = if (isPhoto) "image/jpeg" else "application/octet-stream",
            checksumSha256 = checksumSha256,
            totalChunks = totalChunks,
            completedChunks = 0,
            localFilePath = file.absolutePath,
            status = "TRANSFERRING",
            isIncoming = false,
            timestamp = System.currentTimeMillis()
        )
        database.transferDao().insertTransfer(transferEntity)

        val headerJson = JSONObject()
            .put("transferId", transferId)
            .put("fileName", sanitizeFileName(file.name))
            .put("fileSize", totalSize)
            .put("totalChunks", totalChunks)
            .put("checksum", checksumSha256)
            .put("isPhoto", isPhoto)
            .toString()

        val headerEnvelope = MessageEnvelope.pack(
            senderDeviceId = localDeviceId,
            recipientDeviceId = peerDeviceId,
            sequenceNumber = nextSequenceNumber(),
            messageType = if (isPhoto) MessageType.PHOTO_HEADER else MessageType.FILE_HEADER,
            plaintext = headerJson.toByteArray(Charsets.UTF_8),
            encryptionKey = encryptionKey
        )
        onChunkReady(headerEnvelope)

        val fis = FileInputStream(file)
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        var bytesRead: Int
        var chunkIndex = 0

        fis.use { stream ->
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                val chunkData = if (bytesRead == CHUNK_SIZE_BYTES) buffer else buffer.copyOf(bytesRead)
                val chunkPayload = JSONObject()
                    .put("transferId", transferId)
                    .put("chunkIndex", chunkIndex)
                    .put("data", Base64.getEncoder().encodeToString(chunkData))
                    .toString()

                val chunkEnvelope = MessageEnvelope.pack(
                    senderDeviceId = localDeviceId,
                    recipientDeviceId = peerDeviceId,
                    sequenceNumber = nextSequenceNumber(),
                    messageType = if (isPhoto) MessageType.PHOTO_CHUNK else MessageType.FILE_CHUNK,
                    plaintext = chunkPayload.toByteArray(Charsets.UTF_8),
                    encryptionKey = encryptionKey
                )
                onChunkReady(chunkEnvelope)

                chunkIndex++
                val progress = chunkIndex.toFloat() / totalChunks
                _transferState.value = TransferProgressState.Progress(transferId, progress, "Sending...")
                database.transferDao().updateTransferProgress(transferId, "TRANSFERRING", chunkIndex)
            }
        }

        database.transferDao().updateTransferProgress(transferId, "COMPLETED", totalChunks)
        _transferState.value = TransferProgressState.Completed(transferId, file.absolutePath)
        transferId
    }

    suspend fun handleIncomingHeader(decryptedHeaderBytes: ByteArray) = withContext(Dispatchers.IO) {
        val json = JSONObject(String(decryptedHeaderBytes, Charsets.UTF_8))
        val transferId = json.getString("transferId")
        val safeFileName = sanitizeFileName(json.getString("fileName"))
        val totalSize = json.getLong("fileSize")
        val totalChunks = json.getInt("totalChunks")
        val checksum = json.getString("checksum")
        val isPhoto = json.optBoolean("isPhoto", false)

        val tempFile = File(transfersDir, "${transferId}.part")
        if (tempFile.exists()) tempFile.delete()

        val assembly = IncomingFileAssembly(
            transferId = transferId,
            fileName = safeFileName,
            totalSize = totalSize,
            totalChunks = totalChunks,
            expectedChecksum = checksum,
            isPhoto = isPhoto,
            tempFile = tempFile,
            receivedChunks = 0
        )
        incomingAssemblies[transferId] = assembly

        val entity = TransferEntity(
            id = transferId,
            fileName = safeFileName,
            fileSize = totalSize,
            mimeType = if (isPhoto) "image/jpeg" else "application/octet-stream",
            checksumSha256 = checksum,
            totalChunks = totalChunks,
            completedChunks = 0,
            localFilePath = tempFile.absolutePath,
            status = "TRANSFERRING",
            isIncoming = true,
            timestamp = System.currentTimeMillis()
        )
        database.transferDao().insertTransfer(entity)
    }

    suspend fun handleIncomingChunk(decryptedChunkBytes: ByteArray): CompletedTransfer? = withContext(Dispatchers.IO) {
        val json = JSONObject(String(decryptedChunkBytes, Charsets.UTF_8))
        val transferId = json.getString("transferId")
        val dataBase64 = json.getString("data")
        val rawBytes = Base64.getDecoder().decode(dataBase64)

        val assembly = incomingAssemblies[transferId] ?: return@withContext null

        FileOutputStream(assembly.tempFile, true).use { fos ->
            fos.write(rawBytes)
        }
        assembly.receivedChunks++

        val progress = assembly.receivedChunks.toFloat() / assembly.totalChunks
        _transferState.value = TransferProgressState.Progress(transferId, progress, "Receiving...")
        database.transferDao().updateTransferProgress(transferId, "TRANSFERRING", assembly.receivedChunks)

        if (assembly.receivedChunks >= assembly.totalChunks) {
            val finalBytes = assembly.tempFile.readBytes()
            val computedHash = CryptoEngine.computeSha256(finalBytes)

            if (!computedHash.equals(assembly.expectedChecksum, ignoreCase = true)) {
                assembly.tempFile.delete()
                incomingAssemblies.remove(transferId)
                database.transferDao().updateTransferProgress(transferId, "FAILED_INTEGRITY", assembly.receivedChunks)
                _transferState.value = TransferProgressState.Failed(transferId, "SHA-256 checksum verification failed. File corrupted.")
                return@withContext null
            }

            val finalFile = File(transfersDir, "${System.currentTimeMillis()}_${assembly.fileName}")
            assembly.tempFile.renameTo(finalFile)
            val wasPhoto = assembly.isPhoto
            incomingAssemblies.remove(transferId)

            val finalEntity = database.transferDao().getTransferById(transferId)?.copy(
                status = "COMPLETED",
                localFilePath = finalFile.absolutePath,
                completedChunks = assembly.totalChunks
            )
            if (finalEntity != null) {
                database.transferDao().updateTransfer(finalEntity)
            }

            _transferState.value = TransferProgressState.Completed(transferId, finalFile.absolutePath)
            return@withContext CompletedTransfer(transferId, finalFile, wasPhoto)
        }

        null
    }
}
