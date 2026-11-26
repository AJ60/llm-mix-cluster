package com.example.service

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class TensorCacheStats(
    val cachedBytes: Long = 0L,
    val cachedMb: Float = 0f,
    val layerCount: Int = 0,
    val hitCount: Int = 0,
    val bandwidthSavedMb: Float = 0f,
    val isEnabled: Boolean = true,
    val statusLabel: String = "Ready (auto-caches layer shards on first run)"
)

data class ShardSpanCacheResult(
    val totalLayers: Int,
    val cachedLayers: List<Int>,
    val missingLayers: List<Int>,
    val allCached: Boolean,
    val hitRatePct: Int,
    val cachedBytes: Long
)

/**
 * Persistent local flash/RAM cache for streamed GGUF model layer weights and KV buffers.
 * Eliminates repeated multi-hundred-MB transfers over slow Wi-Fi when reconnecting to a Master Hub.
 */
object TensorCacheManager {

    private const val CACHE_DIR_NAME = "silica_tensor_cache"
    private val STANDALONE_CACHE_DIR = File("/data/local/tmp/silica_tensor_cache")
    @Volatile
    private var totalHits = 0
    @Volatile
    private var totalBandwidthSavedBytes = 0L

    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCachedLayerFile(context: Context, modelId: String, layerIndex: Int): File {
        val safeModel = modelId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(getCacheDir(context), "${safeModel}_layer_${layerIndex}.bin")
    }

    /**
     * Resolves existing cached layer file across app internal cache and standalone /data/local/tmp cache.
     */
    fun findCachedLayerFile(context: Context, modelId: String, layerIndex: Int): File? {
        val safeModel = modelId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val candidates = listOf(
            File(getCacheDir(context), "${safeModel}_layer_${layerIndex}.bin"),
            File(getCacheDir(context), "${safeModel}_L${layerIndex}.bin"),
            File(getCacheDir(context), "${safeModel}.layer_${layerIndex}.gguf"),
            File(STANDALONE_CACHE_DIR, "${safeModel}_layer_${layerIndex}.bin"),
            File(STANDALONE_CACHE_DIR, "${safeModel}_L${layerIndex}.bin"),
            File(STANDALONE_CACHE_DIR, "${safeModel}.layer_${layerIndex}.gguf"),
            File(STANDALONE_CACHE_DIR, "layer_${layerIndex}.bin")
        )
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
    }

    fun hasLayerCached(context: Context, modelId: String, layerIndex: Int): Boolean {
        val file = findCachedLayerFile(context, modelId, layerIndex)
        val exists = file != null && file.exists() && file.length() > 0L
        if (exists && file != null) {
            totalHits++
            totalBandwidthSavedBytes += file.length()
        }
        return exists
    }

    /**
     * Verifies layer range [layerStart..layerEnd] on local flash storage before streaming.
     */
    fun checkLayerRangeCached(
        context: Context,
        modelId: String,
        layerStart: Int,
        layerEnd: Int
    ): ShardSpanCacheResult {
        if (layerEnd < layerStart || layerStart < 0) {
            return ShardSpanCacheResult(0, emptyList(), emptyList(), true, 100, 0L)
        }
        val total = layerEnd - layerStart + 1
        val cached = mutableListOf<Int>()
        val missing = mutableListOf<Int>()
        var bytes = 0L

        for (idx in layerStart..layerEnd) {
            val f = findCachedLayerFile(context, modelId, idx)
            if (f != null) {
                cached.add(idx)
                bytes += f.length()
            } else {
                missing.add(idx)
            }
        }

        val allCached = missing.isEmpty()
        val hitRate = if (total > 0) (cached.size * 100) / total else 100
        return ShardSpanCacheResult(total, cached, missing, allCached, hitRate, bytes)
    }

    fun putLayerData(context: Context, modelId: String, layerIndex: Int, data: ByteArray): File {
        val file = getCachedLayerFile(context, modelId, layerIndex)
        FileOutputStream(file).use { fos ->
            fos.write(data)
            fos.flush()
        }
        return file
    }

    fun readLayerData(context: Context, modelId: String, layerIndex: Int): ByteArray? {
        val file = getCachedLayerFile(context, modelId, layerIndex)
        if (!file.exists() || file.length() == 0L) return null
        totalHits++
        totalBandwidthSavedBytes += file.length()
        return FileInputStream(file).use { fis -> fis.readBytes() }
    }

    /**
     * Persists a compact shard manifest header when a model shard is bound locally so
     * reconnects can verify layer alignment before streaming full weight blocks.
     */
    fun writeShardIndexEntry(
        context: Context,
        modelId: String,
        layerStart: Int,
        layerEnd: Int,
        safeCapMb: Long
    ) {
        if (layerEnd < layerStart) return
        val safeModel = modelId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val manifest = File(getCacheDir(context), "${safeModel}_shard_index.meta")
        val content = "model=$modelId\nlayers=$layerStart..$layerEnd\nsafeCapMb=$safeCapMb\nupdated=${System.currentTimeMillis()}\n"
        runCatching { manifest.writeText(content) }
    }

    fun clearCache(context: Context) {
        runCatching {
            getCacheDir(context).deleteRecursively()
            totalHits = 0
            totalBandwidthSavedBytes = 0L
        }
    }

    fun getStats(context: Context, enabled: Boolean = true): TensorCacheStats {
        val dir = getCacheDir(context)
        val allFiles = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile }.toList()
        } else {
            emptyList()
        }
        val totalBytes = allFiles.sumOf { it.length() }
        val totalMb = totalBytes / (1024f * 1024f)
        val shardFiles = allFiles.filter { !it.name.endsWith(".meta") && !it.name.endsWith(".tmp") }
        val savedMb = totalBandwidthSavedBytes / (1024f * 1024f)

        val status = when {
            !enabled -> "Paused (layers stream directly without disk caching)"
            shardFiles.isNotEmpty() -> "Active (${"%.1f".format(totalMb)} MB • ${shardFiles.size} cached shards)"
            allFiles.isNotEmpty() -> "Indexed (${allFiles.size} model shard manifest ready)"
            else -> "Ready (auto-caches layer shards on first run)"
        }

        return TensorCacheStats(
            cachedBytes = totalBytes,
            cachedMb = totalMb,
            layerCount = shardFiles.size,
            hitCount = totalHits,
            bandwidthSavedMb = savedMb,
            isEnabled = enabled,
            statusLabel = status
        )
    }
}
