package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * Media delay configuration for adapter audio buffer size.
 * Controls tinyalsa PCM buffer size (pcm_open period_size) on the adapter firmware.
 * Lower values = less audio latency but more susceptible to USB jitter.
 * Higher values = more stable but noticeable audio lag.
 */
enum class MediaDelayConfig(val delayMs: Int) {
    LOW(300),
    MEDIUM(500),
    STANDARD(1000),
    HIGH(2000),
    ;

    companion object {
        val DEFAULT = STANDARD
    }
}

/**
 * Video resolution configuration for adapter initialization.
 *
 * AUTO uses the detected display resolution (usable area after system UI).
 * Other options are calculated based on the display's aspect ratio to maintain
 * proper proportions while reducing GPU load.
 *
 * Note: Resolution is stored as a string format "WIDTHxHEIGHT" or "AUTO".
 */
data class VideoResolutionConfig(
    val width: Int,
    val height: Int,
) {
    val isAuto: Boolean get() = width == 0 && height == 0

    fun toStorageString(): String = if (isAuto) "AUTO" else "${width}x$height"

    companion object {
        val AUTO = VideoResolutionConfig(0, 0)

        fun fromStorageString(value: String?): VideoResolutionConfig {
            if (value == null || value == "AUTO") return AUTO
            val parts = value.split("x")
            return if (parts.size == 2) {
                try {
                    VideoResolutionConfig(parts[0].toInt(), parts[1].toInt())
                } catch (e: NumberFormatException) {
                    AUTO
                }
            } else {
                AUTO
            }
        }

        /**
         * Calculate resolution options based on display aspect ratio.
         *
         * Returns 4 resolution options that maintain the display's aspect ratio,
         * scaled down from the base resolution. All dimensions are rounded to
         * even numbers for H.264 compatibility.
         *
         * @param displayWidth Native display width in pixels
         * @param displayHeight Native display height in pixels
         * @return List of 4 resolution options, largest to smallest
         */
        fun calculateOptions(displayWidth: Int, displayHeight: Int): List<VideoResolutionConfig> {
            // Calculate GCD to find the aspect ratio
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val g = gcd(displayWidth, displayHeight)
            val ratioW = displayWidth / g
            val ratioH = displayHeight / g

            // Calculate scale factors to generate 4 options
            // Start from ~93.75% of original, then 83.3%, 72.9%, 62.5%
            val scaleFactors = listOf(0.9375, 0.833, 0.729, 0.625)

            return scaleFactors.map { scale ->
                // Scale from display dimensions and round to even
                val w = ((displayWidth * scale).toInt() and 1.inv())
                val h = ((displayHeight * scale).toInt() and 1.inv())
                VideoResolutionConfig(w, h)
            }
        }
    }
}

/**
 * Adapter config preferences with DataStore + SharedPreferences sync cache for ANR-free startup reads.
 * Tracks pending changes for minimal re-initialization on reconnect.
 */
@Suppress("StaticFieldLeak")
class AdapterConfigPreference private constructor(
    context: Context,
) {
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

        // Mic source: stored as command code (7=phone, 15=adapter)
        private val KEY_MIC_SOURCE = intPreferencesKey("mic_source")

        // WiFi band: stored as command code (25=5GHz, 24=2.4GHz)
        private val KEY_WIFI_BAND = intPreferencesKey("wifi_band")

        // Call quality: stored as value (0=normal, 1=clear, 2=HD)
        private val KEY_CALL_QUALITY = intPreferencesKey("call_quality")

        // Media delay: stored as delay in ms (300, 500, 1000, 2000)
        private val KEY_MEDIA_DELAY = intPreferencesKey("media_delay")

        // Video resolution: stored as string "WIDTHxHEIGHT" or "AUTO"
        private val KEY_VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")

        // Initialization tracking
        private val KEY_HAS_COMPLETED_FIRST_INIT = booleanPreferencesKey("has_completed_first_init")
        private val KEY_PENDING_CHANGES = stringSetPreferencesKey("pending_changes")

        // SharedPreferences keys for sync cache (ANR prevention)
        private const val SYNC_CACHE_PREFS_NAME = "carlink_adapter_config_sync_cache"
        private const val SYNC_CACHE_KEY_AUDIO_CONFIGURED = "audio_source_configured"
        private const val SYNC_CACHE_KEY_AUDIO_BLUETOOTH = "audio_source_bluetooth"
        private const val SYNC_CACHE_KEY_MIC_SOURCE = "mic_source"
        private const val SYNC_CACHE_KEY_WIFI_BAND = "wifi_band"
        private const val SYNC_CACHE_KEY_CALL_QUALITY = "call_quality"
        private const val SYNC_CACHE_KEY_MEDIA_DELAY = "media_delay"
        private const val SYNC_CACHE_KEY_VIDEO_RESOLUTION = "video_resolution"
        private const val SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT = "has_completed_first_init"
        private const val SYNC_CACHE_KEY_PENDING_CHANGES = "pending_changes"

        /**
         * Configuration keys for tracking pending changes.
         * Used to identify which settings need to be sent on next initialization.
         */
        object ConfigKey {
            const val AUDIO_SOURCE = "audio_source"
            const val MIC_SOURCE = "mic_source"
            const val WIFI_BAND = "wifi_band"
            const val CALL_QUALITY = "call_quality"
            const val MEDIA_DELAY = "media_delay"
            const val VIDEO_RESOLUTION = "video_resolution"
        }
    }

    private val dataStore = appContext.adapterConfigDataStore

    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

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

    val mediaDelayFlow: Flow<MediaDelayConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_MEDIA_DELAY] ?: MediaDelayConfig.DEFAULT.delayMs
            MediaDelayConfig.entries.find { it.delayMs == value } ?: MediaDelayConfig.DEFAULT
        }

    /**
     * Get current media delay configuration synchronously.
     */
    fun getMediaDelaySync(): MediaDelayConfig {
        val value = syncCache.getInt(SYNC_CACHE_KEY_MEDIA_DELAY, MediaDelayConfig.DEFAULT.delayMs)
        return MediaDelayConfig.entries.find { it.delayMs == value } ?: MediaDelayConfig.DEFAULT
    }

    /**
     * Set media delay configuration.
     */
    suspend fun setMediaDelay(config: MediaDelayConfig) {
        try {
            dataStore.edit { preferences ->
                preferences[KEY_MEDIA_DELAY] = config.delayMs
            }
            syncCache.edit().putInt(SYNC_CACHE_KEY_MEDIA_DELAY, config.delayMs).apply()
            addPendingChange(ConfigKey.MEDIA_DELAY)
            logInfo("Media delay preference saved: $config (${config.delayMs}ms)", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save media delay preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    val videoResolutionFlow: Flow<VideoResolutionConfig> =
        dataStore.data.map { preferences ->
            val value = preferences[KEY_VIDEO_RESOLUTION]
            VideoResolutionConfig.fromStorageString(value)
        }

    /**
     * Get current video resolution configuration synchronously.
     * Returns AUTO if not configured.
     */
    fun getVideoResolutionSync(): VideoResolutionConfig {
        val value = syncCache.getString(SYNC_CACHE_KEY_VIDEO_RESOLUTION, "AUTO")
        return VideoResolutionConfig.fromStorageString(value)
    }

    /**
     * Set video resolution configuration.
     */
    suspend fun setVideoResolution(config: VideoResolutionConfig) {
        try {
            val storageValue = config.toStorageString()
            dataStore.edit { preferences ->
                preferences[KEY_VIDEO_RESOLUTION] = storageValue
            }
            syncCache.edit().putString(SYNC_CACHE_KEY_VIDEO_RESOLUTION, storageValue).apply()
            addPendingChange(ConfigKey.VIDEO_RESOLUTION)
            logInfo("Video resolution preference saved: $storageValue", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to save video resolution preference: $e", tag = "AdapterConfig")
            throw e
        }
    }

    data class UserConfig(
        /** Audio transfer mode: true = bluetooth, false = adapter (default) */
        val audioTransferMode: Boolean,
        /** Microphone source configuration */
        val micSource: MicSourceConfig,
        /** WiFi band configuration */
        val wifiBand: WiFiBandConfig,
        /** Call quality configuration */
        val callQuality: CallQualityConfig,
        /** Media delay configuration */
        val mediaDelay: MediaDelayConfig,
        /** Video resolution configuration */
        val videoResolution: VideoResolutionConfig,
    ) {
        companion object {
            val DEFAULT =
                UserConfig(
                    audioTransferMode = false, // ADAPTER is default
                    micSource = MicSourceConfig.DEFAULT,
                    wifiBand = WiFiBandConfig.DEFAULT,
                    callQuality = CallQualityConfig.DEFAULT,
                    mediaDelay = MediaDelayConfig.DEFAULT,
                    videoResolution = VideoResolutionConfig.AUTO,
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
        val micSource = getMicSourceSync()
        val wifiBand = getWifiBandSync()
        val callQuality = getCallQualitySync()
        val mediaDelay = getMediaDelaySync()
        val videoResolution = getVideoResolutionSync()
        return UserConfig(
            audioTransferMode = audioSource == AudioSourceConfig.BLUETOOTH,
            micSource = micSource,
            wifiBand = wifiBand,
            callQuality = callQuality,
            mediaDelay = mediaDelay,
            videoResolution = videoResolution,
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
                preferences.remove(KEY_MIC_SOURCE)
                preferences.remove(KEY_WIFI_BAND)
                preferences.remove(KEY_CALL_QUALITY)
                preferences.remove(KEY_MEDIA_DELAY)
                preferences.remove(KEY_VIDEO_RESOLUTION)
                preferences.remove(KEY_HAS_COMPLETED_FIRST_INIT)
                preferences.remove(KEY_PENDING_CHANGES)
            }
            // Clear sync cache
            syncCache
                .edit()
                .apply {
                    remove(SYNC_CACHE_KEY_AUDIO_CONFIGURED)
                    remove(SYNC_CACHE_KEY_AUDIO_BLUETOOTH)
                    remove(SYNC_CACHE_KEY_MIC_SOURCE)
                    remove(SYNC_CACHE_KEY_WIFI_BAND)
                    remove(SYNC_CACHE_KEY_CALL_QUALITY)
                    remove(SYNC_CACHE_KEY_MEDIA_DELAY)
                    remove(SYNC_CACHE_KEY_VIDEO_RESOLUTION)
                    remove(SYNC_CACHE_KEY_HAS_COMPLETED_FIRST_INIT)
                    remove(SYNC_CACHE_KEY_PENDING_CHANGES)
                }.apply()
            logInfo("Adapter config preferences reset to defaults (sync cache cleared, next session will run FULL init)", tag = "AdapterConfig")
        } catch (e: Exception) {
            logError("Failed to reset adapter config preferences: $e", tag = "AdapterConfig")
            throw e
        }
    }

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
