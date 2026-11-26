package com.example.data

import kotlinx.coroutines.flow.Flow

class SilicaRepository(
    private val chatDao: SilicaChatDao,
    private val peerDao: SavedPeerDao,
    private val prefs: SilicaPreferencesRepository
) {
    val engineConfig: Flow<SilicaEngineConfig> = prefs.configFlow
    val sessions: Flow<List<ChatSessionEntity>> = chatDao.observeSessions()
    val savedPeers: Flow<List<SavedPeerEntity>> = peerDao.observePeers()

    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        chatDao.observeMessages(sessionId)

    suspend fun getMessagesOnce(sessionId: Long): List<ChatMessageEntity> =
        chatDao.getMessagesOnce(sessionId)

    suspend fun updateEngineConfig(transform: (SilicaEngineConfig) -> SilicaEngineConfig) =
        prefs.updateConfig(transform)

    suspend fun createSession(title: String, modelId: String): Long =
        chatDao.insertSession(ChatSessionEntity(title = title, modelId = modelId))

    suspend fun updateSessionMeta(sessionId: Long, title: String, modelId: String) =
        chatDao.updateSessionMeta(sessionId, title, modelId, System.currentTimeMillis())

    suspend fun deleteSession(sessionId: Long) = chatDao.deleteSession(sessionId)

    suspend fun insertMessage(message: ChatMessageEntity): Long = chatDao.insertMessage(message)

    suspend fun updateMessage(
        messageId: Long,
        content: String,
        tokPerSec: Float,
        latencyMs: Long,
        activeNodes: Int,
        isError: Boolean
    ) = chatDao.updateMessage(messageId, content, tokPerSec, latencyMs, activeNodes, isError)

    suspend fun upsertPeer(peer: SavedPeerEntity) = peerDao.upsertPeer(peer)

    suspend fun updatePeerLatency(address: String, latencyMs: Long) =
        peerDao.updatePeerLatency(address, latencyMs)

    suspend fun deletePeer(address: String) = peerDao.deletePeer(address)
}
