package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.studioSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clusterlab_studio_settings"
)

data class StudioDisplayPreferences(
    val isDarkTheme: Boolean = true,
    val showGrid: Boolean = true,
    val showSpiderLines: Boolean = true,
    val developerModeEnabled: Boolean = false,
    val hasCompletedOnboarding: Boolean = false
)

/**
 * Persists client-only appearance, visual-comfort, Developer Mode, and first-launch
 * Coach-Mark onboarding completion preferences using Jetpack Preferences DataStore.
 */
class ThemePreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.studioSettingsDataStore)

    private object Keys {
        val DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val SHOW_SPIDER_LINES = booleanPreferencesKey("show_spider_lines")
        val DEVELOPER_MODE_ENABLED = booleanPreferencesKey("developer_mode_enabled")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
    }

    val preferencesFlow: Flow<StudioDisplayPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            StudioDisplayPreferences(
                isDarkTheme = prefs[Keys.DARK_MODE_ENABLED] ?: true,
                showGrid = prefs[Keys.SHOW_GRID] ?: true,
                showSpiderLines = prefs[Keys.SHOW_SPIDER_LINES] ?: true,
                developerModeEnabled = prefs[Keys.DEVELOPER_MODE_ENABLED] ?: false,
                hasCompletedOnboarding = prefs[Keys.HAS_COMPLETED_ONBOARDING] ?: false
            )
        }

    val isDarkThemeFlow: Flow<Boolean> = preferencesFlow.map { it.isDarkTheme }
    val developerModeFlow: Flow<Boolean> = preferencesFlow.map { it.developerModeEnabled }
    val hasCompletedOnboardingFlow: Flow<Boolean> = preferencesFlow.map { it.hasCompletedOnboarding }

    suspend fun setDarkTheme(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DARK_MODE_ENABLED] = enabled
        }
    }

    suspend fun setShowGrid(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_GRID] = show
        }
    }

    suspend fun setShowSpiderLines(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.SHOW_SPIDER_LINES] = show
        }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.DEVELOPER_MODE_ENABLED] = enabled
        }
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.HAS_COMPLETED_ONBOARDING] = completed
        }
    }
}
