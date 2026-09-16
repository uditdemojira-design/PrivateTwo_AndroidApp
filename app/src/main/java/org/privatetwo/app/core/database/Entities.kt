package org.privatetwo.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DeliveryStatus {
    SENDING,
    SENT,
    DELIVERED,
    FAILED
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val encryptedContent: String,
    val nonce: String,
    val authTag: String,
    val deliveryStatus: DeliveryStatus,
    val messageType: String,
    val isIncoming: Boolean,
    val mediaLocalPath: String? = null,
    val mediaFileName: String? = null,
    val mediaFileSize: Long = 0L
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val checksumSha256: String,
    val totalChunks: Int,
    val completedChunks: Int,
    val localFilePath: String,
    val status: String,
    val isIncoming: Boolean,
    val timestamp: Long
)

@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val durationSeconds: Long,
    val callType: String,
    val callStatus: String,
    val isIncoming: Boolean
)
