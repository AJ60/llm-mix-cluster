package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

data class MasterHealthResult(
    val reachable: Boolean,
    val latencyMs: Long = -1L,
    val activeModel: String? = null,
    val activeModelId: String? = null,
    val engineRunning: Boolean = false,
    val activeNodes: Int = 0,
    val detail: String = ""
)

data class RemoteClusterNodeInfo(
    val id: String,
    val label: String,
    val osType: String,
    val backendDriver: String,
    val layerStart: Int,
    val layerEnd: Int,
    val allocatedLayers: Int,
    val allocatedMb: Long,
    val estimatedTokPerSec: Float,
    val isMaster: Boolean
)

data class MasterClusterSnapshot(
    val activeModelId: String,
    val activeModelName: String,
    val hasMmproj: Boolean,
    val engineRunning: Boolean,
    val totalSafePoolMb: Long,
    val neededMb: Long,
    val fitVerdict: String,
    val estimatedClusterTokPerSec: Float,
    val nodes: List<RemoteClusterNodeInfo>
)

data class ChatCompletionMessage(
    val role: String,
    val content: String,
    val imageDataUrl: String? = null
)

/**
 * Live HTTP/SSE client communicating with any OpenAI-compatible LAN/USB Master
 * (`clusterd`, native `llama-server` on `:8080`, or companion Express bridge on `:3000`).
 */
object SilicaMasterApiClient {

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "http://127.0.0.1:3000"
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/v1")) {
            withScheme.removeSuffix("/v1")
        } else {
            withScheme
        }
    }

    suspend fun probeHealth(baseUrl: String, secret: String): MasterHealthResult =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
            val start = System.currentTimeMillis()
            val altPortUrl = when {
                normalized.contains(":8080") -> normalized.replace(":8080", ":3000")
                normalized.contains(":3000") -> normalized.replace(":3000", ":8080")
                else -> "$normalized:3000"
            }
            // Auto-probe port 3000 (ClusterLab Master) and port 8080 (llama-server)
            val candidates = listOf(
                "$normalized/health",
                "$altPortUrl/health",
                "$normalized/v1/models",
                "$altPortUrl/v1/models"
            )
            for (endpoint in candidates) {
                val res = runCatching {
                    val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2000
                        readTimeout = 2500
                        setRequestProperty("Accept", "application/json")
                        if (secret.isNotBlank()) {
                            setRequestProperty("X-Cluster-Secret", secret)
                        }
                    }
                    try {
                        val code = conn.responseCode
                        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader(StandardCharsets.UTF_8)
                            ?.use { it.readText() }
                            .orEmpty()
                        code to body
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull() ?: continue

                val (code, body) = res
                if (code in 200..299) {
                    val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val activeModel = json?.optString("activeModel").orEmpty().ifBlank {
                        json?.optJSONArray("data")?.optJSONObject(0)?.optString("id")
                    }
                    val activeModelId = json?.optString("activeModelId").orEmpty().ifBlank {
                        json?.optJSONArray("data")?.optJSONObject(0)?.optString("id")
                    }
                    val engineRunning = json?.optBoolean("engineRunning", true) ?: true
                    val activeNodes = json?.optInt("activeNodes", 0) ?: 0
                    return@withContext MasterHealthResult(
                        reachable = true,
                        latencyMs = latency,
                        activeModel = activeModel,
                        activeModelId = activeModelId,
                        engineRunning = engineRunning,
                        activeNodes = activeNodes,
                        detail = if (engineRunning) "Engine Online ($latency ms)" else "Coordinator Ready · Engine Offline ($latency ms)"
                    )
                }
            }
            MasterHealthResult(
                reachable = false,
                detail = "Unreachable at $normalized"
            )
        }

    suspend fun fetchModels(baseUrl: String, secret: String): List<String> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
            val altUrl = when {
                normalized.contains(":8080") -> normalized.replace(":8080", ":3000")
                normalized.contains(":3000") -> normalized.replace(":3000", ":8080")
                else -> "$normalized:3000"
            }
            val urls = listOf("$normalized/v1/models", "$altUrl/v1/models").distinct()
            for (targetUrl in urls) {
                val list = runCatching {
                    val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2500
                        readTimeout = 3000
                        setRequestProperty("Accept", "application/json")
                        if (secret.isNotBlank()) {
                            setRequestProperty("X-Cluster-Secret", secret)
                        }
                    }
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching emptyList()
                        val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                        val json = JSONObject(text)
                        val data = json.optJSONArray("data") ?: return@runCatching emptyList()
                        buildList {
                            for (i in 0 until data.length()) {
                                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                                if (id.isNotBlank()) add(id)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
                if (!list.isNullOrEmpty()) return@withContext list
            }
            emptyList()
        }

    suspend fun fetchClusterState(baseUrl: String, secret: String): MasterClusterSnapshot? =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
            val altUrl = when {
                normalized.contains(":8080") -> normalized.replace(":8080", ":3000")
                normalized.contains(":3000") -> normalized.replace(":3000", ":8080")
                else -> "$normalized:3000"
            }
            val urls = listOf("$normalized/api/cluster/state", "$altUrl/api/cluster/state")
            for (targetUrl in urls) {
                val snapshot = runCatching {
                    val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 2500
                        readTimeout = 3000
                        setRequestProperty("Accept", "application/json")
                        if (secret.isNotBlank()) {
                            setRequestProperty("X-Cluster-Secret", secret)
                        }
                    }
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                        val root = JSONObject(text)
                        val activeModelObj = root.optJSONObject("activeModel")
                        val modelId = activeModelObj?.optString("id").orEmpty()
                        val modelName = activeModelObj?.optString("name").orEmpty()
                            .ifBlank { modelId }
                        val engineStatusObj = root.optJSONObject("engineStatus")
                        val hasMmproj = engineStatusObj?.optBoolean("hasMmproj", false) == true ||
                            !activeModelObj?.optString("mmprojPath").isNullOrBlank()
                        val engineRunning = engineStatusObj?.optBoolean("running", false) == true
                        val plan = root.optJSONObject("plan") ?: return@runCatching null
                        val nodesArr = plan.optJSONArray("nodes") ?: JSONArray()
                        val parsedNodes = buildList {
                            for (i in 0 until nodesArr.length()) {
                                val n = nodesArr.optJSONObject(i) ?: continue
                                val role = n.optString("role")
                                val isMasterNode = n.optBoolean("isMaster", role.equals("Master", ignoreCase = true) || i == 0)
                                val nodeId = n.optString("nodeId").ifBlank { n.optString("id", "node_$i") }
                                add(
                                    RemoteClusterNodeInfo(
                                        id = nodeId,
                                        label = n.optString("label", "Cluster Node ${i + 1}"),
                                        osType = n.optString("osType", "LAN"),
                                        backendDriver = n.optString("backendDriver", "CPU/RPC"),
                                        layerStart = n.optInt("layerStart", 0),
                                        layerEnd = n.optInt("layerEnd", 0),
                                        allocatedLayers = n.optInt("allocatedLayers", 0),
                                        allocatedMb = n.optLong("allocatedMb", 0L),
                                        estimatedTokPerSec = n.optDouble("estimatedTokPerSec", 0.0).toFloat(),
                                        isMaster = isMasterNode
                                    )
                                )
                            }
                        }
                        MasterClusterSnapshot(
                            activeModelId = modelId.ifBlank { modelName },
                            activeModelName = modelName,
                            hasMmproj = hasMmproj,
                            engineRunning = engineRunning,
                            totalSafePoolMb = plan.optLong("totalSafePoolMb", 0L),
                            neededMb = plan.optLong("neededMb", 0L),
                            fitVerdict = plan.optString("fitVerdict", "READY"),
                            estimatedClusterTokPerSec = plan.optDouble("estimatedClusterTokPerSec", 0.0).toFloat(),
                            nodes = parsedNodes
                        )
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()

                if (snapshot != null) return@withContext snapshot
            }
            null
        }

    suspend fun activateModelOnMaster(baseUrl: String, modelId: String, secret: String): Boolean =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
            val altUrl = when {
                normalized.contains(":8080") -> normalized.replace(":8080", ":3000")
                normalized.contains(":3000") -> normalized.replace(":3000", ":8080")
                else -> "$normalized:3000"
            }
            val urls = listOf("$normalized/api/models/activate", "$altUrl/api/models/activate").distinct()
            val payload = JSONObject().apply {
                put("modelId", modelId)
            }.toString().toByteArray(StandardCharsets.UTF_8)

            for (targetUrl in urls) {
                val ok = runCatching {
                    val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 3000
                        readTimeout = 4000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        if (secret.isNotBlank()) {
                            setRequestProperty("X-Cluster-Secret", secret)
                        }
                    }
                    try {
                        conn.outputStream.use { it.write(payload) }
                        conn.responseCode in 200..299
                    } finally {
                        conn.disconnect()
                    }
                }.getOrDefault(false)
                if (ok) return@withContext true
            }
            false
        }

    suspend fun probePeerTcp(address: String, timeoutMs: Int = 900): Long? =
        withContext(Dispatchers.IO) {
            val host = address.substringBefore(":").trim()
            val port = address.substringAfter(":", "50052").toIntOrNull() ?: 50052
            if (host.isBlank() || port !in 1..65535) return@withContext null
            val start = System.currentTimeMillis()
            runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), timeoutMs)
                    (System.currentTimeMillis() - start).coerceAtLeast(1L)
                }
            }.getOrNull()
        }

    /**
     * Streams tokens from POST `<baseUrl>/v1/chat/completions` (OpenAI SSE stream with graceful
     * fallback to standard JSON completion if the upstream server replies non-streamed).
     */
    suspend fun streamChatCompletion(
        baseUrl: String,
        modelId: String,
        messages: List<ChatCompletionMessage>,
        temperature: Float,
        systemPrompt: String,
        secret: String,
        webToolsEnabled: Boolean = false,
        onDelta: suspend (accumulatedText: String, tokenEstimate: Int, tokPerSec: Float) -> Unit
    ): Result<Triple<String, Float, Long>> = withContext(Dispatchers.IO) {
        val candidates = buildList {
            val norm = normalizeBaseUrl(baseUrl)
            add(norm)
            if (norm.contains(":8080")) {
                add(norm.replace(":8080", ":3000"))
            } else if (norm.contains(":3000")) {
                add(norm.replace(":3000", ":8080"))
            } else {
                add("$norm:3000")
                add("$norm:8080")
            }
        }.distinct()

        var lastError: Exception? = null

        for (candidateBase in candidates) {
            val startMs = System.currentTimeMillis()
            var conn: HttpURLConnection? = null

            try {
                val url = URL("$candidateBase/v1/chat/completions")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4500
                    readTimeout = 120_000 // Long prefill tolerance alongside 10s SSE keepalives
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream, application/json")
                    if (secret.isNotBlank()) {
                        setRequestProperty("X-Cluster-Secret", secret)
                        setRequestProperty("Authorization", "Bearer $secret")
                    }
                }

                val msgArray = JSONArray()
                if (systemPrompt.isNotBlank()) {
                    msgArray.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                messages.forEach { msg ->
                    val obj = JSONObject().apply { put("role", msg.role) }
                    if (!msg.imageDataUrl.isNullOrBlank()) {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", msg.content.ifBlank { "Describe this image." })
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply { put("url", msg.imageDataUrl) })
                            })
                        }
                        obj.put("content", parts)
                    } else {
                        obj.put("content", msg.content)
                    }
                    msgArray.put(obj)
                }

                val payload = JSONObject().apply {
                    put("model", modelId)
                    put("stream", true)
                    put("temperature", temperature.toDouble())
                    put("messages", msgArray)
                    if (webToolsEnabled) {
                        val toolsArr = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", "web_search")
                                    put("description", "Search public web knowledge")
                                    put("parameters", JSONObject().apply {
                                        put("type", "object")
                                        put("properties", JSONObject().apply {
                                            put("query", JSONObject().apply { put("type", "string") })
                                        })
                                        put("required", JSONArray().put("query"))
                                    })
                                })
                            })
                        }
                        put("tools", toolsArr)
                        put("tool_choice", "auto")
                    }
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }

                val code = conn.responseCode
                if (code == 503) {
                    val errBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val msg = runCatching {
                        JSONObject(errBody).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException(
                            "HTTP 503 ENGINE_OFFLINE: ${msg.ifBlank { "No live inference engine loaded on Master. Load a GGUF model or connect llama-server / Ollama on Desktop." }}"
                        )
                    )
                }
                if (code !in 200..299) {
                    val errBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("Master HTTP $code at $candidateBase: ${errBody.take(180).ifBlank { "Request failed" }}")
                }

                val contentType = conn.contentType.orEmpty().lowercase()
                val reasoningBuf = StringBuilder()
                val answerBuf = StringBuilder()
                var chunkCount = 0

                fun buildCombinedDisplay(): String {
                    val r = reasoningBuf.toString().trim()
                    val a = answerBuf.toString()
                    return if (r.isNotEmpty()) "<think>\n$r\n</think>\n$a" else a
                }

                if (contentType.contains("text/event-stream")) {
                    BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        while (currentCoroutineContext().isActive) {
                            val line = reader.readLine() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue // SSE keepalive heartbeat
                            if (!trimmed.startsWith("data:")) continue
                            val dataStr = trimmed.removePrefix("data:").trim()
                            if (dataStr == "[DONE]") break
                            val json = runCatching { JSONObject(dataStr) }.getOrNull() ?: continue
                            val choices = json.optJSONArray("choices") ?: continue
                            val choice0 = choices.optJSONObject(0) ?: continue
                            val deltaObj = choice0.optJSONObject("delta")
                            val rDelta = deltaObj?.optString("reasoning_content").orEmpty()
                            val cDelta = deltaObj?.optString("content").orEmpty().ifEmpty {
                                choice0.optJSONObject("message")?.optString("content").orEmpty()
                            }
                            if (rDelta.isNotEmpty() || cDelta.isNotEmpty()) {
                                if (rDelta.isNotEmpty()) reasoningBuf.append(rDelta)
                                if (cDelta.isNotEmpty()) answerBuf.append(cDelta)
                                chunkCount++
                                val combined = buildCombinedDisplay()
                                val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(50L)) / 1000f
                                val estTokens = (combined.length / 4).coerceAtLeast(chunkCount)
                                val tps = estTokens / elapsedSec
                                onDelta(combined, estTokens, tps)
                            }
                        }
                    }
                } else {
                    val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val json = JSONObject(body)
                    val msgObj = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    val rText = msgObj?.optString("reasoning_content").orEmpty()
                    val cText = msgObj?.optString("content").orEmpty()
                    if (rText.isNotEmpty()) reasoningBuf.append(rText)
                    if (cText.isNotEmpty()) answerBuf.append(cText)
                    val combined = buildCombinedDisplay()
                    val elapsedSec = ((System.currentTimeMillis() - startMs).coerceAtLeast(50L)) / 1000f
                    val estTokens = (combined.length / 4).coerceAtLeast(1)
                    val tps = estTokens / elapsedSec
                    onDelta(combined, estTokens, tps)
                }

                val combinedFinal = buildCombinedDisplay()
                val totalMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val finalTokens = (combinedFinal.length / 4).coerceAtLeast(chunkCount.coerceAtLeast(1))
                val finalTps = finalTokens / (totalMs / 1000f)
                return@withContext Result.success(Triple(combinedFinal, finalTps, totalMs))
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }

        Result.failure(lastError ?: IllegalStateException("Unable to reach master endpoint"))
    }
}
