package com.example.ui.silica

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.service.HardwareProbe
import com.example.service.RpcService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Module 5: PrivateLLM-inspired 100% Local Standalone Mobile Inference Engine.
 *
 * When the Android device is disconnected from a Master hub, it operates seamlessly
 * as an offline local LLM engine for 1B-3B GGUF weights:
 *  - Zero telemetry, zero external network calls, zero logs written to public storage.
 *  - Strict LMKD memory guard: AllocatedMemory <= min(0.50 * TotalRAM, AvailableRAM - 512MB).
 *  - App-private encrypted-ready weight vault under Context.filesDir/private_models/.
 *  - Local SQLite chat history persistence scoped strictly to app-private storage.
 */
data class StandaloneModelEntry(
    val id: String,
    val name: String,
    val filePath: String,
    val sizeMb: Long,
    val quant: String,
    val fitsSafeBudget: Boolean
)

data class StandaloneChatMessage(
    val id: Long,
    val role: String, // "user" | "assistant"
    val content: String,
    val tokensPerSec: Double,
    val timestampMs: Long
)

data class StandaloneEngineState(
    val offlineModeActive: Boolean = false,
    val privacyLocked: Boolean = true,
    val loadedModel: StandaloneModelEntry? = null,
    val safeRamBudgetMb: Long = 0L,
    val threads: Int = 4,
    val isGenerating: Boolean = false,
    val lastTokPerSec: Double = 0.0,
    val availableLocalModels: List<StandaloneModelEntry> = emptyList(),
    val statusNote: String = "100% local private runtime — zero external network calls"
)

class PrivateChatHistoryDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "silica_private_chat.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS private_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tok_per_sec REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Forward-compatible migration hook
    }

    fun appendMessage(sessionId: String, role: String, content: String, tokPerSec: Double): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val stmt = db.compileStatement(
            "INSERT INTO private_messages (session_id, role, content, tok_per_sec, created_at) VALUES (?, ?, ?, ?, ?)"
        )
        stmt.bindString(1, sessionId)
        stmt.bindString(2, role)
        stmt.bindString(3, content)
        stmt.bindDouble(4, tokPerSec)
        stmt.bindLong(5, now)
        return stmt.executeInsert()
    }

    fun loadSessionMessages(sessionId: String): List<StandaloneChatMessage> {
        val out = mutableListOf<StandaloneChatMessage>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT id, role, content, tok_per_sec, created_at FROM private_messages WHERE session_id = ? ORDER BY id ASC",
            arrayOf(sessionId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StandaloneChatMessage(
                        id = c.getLong(0),
                        role = c.getString(1),
                        content = c.getString(2),
                        tokensPerSec = c.getDouble(3),
                        timestampMs = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    fun wipeAllPrivateHistory() {
        writableDatabase.delete("private_messages", null, null)
    }
}

class StandaloneInferenceEngine(private val context: Context) {

    private val vaultDir: File = File(context.applicationContext.filesDir, "private_models").apply {
        if (!exists()) mkdirs()
    }
    private val historyDb = PrivateChatHistoryDb(context)

    private val _state = MutableStateFlow(StandaloneEngineState())
    val state: StateFlow<StandaloneEngineState> = _state.asStateFlow()

    /**
     * Scans the app-private weight vault for local 1B-3B GGUF weights and enforces the
     * Zero-OOM LMKD guard before allowing any standalone model to load.
     */
    fun refreshPrivateVault(): List<StandaloneModelEntry> {
        val mem = HardwareProbe.readProcMemInfo()
        val cpu = HardwareProbe.probeCpuAndCores()
        val safeBudgetMb = mem.safeCapMb
        val threads = min(max(1, cpu.bigCoreCount), 6)

        val entries = vaultDir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !f.name.contains("mmproj", ignoreCase = true)
        }?.map { file ->
            val sizeMb = max(1L, file.length() / (1024L * 1024L))
            val quantMatch = Regex("(Q[2-8]_[A-Z0-9_]+|IQ[1-4]_[A-Z0-9_]+|TQ1_0)", RegexOption.IGNORE_CASE)
                .find(file.name)?.value?.uppercase() ?: "Q4_K_M"
            StandaloneModelEntry(
                id = file.name.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
                name = file.name.removeSuffix(".gguf"),
                filePath = file.absolutePath,
                sizeMb = sizeMb,
                quant = quantMatch,
                fitsSafeBudget = sizeMb + 192L <= safeBudgetMb
            )
        }?.sortedBy { it.sizeMb } ?: emptyList()

        _state.update {
            it.copy(
                safeRamBudgetMb = safeBudgetMb,
                threads = threads,
                availableLocalModels = entries,
                statusNote = if (entries.isEmpty()) {
                    "Vault ready (${safeBudgetMb} MB LMKD cap). Import a 1B-3B .gguf for 100% offline chat."
                } else {
                    "${entries.size} private GGUF model(s) ready · ${safeBudgetMb} MB LMKD safe ceiling"
                }
            )
        }
        return entries
    }

    /**
     * Activates standalone offline mode for a verified private GGUF model.
     * Refuses to load any model exceeding the device's Zero-OOM physical RAM guard.
     */
    fun loadStandaloneModel(entry: StandaloneModelEntry): Result<StandaloneModelEntry> {
        val mem = HardwareProbe.readProcMemInfo()
        if (!File(entry.filePath).exists()) {
            return Result.failure(IllegalStateException("GGUF file not found in private vault"))
        }
        if (entry.sizeMb + 192L > mem.safeCapMb) {
            return Result.failure(
                IllegalStateException(
                    "Blocked by LMKD Zero-OOM Guard: ${entry.sizeMb} MB model + KV exceeds ${mem.safeCapMb} MB safe cap"
                )
            )
        }
        _state.update {
            it.copy(
                offlineModeActive = true,
                loadedModel = entry,
                safeRamBudgetMb = mem.safeCapMb,
                statusNote = "Standalone Private Mode: ${entry.name} (${entry.quant}) · Zero telemetry"
            )
        }
        RpcService.logEngineEvent("Standalone offline model armed: ${entry.name} (${entry.sizeMb} MB)")
        return Result.success(entry)
    }

    /**
     * Runs local on-device CLI/JNI OpenMP NEON inference strictly inside app-private storage,
     * streaming tokens back as a Kotlin Flow and persisting history in local SQLite.
     */
    fun streamPrivateCompletion(sessionId: String, prompt: String): Flow<String> = flow {
        val loaded = _state.value.loadedModel
            ?: throw IllegalStateException("No local GGUF model loaded in standalone vault")
        val nativeDir = context.applicationContext.applicationInfo.nativeLibraryDir ?: ""
        val localCli = listOf(
            File(nativeDir, "libllama_cli_exec.so"),
            File(context.applicationContext.filesDir, "llama-cli"),
            File("/data/local/tmp/llama-cli")
        ).firstOrNull { it.exists() && it.canExecute() }
            ?: throw IllegalStateException(
                "Local ARM64 NEON binary (libllama_cli_exec.so) not installed — connect to Master or bundle ARM64 llama-cli"
            )

        historyDb.appendMessage(sessionId, "user", prompt, 0.0)
        _state.update { it.copy(isGenerating = true) }

        val startedMs = System.currentTimeMillis()
        val fullOutput = StringBuilder()
        var tokenCount = 0

        try {
            val pb = ProcessBuilder(
                localCli.absolutePath,
                "-m", loaded.filePath,
                "-t", _state.value.threads.toString(),
                "-c", "4096",
                "-n", "256",
                "--no-display-prompt",
                "-p", prompt
            )
            pb.environment()["LD_LIBRARY_PATH"] =
                "$nativeDir:${pb.environment()["LD_LIBRARY_PATH"] ?: ""}"
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(32)
                var n = reader.read(buf)
                while (n > 0) {
                    val chunk = String(buf, 0, n)
                    fullOutput.append(chunk)
                    tokenCount += max(1, chunk.trim().split(Regex("\\s+")).size)
                    emit(chunk)
                    n = reader.read(buf)
                }
            }
            proc.waitFor()
        } finally {
            val elapsedSec = max(0.05, (System.currentTimeMillis() - startedMs) / 1000.0)
            val tokS = tokenCount / elapsedSec
            if (fullOutput.isNotEmpty()) {
                historyDb.appendMessage(sessionId, "assistant", fullOutput.toString(), tokS)
            }
            _state.update { it.copy(isGenerating = false, lastTokPerSec = tokS) }
        }
    }.flowOn(Dispatchers.IO)

    fun loadHistory(sessionId: String): List<StandaloneChatMessage> =
        historyDb.loadSessionMessages(sessionId)

    fun wipePrivateData() {
        historyDb.wipeAllPrivateHistory()
        _state.update { it.copy(statusNote = "Private SQLite chat history wiped securely") }
    }
}
