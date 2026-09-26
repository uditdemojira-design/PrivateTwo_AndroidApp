package org.privatetwo.app.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessagesFlow(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateDeliveryStatus(id: String, status: DeliveryStatus)

    @Query("SELECT * FROM messages WHERE isIncoming = 1 AND deliveryStatus != 'READ'")
    suspend fun getUnreadIncomingMessages(): List<MessageEntity>

    @Query("UPDATE messages SET deliveryStatus = 'READ' WHERE isIncoming = 1 AND deliveryStatus != 'READ'")
    suspend fun markAllIncomingAsRead()

    @Query("SELECT * FROM messages WHERE isIncoming = 0 AND deliveryStatus = 'SENT'")
    suspend fun getPendingOutboundMessages(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY timestamp DESC")
    fun getAllTransfersFlow(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id LIMIT 1")
    suspend fun getTransferById(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferEntity)

    @Update
    suspend fun updateTransfer(transfer: TransferEntity)

    @Query("UPDATE transfers SET status = :status, completedChunks = :completedChunks WHERE id = :id")
    suspend fun updateTransferProgress(id: String, status: String, completedChunks: Int)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteTransfer(id: String)

    @Query("DELETE FROM transfers")
    suspend fun deleteAllTransfers()
}

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun getAllCallRecordsFlow(): Flow<List<CallRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(record: CallRecordEntity)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteCallRecord(id: String)

    @Query("DELETE FROM call_records")
    suspend fun deleteAllCallRecords()
}

