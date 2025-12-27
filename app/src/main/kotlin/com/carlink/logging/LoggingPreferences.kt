package com.carlink.logging

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore instance for logging preferences.
 * Following Android best practices: singleton instance at top level.
 */
private val Context.loggingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_logging_preferences",
)

/**
 * Manages persistent storage of logging preferences.
 *
 * Stores user's logging preferences across app sessions using DataStore.
 * Handles log level, enabled state, and user's explicit actions.
 *
 * Matches Flutter: logging_preferences.dart
 */
@Suppress("StaticFieldLeak") // Uses applicationContext, not Activity context - no leak
class LoggingPreferences private constructor(
    context: Context,
) {
    // Store applicationContext to avoid Activity leaks
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: LoggingPreferences? = null

        fun getInstance(context: Context): LoggingPreferences =
            instance ?: synchronized(this) {
                instance ?: LoggingPreferences(context.applicationContext).also { instance = it }
            }

        // Preference keys matching Flutter
        private val KEY_LOG_LEVEL = intPreferencesKey("carlink_log_level")
        private val KEY_LOGGING_ENABLED = booleanPreferencesKey("carlink_logging_enabled")
        private val KEY_USER_HAS_DISABLED = booleanPreferencesKey("carlink_user_has_disabled")
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("carlink_first_launch")
        private val KEY_LAST_USER_ACTION = longPreferencesKey("carlink_last_user_action")
    }

    private val dataStore = appContext.loggingDataStore

    /**
     * Get the saved log level as Flow.
     */
    val logLevelFlow: Flow<LogPreset> =
        dataStore.data.map { preferences ->
            val levelIndex = preferences[KEY_LOG_LEVEL] ?: LogPreset.SILENT.ordinal
            LogPreset.fromIndex(levelIndex)
        }

    /**
     * Get whether logging is enabled as Flow.
     */
    val loggingEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_LOGGING_ENABLED] ?: true // Default to enabled
        }

    /**
     * Get the saved log level, defaults to Silent.
     */
    suspend fun getLogLevel(): LogPreset {
        val preferences = dataStore.data.first()
        val levelIndex = preferences[KEY_LOG_LEVEL] ?: LogPreset.SILENT.ordinal
        return LogPreset.fromIndex(levelIndex)
    }

    /**
     * Save the user's preferred log level.
     */
    suspend fun setLogLevel(level: LogPreset) {
        dataStore.edit { preferences ->
            preferences[KEY_LOG_LEVEL] = level.ordinal
        }
        recordUserAction()
    }

    /**
     * Get whether logging is enabled.
     */
    suspend fun isLoggingEnabled(): Boolean {
        val preferences = dataStore.data.first()
        return preferences[KEY_LOGGING_ENABLED] ?: true // Default to enabled
    }

    /**
     * Set whether logging is enabled.
     */
    suspend fun setLoggingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_LOGGING_ENABLED] = enabled
        }
        recordUserAction()
    }

    /**
     * Get whether user has explicitly disabled logging.
     */
    suspend fun hasUserExplicitlyDisabled(): Boolean {
        val preferences = dataStore.data.first()
        return preferences[KEY_USER_HAS_DISABLED] ?: false
    }

    /**
     * Set that user has explicitly disabled logging.
     */
    suspend fun setUserHasExplicitlyDisabled(disabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_USER_HAS_DISABLED] = disabled
        }
        if (disabled) {
            recordUserAction()
        }
    }

    /**
     * Check if this is the first app launch.
     */
    suspend fun isFirstLaunch(): Boolean {
        val preferences = dataStore.data.first()
        val isFirst = preferences[KEY_FIRST_LAUNCH] ?: true

        // Mark as not first launch after checking
        if (isFirst) {
            dataStore.edit { prefs ->
                prefs[KEY_FIRST_LAUNCH] = false
            }
        }

        return isFirst
    }

    /**
     * Get the timestamp of the last user action.
     */
    suspend fun getLastUserAction(): Long? {
        val preferences = dataStore.data.first()
        return preferences[KEY_LAST_USER_ACTION]
    }

    /**
     * Record the current time as the last user action.
     */
    private suspend fun recordUserAction() {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_USER_ACTION] = System.currentTimeMillis()
        }
    }

    /**
     * Check if user has made any logging configuration within the last session.
     */
    suspend fun hasRecentUserConfiguration(): Boolean {
        val lastAction = getLastUserAction() ?: return false
        // Consider actions within the last 24 hours as "recent"
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        return lastAction > cutoff
    }

    /**
     * Get a summary of current preferences for debugging.
     */
    suspend fun getPreferencesSummary(): Map<String, Any?> {
        val preferences = dataStore.data.first()
        return mapOf(
            "logLevel" to getLogLevel().name,
            "loggingEnabled" to isLoggingEnabled(),
            "userHasDisabled" to hasUserExplicitlyDisabled(),
            "isFirstLaunch" to (preferences[KEY_FIRST_LAUNCH] ?: true),
            "lastUserAction" to getLastUserAction(),
            "hasRecentConfig" to hasRecentUserConfiguration(),
        )
    }

    /**
     * Reset all preferences to defaults (for testing).
     */
    suspend fun resetToDefaults() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_LOG_LEVEL)
            preferences.remove(KEY_LOGGING_ENABLED)
            preferences.remove(KEY_USER_HAS_DISABLED)
            preferences.remove(KEY_FIRST_LAUNCH)
            preferences.remove(KEY_LAST_USER_ACTION)
        }
    }

    /**
     * Apply saved preferences to the logging system.
     *
     * This method should be called on app startup to restore user's settings.
     */
    suspend fun applySavedPreferences() {
        try {
            val level = getLogLevel()
            val enabled = isLoggingEnabled()

            // Apply the saved log level
            level.apply()

            // Apply the saved logging state if available
            if (!enabled) {
                FileLogManager.getInstance(appContext).disable()
            } else {
                FileLogManager.getInstance(appContext).enable()
            }

            val summary = getPreferencesSummary()
            logInfo("Applied saved preferences: $summary", tag = Logger.Tags.FILE_LOG)
        } catch (e: Exception) {
            logError("Failed to apply saved preferences: $e", tag = Logger.Tags.FILE_LOG)
        }
    }
}
