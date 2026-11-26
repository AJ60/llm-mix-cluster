package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.silicaPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "silica_cluster_engine_prefs"
)

data class SilicaEngineConfig(
    val nodeRole: String = "WORKER", // "WORKER" | "MASTER_HUB"
    val selectedModelId: String = "small-1.5b",
    val localGgufUri: String = "",
    val localGgufName: String = "",
    val threadCountOverride: Int = 0, // 0 = auto-calibrated big cores
    val safeRamPercent: Int = 50, // bounded 25..70% for physical RAM ceiling
    val contextLength: Int = 4096,
    val kvCacheQuant: String = "Q8_0", // "F16" | "Q8_0" | "Q4_0"
    val usbBridgeOnly: Boolean = true,
    val masterBaseUrl: String = "http://127.0.0.1:3000",
    val clusterSecret: String = "clusterd-default-hmac-key",
    val tensorCacheEnabled: Boolean = true,
    val temperature: Float = 0.7f,
    val systemPrompt: String = "You are a concise, helpful local AI running across a decentralized Silica Cluster.",
    val darkTheme: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val webToolsEnabled: Boolean = false
)

class SilicaPreferencesRepository(private val context: Context) {

    private object Keys {
        val NODE_ROLE = stringPreferencesKey("node_role")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val LOCAL_GGUF_URI = stringPreferencesKey("local_gguf_uri")
        val LOCAL_GGUF_NAME = stringPreferencesKey("local_gguf_name")
        val THREAD_OVERRIDE = intPreferencesKey("thread_count_override")
        val SAFE_RAM_PERCENT = intPreferencesKey("safe_ram_percent")
        val CONTEXT_LENGTH = intPreferencesKey("context_length")
        val KV_CACHE_QUANT = stringPreferencesKey("kv_cache_quant")
        val USB_BRIDGE_ONLY = booleanPreferencesKey("usb_bridge_only")
        val MASTER_BASE_URL = stringPreferencesKey("master_base_url")
        val CLUSTER_SECRET = stringPreferencesKey("cluster_secret")
        val TENSOR_CACHE_ENABLED = booleanPreferencesKey("tensor_cache_enabled")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val WEB_TOOLS_ENABLED = booleanPreferencesKey("web_tools_enabled")
    }

    val configFlow: Flow<SilicaEngineConfig> = context.silicaPrefsDataStore.data.map { prefs ->
        SilicaEngineConfig(
            nodeRole = prefs[Keys.NODE_ROLE] ?: "WORKER",
            selectedModelId = prefs[Keys.SELECTED_MODEL_ID] ?: "small-1.5b",
            localGgufUri = prefs[Keys.LOCAL_GGUF_URI] ?: "",
            localGgufName = prefs[Keys.LOCAL_GGUF_NAME] ?: "",
            threadCountOverride = prefs[Keys.THREAD_OVERRIDE] ?: 0,
            safeRamPercent = (prefs[Keys.SAFE_RAM_PERCENT] ?: 45).coerceIn(25, 50),
            contextLength = prefs[Keys.CONTEXT_LENGTH] ?: 4096,
            kvCacheQuant = prefs[Keys.KV_CACHE_QUANT] ?: "Q8_0",
            usbBridgeOnly = prefs[Keys.USB_BRIDGE_ONLY] ?: true,
            masterBaseUrl = prefs[Keys.MASTER_BASE_URL] ?: "http://127.0.0.1:3000",
            clusterSecret = prefs[Keys.CLUSTER_SECRET] ?: "clusterd-default-hmac-key",
            tensorCacheEnabled = prefs[Keys.TENSOR_CACHE_ENABLED] ?: true,
            temperature = (prefs[Keys.TEMPERATURE] ?: 0.7f).coerceIn(0f, 1.5f),
            systemPrompt = prefs[Keys.SYSTEM_PROMPT]
                ?: "You are a concise, helpful local AI running across a decentralized Silica Cluster.",
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            webToolsEnabled = prefs[Keys.WEB_TOOLS_ENABLED] ?: false
        )
    }

    suspend fun updateConfig(transform: (SilicaEngineConfig) -> SilicaEngineConfig) {
        context.silicaPrefsDataStore.edit { prefs ->
            val current = SilicaEngineConfig(
                nodeRole = prefs[Keys.NODE_ROLE] ?: "WORKER",
                selectedModelId = prefs[Keys.SELECTED_MODEL_ID] ?: "small-1.5b",
                localGgufUri = prefs[Keys.LOCAL_GGUF_URI] ?: "",
                localGgufName = prefs[Keys.LOCAL_GGUF_NAME] ?: "",
                threadCountOverride = prefs[Keys.THREAD_OVERRIDE] ?: 0,
                safeRamPercent = (prefs[Keys.SAFE_RAM_PERCENT] ?: 45).coerceIn(25, 50),
                contextLength = prefs[Keys.CONTEXT_LENGTH] ?: 4096,
                kvCacheQuant = prefs[Keys.KV_CACHE_QUANT] ?: "Q8_0",
                usbBridgeOnly = prefs[Keys.USB_BRIDGE_ONLY] ?: true,
                masterBaseUrl = prefs[Keys.MASTER_BASE_URL] ?: "http://127.0.0.1:3000",
                clusterSecret = prefs[Keys.CLUSTER_SECRET] ?: "clusterd-default-hmac-key",
                tensorCacheEnabled = prefs[Keys.TENSOR_CACHE_ENABLED] ?: true,
                temperature = (prefs[Keys.TEMPERATURE] ?: 0.7f).coerceIn(0f, 1.5f),
                systemPrompt = prefs[Keys.SYSTEM_PROMPT]
                    ?: "You are a concise, helpful local AI running across a decentralized Silica Cluster.",
                darkTheme = prefs[Keys.DARK_THEME] ?: true,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
                webToolsEnabled = prefs[Keys.WEB_TOOLS_ENABLED] ?: false
            )
            val next = transform(current)
            prefs[Keys.NODE_ROLE] = next.nodeRole
            prefs[Keys.SELECTED_MODEL_ID] = next.selectedModelId
            prefs[Keys.LOCAL_GGUF_URI] = next.localGgufUri
            prefs[Keys.LOCAL_GGUF_NAME] = next.localGgufName
            prefs[Keys.THREAD_OVERRIDE] = next.threadCountOverride.coerceAtLeast(0)
            prefs[Keys.SAFE_RAM_PERCENT] = next.safeRamPercent.coerceIn(25, 50)
            prefs[Keys.CONTEXT_LENGTH] = next.contextLength
            prefs[Keys.KV_CACHE_QUANT] = next.kvCacheQuant
            prefs[Keys.USB_BRIDGE_ONLY] = next.usbBridgeOnly
            prefs[Keys.MASTER_BASE_URL] = next.masterBaseUrl.trim().trimEnd('/')
            prefs[Keys.CLUSTER_SECRET] = next.clusterSecret
            prefs[Keys.TENSOR_CACHE_ENABLED] = next.tensorCacheEnabled
            prefs[Keys.TEMPERATURE] = next.temperature.coerceIn(0f, 1.5f)
            prefs[Keys.SYSTEM_PROMPT] = next.systemPrompt
            prefs[Keys.DARK_THEME] = next.darkTheme
            prefs[Keys.ONBOARDING_COMPLETED] = next.onboardingCompleted
            prefs[Keys.WEB_TOOLS_ENABLED] = next.webToolsEnabled
        }
    }
}
