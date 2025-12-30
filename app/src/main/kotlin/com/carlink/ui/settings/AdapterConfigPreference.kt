package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
     * Bluetooth mode - Audio plays through phone's Bluetooth to car stereo.
     * Sends AUDIO_TRANSFER_ON during initialization.
     */
    BLUETOOTH,

    /**
     * Adapter mode - Audio streams through USB to be played by this app.
     * Sends AUDIO_TRANSFER_OFF during initialization.
     * This is the default for first launch (FULL init).
     */
    ADAPTER,
    ;

    companion object {
        val DEFAULT = ADAPTER
    }
}

/**
 * Microphone source configuration.
 * Controls which microphone is used for voice input (Siri, calls).
 */
enum class MicSourceConfig(
    val commandCode: Int,
) {
    /** App/Device mic - this device (Android/Pi running the app) captures microphone input. */
    APP(7),

    /** Phone/Adapter mic - phone uses its own mic, or adapter's mic if physically present. */
    PHONE(15),
    ;

    companion object {
        val DEFAULT = APP
    }
}

/**
 * WiFi band configuration for wireless CarPlay connection.
 * 5GHz offers better performance, 2.4GHz offers better range/compatibility.
 */
enum class WiFiBandConfig(
    val commandCode: Int,
) {
    /** 5GHz band - better speed and less interference (recommended). */
    BAND_5GHZ(25),

    /** 2.4GHz band - better range but more interference. */
    BAND_24GHZ(24),
    ;

    companion object {
        val DEFAULT = BAND_5GHZ
    }
}

/**
 * Call quality configuration for phone calls.
 * May affect audio bitrate or quality during calls (needs testing).
 */
enum class CallQualityConfig(
    val value: Int,
) {
    /** Normal quality. */
    NORMAL(0),

    /** Clear quality - enhanced clarity. */
    CLEAR(1),

    /** HD quality - highest quality. */
    HD(2),
    ;

    companion object {
        val DEFAULT = HD
    }
}

/**
 * Sample rate configuration for adapter audio output.
 * Controls the mediaSound parameter sent to the adapter during initialization.
 */
enum class SampleRateConfig(
    val hz: Int,
    val mediaSound: Int,
) {
    /**
     * 44.1kHz - Standard CD quality audio.
     * Adapter sends audio with decodeType 1 or 2.
     */
    RATE_44100(44100, 0),

    /**
     * 48kHz - Professional/high-quality audio (default for GM AAOS).
     * Adapter sends audio with decodeType 4.
     */
    RATE_48000(48000, 1),
    ;

    companion object {
        val DEFAULT = RATE_48000

        fun fromHz(hz: Int): SampleRateConfig =
            when (hz) {
                44100 -> RATE_44100
                48000 -> RATE_48000
                else -> DEFAULT
            }
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
 * - All options have sensible defaults (ADAPTER for audio source)
 * - FULL init sends all settings, minimal init only sends changed settings
 * - Adapter firmware retains most settings through power cycles
 *
 * Storage:
 * - Uses DataStore Preferences for persistence with SharedPreferences sync cache
 * - Sync Cache: SharedPreferences for instant startup reads (avoids ANR)
 * - Preferences survive app restarts
 * - Applied during AdapterConfig creation in MainActivity
 *
 * ANR Prevention:
 * Uses SharedPreferences as a synchronous cache per Android Developer guidance.
 * DataStore I/O can block the main thread causing ANR if read synchronously.
 * SharedPreferences provides instant cached reads at startup while DataStore
 * remains the source of truth for Flow-based observation.
 *
 * Extensibility:
 * - New configuration options can be added by:
 *   1. Adding a preference key (both DataStore and sync cache)
 *   2. Adding getter/setter methods (update sync cache in setter)
 *   3. Adding Flow for UI observation
 *   4. Updating getUserConfigSync() and getUserConfig() methods
 */
@Suppress("StaticFieldLeak") // Uses applicationContext, not Activity context - no leak
class AdapterConfigPreference private constructor(
    context: Context,
) {
    // Store applicationContext to avoid Activity leaks
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: AdapterConfigPreference? = null

        fun getInstance(context: Context): AdapterConfigPreference =
            instance ?: synchronized(this) {
                instance ?: AdapterConfigPreference(context.applicationContext).also { instance = it }
            }

        // Preference keys
        // Audio source: null = not configured, true = bluetooth, false = adapter
        private val KEY_AUDIO_SOURCE_CONFIGURED = booleanPreferencesKey("audio_source_configured")
        private val KEY_AUDIO_SOURCE_BLUETOOTH = booleanPreferencesKey("audio_source_bluetooth")

        // Sample rate: stored as Hz value (44100 or 48000)
        private val KEY_SAMPLE_RATE = intPreferencesKey("sample_rate")

        // Mic source: stored as command code (7=phone, 15=adapter)
        private val KEY_MIC_SOURCE = intPreferencesKey("mic_source")

        // WiFi band: stored as command code (25=5GHz, 24=2.4GHz)
        private val KEY_WIFI_BAND = intPreferencesKey("wifi_band")

        // Call quality: stored as value (0=normal, 1=clear, 2=HD)
        private val KEY_CALL_QUALITY = intPreferencesKey("call_quality")

        // Initialization tracking
        private val KEY_HAS_COMPLETED_FIRST_INIT = booleanPreferencesKey("has_completed_first_init")
        private val KEY_PENDING_CHANGES = stringSetPreferencesKey("pending_changes")

        // SharedPreferences keys for sync cache (ANR prevention)
        private const val SYNC_CACHE_PREFS_NAME = "carlink_adapter_config_sync_cache"
        private const val SYNC_CACHE_KEY_AUDIO_CONFIGURED = "audio_source_configured"
        private const val SYNC_CACHE_KEY_AUDIO_BLUETOOTH = "audio_source_bluetooth"
        private const val SYNC_CACHE_KEY_SAMPLE_RATE = "sample_rate"
        private const val SYNC_CACHE_KEY_MIC_SOURCE = "mic_source"
        private const val SYNC_CACHE_KEY_WIFI_BAND = "wifi_band"
        private const val SYNC_CACHE_KEY_CALL_QUALITY = "call_quality"
        private const val SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT = "has_completed_first_init"
        private const val SYNC_CACHE_KEY_PENDING_CHANGES = "pending_changes"

        /**
         * Configuration keys for tracking pending changes.
         * Used to identify which settings need to be sent on next initialization.
         */
        object ConfigKey {
            const val AUDIO_SOURCE = "audio_source"
            const val SAMPLE_RATE = "sample_rate"
            const val MIC_SOURCE = "mic_source"
            const val WIFI_BAND = "wifi_band"
            const val CALL_QUALITY = "call_quality"
        }
    }

    private val dataStore = appContext.adapterConfigDataStore

    // SharedPreferences sync cache for instant startup reads
    // Per Android Developer guidance: use SharedPreferences for synchronous access
    // to avoid blocking main thread with DataStore I/O
    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    // ==================== Audio Source ====================

    /**
     * Flow for observing audio source configuration changes.
     */
    val audioSourceFlow: Flow<AudioSourceConfig> =
        dataStore.data.map { preferences ->
            val isBluetooth = preferences[KEY_AUDIO_SOURCE_BLUETOOTH] ?: false
            if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
        }

    /**
     * Get current audio source configuration synchronously.
     * Uses SharedPreferences cache to avoid ANR.
     *
     * This is safe to call from the main thread during Activity.onCreate().
     */
    fun getAudioSourceSync(): AudioSourceConfig {
        val isBluetooth = syncCache.getBoolean(SYNC_CACHE_KEY_AUDIO_BLUETOOTH, false)
        return if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
    }

    /**
     * Get current audio source configuration.
     *
     * Note: Prefer getAudioSourceSync() for startup reads to avoid ANR.
     */
    suspend fun getAudioSource(): AudioSourceConfig =
        try {
            val preferences = dataStore.data.first()
            val isBluetooth = preferences[KEY_AUDIO_SOURCE_BLUETOOTH] ?: false
            if (isBluetooth) AudioSourceConfig.BLUETOOTH else AudioSourceConfig.ADAPTER
        } catch (e: Exception) {
            logError("Failed to read audio source preference: $e", tag = "AdapterConfig")
            AudioSourceConfig.DEFAULT
        }

    /**
     * Set audio source configuration.
     * Updates both DataStore and sync cache atomically.
     */
    suspend fun setAudioSource(config: AudioSourceConfig) {
        try {
            val isBluetooth = config == AudioSourceConfig.BLUETOOTH
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_AUDIO_SOURCE_BLUETOOTH] = isBluetooth
            }
            // Update sync cache for instant reads on next startup
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_AUDIO_BLUETOOTH, isBluetooth).apply()
            // Track as pending change for next initialization
            addPendingChange(ConfigKey.AUDIO_SOURCE)
            logInfo("Audio source preference saved: $config (sync cache updated)", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save audio source preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    // ==================== Sample Rate ====================

    /**
     * Flow for observing sample rate configuration changes.
     */
    val sampleRateFlow: Flow<SampleRateConfig> =
        dataStore.data.map { preferences ->
            val hz = preferences[KEY_SAMPLE_RATE] ?: SampleRateConfig.DEFAULT.hz
            SampleRateConfig.fromHz(hz)
        }

    /**
     * Get current sample rate configuration synchronously.
     * Uses SharedPreferences cache to avoid ANR.
     *
     * This is safe to call from the main thread during Activity.onCreate().
     */
    fun getSampleRateSync(): SampleRateConfig {
        val hz = syncCache.getInt(SYNC_CACHE_KEY_SAMPLE_RATE, SampleRateConfig.DEFAULT.hz)
        return SampleRateConfig.fromHz(hz)
    }

    /**
     * Get current sample rate configuration.
     *
     * Note: Prefer getSampleRateSync() for startup reads to avoid ANR.
     */
    suspend fun getSampleRate(): SampleRateConfig =
        try {
            val preferences = dataStore.data.first()
            val hz = preferences[KEY_SAMPLE_RATE] ?: SampleRateConfig.DEFAULT.hz
            SampleRateConfig.fromHz(hz)
        } catch (e: Exception) {
            logError("Failed to read sample rate preference: $e", tag = "AdapterConfig")
            SampleRateConfig.DEFAULT
        }

    /**
     * Set sample rate configuration.
     * Updates both DataStore and sync cache atomically.
     */
    suspend fun setSampleRate(config: SampleRateConfig) {
        try {
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_SAMPLE_RATE] = config.hz
            }
            // Update sync cache for instant reads on next startup
            syncCache.edit().putInt(SYNC_CACHE_KEY_SAMPLE_RATE, config.hz).apply()
            // Track as pending change for next initialization
            addPendingChange(ConfigKey.SAMPLE_RATE)
            logInfo("Sample rate preference saved: $config (${config.hz}Hz)", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save sample rate preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    // ==================== Mic Source ====================

    /**
     * Flow for observing mic source configuration changes.
     */
    val micSourceFlow: Flow<MicSourceConfig> =
        dataStore.data.map { preferences ->
            val code = preferences[KEY_MIC_SOURCE] ?: MicSourceConfig.DEFAULT.commandCode
            MicSourceConfig.entries.find { it.commandCode == code } ?: MicSourceConfig.DEFAULT
        }

    /**
     * Get current mic source configuration synchronously.
     */
    fun getMicSourceSync(): MicSourceConfig {
        val code = syncCache.getInt(SYNC_CACHE_KEY_MIC_SOURCE, MicSourceConfig.DEFAULT.commandCode)
        return MicSourceConfig.entries.find { it.commandCode == code } ?: MicSourceConfig.DEFAULT
    }

    /**
     * Set mic source configuration.
     */
    suspend fun setMicSource(config: MicSourceConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_MIC_SOURCE] = config.commandCode
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_MIC_SOURCE, config.commandCode).apply()
            addPendingChange(ConfigKey.MIC_SOURCE)
            logInfo("Mic source preference saved: $config", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save mic source preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    // ==================== WiFi Band ====================

    /**
     * Flow for observing WiFi band configuration changes.
     */
    val wifiBandFlow: Flow<WiFiBandConfig> =
        dataStore.data.map { preferences ->
            val code = preferences[KEY_WIFI_BAND] ?: WiFiBandConfig.DEFAULT.commandCode
            WiFiBandConfig.entries.find { it.commandCode == code } ?: WiFiBandConfig.DEFAULT
        }

    /**
     * Get current WiFi band configuration synchronously.
     */
    fun getWifiBandSync(): WiFiBandConfig {
        val code = syncCache.getInt(SYNC_CACHE_KEY_WIFI_BAND, WiFiBandConfig.DEFAULT.commandCode)
        return WiFiBandConfig.entries.find { it.commandCode == code } ?: WiFiBandConfig.DEFAULT
    }

    /**
     * Set WiFi band configuration.
     */
    suspend fun setWifiBand(config: WiFiBandConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_WIFI_BAND] = config.commandCode
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_WIFI_BAND, config.commandCode).apply()
            addPendingChange(ConfigKey.WIFI_BAND)
            logInfo("WiFi band preference saved: $config", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save WiFi band preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    // ==================== Call Quality ====================

    /**
     * Flow for observing call quality configuration changes.
     */
    val callQualityFlow: Flow<CallQualityConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_CALL_QUALITY] ?: CallQualityConfig.DEFAULT.value
            CallQualityConfig.entries.find { it.value == value } ?: CallQualityConfig.DEFAULT
        }

    /**
     * Get current call quality configuration synchronously.
     */
    fun getCallQualitySync(): CallQualityConfig {
        val value = syncCache.getInt(SYNC_CACHE_KEY_CALL_QUALITY, CallQualityConfig.DEFAULT.value)
        return CallQualityConfig.entries.find { it.value == value } ?: CallQualityConfig.DEFAULT
    }

    /**
     * Set call quality configuration.
     */
    suspend fun setCallQuality(config: CallQualityConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_CALL_QUALITY] = config.value
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_CALL_QUALITY, config.value).apply()
            addPendingChange(ConfigKey.CALL_QUALITY)
            logInfo("Call quality preference saved: $config", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save call quality preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    // ==================== Configuration Summary ====================

    /**
     * Data class containing all user-configured adapter settings.
     */
    data class UserConfig(
        /** Audio transfer mode: true = bluetooth, false = adapter (default) */
        val audioTransferMode: Boolean,
        /** Sample rate configuration for media audio */
        val sampleRate: SampleRateConfig,
        /** Microphone source configuration */
        val micSource: MicSourceConfig,
        /** WiFi band configuration */
        val wifiBand: WiFiBandConfig,
        /** Call quality configuration */
        val callQuality: CallQualityConfig,
    ) {
        companion object {
            val DEFAULT =
                UserConfig(
                    audioTransferMode = false, // ADAPTER is default
                    sampleRate = SampleRateConfig.DEFAULT,
                    micSource = MicSourceConfig.DEFAULT,
                    wifiBand = WiFiBandConfig.DEFAULT,
                    callQuality = CallQualityConfig.DEFAULT,
                )
        }
    }

    /**
     * Get all user-configured settings synchronously.
     * Uses SharedPreferences cache to avoid ANR.
     *
     * This is safe to call from the main thread during Activity.onCreate().
     */
    fun getUserConfigSync(): UserConfig {
        val audioSource = getAudioSourceSync()
        val sampleRate = getSampleRateSync()
        val micSource = getMicSourceSync()
        val wifiBand = getWifiBandSync()
        val callQuality = getCallQualitySync()
        return UserConfig(
            audioTransferMode = audioSource == AudioSourceConfig.BLUETOOTH,
            sampleRate = sampleRate,
            micSource = micSource,
            wifiBand = wifiBand,
            callQuality = callQuality,
        )
    }

    /**
     * Get all user-configured settings as a single object.
     *
     * Note: Prefer getUserConfigSync() for startup reads to avoid ANR.
     */
    suspend fun getUserConfig(): UserConfig {
        val audioSource = getAudioSource()
        val sampleRate = getSampleRate()
        return UserConfig(
            audioTransferMode = audioSource == AudioSourceConfig.BLUETOOTH,
            sampleRate = sampleRate,
            micSource = getMicSourceSync(),
            wifiBand = getWifiBandSync(),
            callQuality = getCallQualitySync(),
        )
    }

    /**
     * Reset all configuration to defaults.
     * Clears both DataStore and sync cache.
     */
    suspend fun resetToDefaults() {
        try {
            // Clear DataStore
            dataStore.edit { preferences ->
                preferences.remove(KEY_AUDIO_SOURCE_CONFIGURED)
                preferences.remove(KEY_AUDIO_SOURCE_BLUETOOTH)
                preferences.remove(KEY_SAMPLE_RATE)
                preferences.remove(KEY_MIC_SOURCE)
                preferences.remove(KEY_WIFI_BAND)
                preferences.remove(KEY_CALL_QUALITY)
            }
            // Clear sync cache
            syncCache
                .edit()
                .apply {
                    remove(SYNC_CACHE_KEY_AUDIO_CONFIGURED)
                    remove(SYNC_CACHE_KEY_AUDIO_BLUETOOTH)
                    remove(SYNC_CACHE_KEY_SAMPLE_RATE)
                    remove(SYNC_CACHE_KEY_MIC_SOURCE)
                    remove(SYNC_CACHE_KEY_WIFI_BAND)
                    remove(SYNC_CACHE_KEY_CALL_QUALITY)
                }.apply()
            logInfo("Adapter config preferences reset to defaults (sync cache cleared)", tag = "AdapterConfig")
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
        val sampleRate = getSampleRate()
        return mapOf(
            "audioSource" to audioSource.name,
            "audioTransferMode" to if (audioSource == AudioSourceConfig.BLUETOOTH) "true (bluetooth)" else "false (adapter)",
            "sampleRate" to "${sampleRate.hz}Hz",
            "micSource" to getMicSourceSync().name,
            "wifiBand" to getWifiBandSync().name,
            "callQuality" to getCallQualitySync().name,
            "hasCompletedFirstInit" to hasCompletedFirstInitSync(),
            "pendingChanges" to getPendingChangesSync(),
        )
    }

    // ==================== Initialization Tracking ====================

    /**
     * Initialization mode for adapter configuration.
     */
    enum class InitMode {
        /** First launch - send full configuration */
        FULL,

        /** Subsequent launch with pending changes - send minimal + changed settings */
        MINIMAL_PLUS_CHANGES,

        /** Subsequent launch with no changes - send minimal only */
        MINIMAL_ONLY,
    }

    /**
     * Check if first initialization has been completed.
     */
    fun hasCompletedFirstInitSync(): Boolean = syncCache.getBoolean(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT, false)

    /**
     * Mark first initialization as completed.
     */
    suspend fun markFirstInitCompleted() {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_HAS_COMPLETED_FIRST_INIT] = true
            }
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT, true).apply()
            logInfo("First initialization marked as completed", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to mark first init completed: $e", tag = "AdapterConfig")
        }
    }

    /**
     * Get pending configuration changes synchronously.
     */
    fun getPendingChangesSync(): Set<String> = syncCache.getStringSet(SYNC_CACHE_KEY_PENDING_CHANGES, emptySet()) ?: emptySet()

    /**
     * Add a configuration key to pending changes.
     * Called when user modifies a setting.
     */
    private suspend fun addPendingChange(configKey: String) {
        try {
            dataStore.edit { preferences ->
                val current = preferences[KEY_PENDING_CHANGES] ?: emptySet()
                preferences[KEY_PENDING_CHANGES] = current + configKey
            }
            val current = syncCache.getStringSet(SYNC_CACHE_KEY_PENDING_CHANGES, emptySet()) ?: emptySet()
            syncCache.edit().putStringSet(SYNC_CACHE_KEY_PENDING_CHANGES, current + configKey).apply()
            logInfo("Added pending change: $configKey", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to add pending change: $e", tag = "AdapterConfig")
        }
    }

    /**
     * Clear all pending changes.
     * Called after successful initialization with changes.
     */
    suspend fun clearPendingChanges() {
        try {
            dataStore.edit { preferences ->
                preferences.remove(KEY_PENDING_CHANGES)
            }
            syncCache.edit().remove(SYNC_CACHE_KEY_PENDING_CHANGES).apply()
            logInfo("Pending changes cleared", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to clear pending changes: $e", tag = "AdapterConfig")
        }
    }

    /**
     * Determine the initialization mode based on current state.
     *
     * @return InitMode indicating what configuration to send
     */
    fun getInitializationMode(): InitMode {
        val hasCompletedFirstInit = hasCompletedFirstInitSync()
        val pendingChanges = getPendingChangesSync()

        return when {
            !hasCompletedFirstInit -> InitMode.FULL
            pendingChanges.isNotEmpty() -> InitMode.MINIMAL_PLUS_CHANGES
            else -> InitMode.MINIMAL_ONLY
        }
    }

    /**
     * Get initialization info for logging.
     */
    fun getInitializationInfo(): String {
        val mode = getInitializationMode()
        val pendingChanges = getPendingChangesSync()
        return when (mode) {
            InitMode.FULL -> "FULL (first launch)"
            InitMode.MINIMAL_PLUS_CHANGES -> "MINIMAL + changes: $pendingChanges"
            InitMode.MINIMAL_ONLY -> "MINIMAL (no changes)"
        }
    }
}
