package com.carlink.logging

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.loggingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_logging_preferences",
)

/** Persists logging preferences across sessions using DataStore. */
@Suppress("StaticFieldLeak")
class LoggingPreferences private constructor(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: LoggingPreferences? = null

        fun getInstance(context: Context): LoggingPreferences =
            instance ?: synchronized(this) {
                instance ?: LoggingPreferences(context.applicationContext).also { instance = it }
            }

        private val KEY_LOG_LEVEL = intPreferencesKey("carlink_log_level")
        private val KEY_LOGGING_ENABLED = booleanPreferencesKey("carlink_logging_enabled")
    }

    private val dataStore = appContext.loggingDataStore

    val logLevelFlow: Flow<LogPreset> =
        dataStore.data.map { preferences ->
            val levelIndex = preferences[KEY_LOG_LEVEL] ?: LogPreset.SILENT.ordinal
            LogPreset.fromIndex(levelIndex)
        }

    val loggingEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_LOGGING_ENABLED] ?: true
        }

    suspend fun setLogLevel(level: LogPreset) {
        dataStore.edit { preferences ->
            preferences[KEY_LOG_LEVEL] = level.ordinal
        }
    }

    suspend fun setLoggingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_LOGGING_ENABLED] = enabled
        }
    }
}
