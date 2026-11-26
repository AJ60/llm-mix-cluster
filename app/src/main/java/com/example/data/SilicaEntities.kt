package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "silica_chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val modelId: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "silica_chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val modelId: String = "",
    val tokPerSec: Float = 0f,
    val latencyMs: Long = 0L,
    val activeNodes: Int = 0,
    val isError: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "silica_saved_peers")
data class SavedPeerEntity(
    @PrimaryKey val address: String, // e.g. "192.168.1.15:50052"
    val label: String,
    val role: String = "WORKER", // "MASTER" | "WORKER"
    val lastLatencyMs: Long = -1L,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface SilicaChatDao {
    @Query("SELECT * FROM silica_chat_sessions ORDER BY updatedAtMs DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Query("UPDATE silica_chat_sessions SET title = :title, modelId = :modelId, updatedAtMs = :updatedAtMs WHERE id = :sessionId")
    suspend fun updateSessionMeta(sessionId: Long, title: String, modelId: String, updatedAtMs: Long)

    @Query("DELETE FROM silica_chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM silica_chat_messages WHERE sessionId = :sessionId ORDER BY createdAtMs ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM silica_chat_messages WHERE sessionId = :sessionId ORDER BY createdAtMs ASC, id ASC")
    suspend fun getMessagesOnce(sessionId: Long): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query(
        "UPDATE silica_chat_messages SET content = :content, tokPerSec = :tokPerSec, " +
            "latencyMs = :latencyMs, activeNodes = :activeNodes, isError = :isError WHERE id = :messageId"
    )
    suspend fun updateMessage(
        messageId: Long,
        content: String,
        tokPerSec: Float,
        latencyMs: Long,
        activeNodes: Int,
        isError: Boolean
    )

    @Query("DELETE FROM silica_chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSessionMessages(sessionId: Long)
}

@Dao
interface SavedPeerDao {
    @Query("SELECT * FROM silica_saved_peers ORDER BY addedAtMs DESC")
    fun observePeers(): Flow<List<SavedPeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: SavedPeerEntity)

    @Query("UPDATE silica_saved_peers SET lastLatencyMs = :latencyMs WHERE address = :address")
    suspend fun updatePeerLatency(address: String, latencyMs: Long)

    @Query("DELETE FROM silica_saved_peers WHERE address = :address")
    suspend fun deletePeer(address: String)
}
