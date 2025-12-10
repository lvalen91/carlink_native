package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore instance for adapter configuration preferences.
 * Following Android best practices: singleton instance at top level.
 */
private val Context.adapterConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_adapter_config_preferences",
)

/**
 * Audio source configuration for adapter initialization.
 */
enum class AudioSourceConfig {
    /**
     * Not configured - adapter retains its current setting.
     * No AUDIO_TRANSFER command will be sent during initialization.
     */
    NOT_CONFIGURED,

    /**
     * Bluetooth mode - Audio plays through phone's Bluetooth to car stereo.
     * Sends AUDIO_TRANSFER_ON during initialization.
     */
    BLUETOOTH,

    /**
     * Adapter mode - Audio streams through USB to be played by this app.
     * Sends AUDIO_TRANSFER_OFF during initialization.
     */
    ADAPTER,
    ;

    companion object {
        val DEFAULT = NOT_CONFIGURED
    }
}

/**
 * Manages persistent storage of adapter configuration preferences.
 *
 * Overview:
 * Stores user's adapter configuration preferences that are applied during
 * adapter initialization. Changes take effect on the next connection/restart.
 *
 * Design Philosophy:
 * - Only configured options are sent to the adapter
 * - NOT_CONFIGURED means the adapter retains its current setting
 * - Adapter firmware retains most settings through power cycles
 *
 * Storage:
 * - Uses DataStore Preferences for persistence
 * - Preferences survive app restarts
 * - Applied during AdapterConfig creation in MainActivity
 *
 * Extensibility:
 * - New configuration options can be added by:
 *   1. Adding a preference key
 *   2. Adding getter/setter methods
 *   3. Adding Flow for UI observation
 *   4. Updating applyToConfig() method
 */
@Suppress("StaticFieldLeak") // Uses applicationContext, not Activity context - no leak
class AdapterConfigPreference private constructor(
    context: Context,
) {
    // Store applicationContext to avoid Activity leaks
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var INSTANCE: AdapterConfigPreference? = null

        fun getInstance(context: Context): AdapterConfigPreference =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdapterConfigPreference(context.applicationContext).also { INSTANCE = it }
            }

        // Preference keys
        // Audio source: null = not configured, true = bluetooth, false = adapter
        private val KEY_AUDIO_SOURCE_CONFIGURED = booleanPreferencesKey("audio_source_configured")
        private val KEY_AUDIO_SOURCE_BLUETOOTH = booleanPreferencesKey("audio_source_bluetooth")

        // Add future configuration keys here:
        // private val KEY_WIFI_BAND = stringPreferencesKey("wifi_band")
        // private val KEY_MIC_SOURCE = stringPreferencesKey("mic_source")
    }

    private val dataStore = appContext.adapterConfigDataStore

    // ==================== Audio Source ====================

    /**
     * Flow for observing audio source configuration changes.
     */
    val audioSourceFlow: Flow<AudioSourceConfig> =
        dataStore.data.map { preferences ->
            val isConfigured = preferences[KEY_AUDIO_SOURCE_CONFIGURED] ?: false
            if (!isConfigured) {
                AudioSourceConfig.NOT_CONFIGURED
            } else {
                val isBluetooth = preferences[KEY_AUDIO_SOURCE_BLUETOOTH] ?: false
                if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
            }
        }

    /**
     * Get current audio source configuration.
     */
    suspend fun getAudioSource(): AudioSourceConfig {
        return try {
            val preferences = dataStore.data.first()
            val isConfigured = preferences[KEY_AUDIO_SOURCE_CONFIGURED] ?: false
            if (!isConfigured) {
                AudioSourceConfig.NOT_CONFIGURED
            } else {
                val isBluetooth = preferences[KEY_AUDIO_SOURCE_BLUETOOTH] ?: false
                if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
            }
        } catch (e: Exception) {
            logError("Failed to read audio source preference: $e", tag = "AdapterConfig")
            AudioSourceConfig.NOT_CONFIGURED
        }
    }

    /**
     * Set audio source configuration.
     */
    suspend fun setAudioSource(config: AudioSourceConfig) {
        try {
            dataStore.edit { preferences ->
                when (config) {
                    AudioSourceConfig.NOT_CONFIGURED -> {
                        preferences[KEY_AUDIO_SOURCE_CONFIGURED] = false
                        preferences.remove(KEY_AUDIO_SOURCE_BLUETOOTH)
                    }
                    AudioSourceConfig.BLUETOOTH -> {
                        preferences[KEY_AUDIO_SOURCE_CONFIGURED] = true
                        preferences[KEY_AUDIO_SOURCE_BLUETOOTH] = true
                    }
                    AudioSourceConfig.ADAPTER -> {
                        preferences[KEY_AUDIO_SOURCE_CONFIGURED] = true
                        preferences[KEY_AUDIO_SOURCE_BLUETOOTH] = false
                    }
                }
            }
            logInfo("Audio source preference saved: $config", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save audio source preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    // ==================== Configuration Summary ====================

    /**
     * Data class containing all user-configured adapter settings.
     * Only configured options are included; NOT_CONFIGURED options are null.
     */
    data class UserConfig(
        val audioTransferMode: Boolean?, // null = not configured, true = bluetooth, false = adapter
        // Add future configuration fields here:
        // val wifiBand: String?,
        // val micSource: String?,
    ) {
        /**
         * Returns true if any configuration option has been set by the user.
         */
        fun hasAnyConfiguration(): Boolean = audioTransferMode != null

        companion object {
            val EMPTY = UserConfig(audioTransferMode = null)
        }
    }

    /**
     * Get all user-configured settings as a single object.
     * Only returns values that the user has explicitly configured.
     */
    suspend fun getUserConfig(): UserConfig {
        val audioSource = getAudioSource()
        return UserConfig(
            audioTransferMode = when (audioSource) {
                AudioSourceConfig.NOT_CONFIGURED -> null
                AudioSourceConfig.BLUETOOTH -> true
                AudioSourceConfig.ADAPTER -> false
            }
        )
    }

    /**
     * Reset all configuration to defaults (not configured).
     */
    suspend fun resetToDefaults() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_AUDIO_SOURCE_CONFIGURED)
                preferences.remove(KEY_AUDIO_SOURCE_BLUETOOTH)
                // Add future keys to clear here
            }
            logInfo("Adapter config preferences reset to defaults", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to reset adapter config preferences: $e", tag = "AdapterConfig")
            throw e
        }
    }

    /**
     * Get a summary of current preferences for debugging.
     */
    suspend fun getPreferencesSummary(): Map<String, Any?> {
        val audioSource = getAudioSource()
        return mapOf(
            "audioSource" to audioSource.name,
            "audioTransferMode" to when (audioSource) {
                AudioSourceConfig.NOT_CONFIGURED -> "not configured"
                AudioSourceConfig.BLUETOOTH -> "true (bluetooth)"
                AudioSourceConfig.ADAPTER -> "false (adapter)"
            },
            // Add future config summaries here
        )
    }
}
