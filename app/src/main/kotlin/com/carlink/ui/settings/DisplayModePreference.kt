package com.carlink.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Display mode options for controlling system UI visibility.
 *
 * Controls how the app interacts with AAOS system bars (status bar and navigation bar)
 * during CarPlay/Android Auto projection.
 */
enum class DisplayMode(
    val value: Int,
) {
    /**
     * System UI Visible - Both status bar and navigation bar always visible.
     * AAOS manages display bounds. Recommended for proper GM infotainment integration.
     * This is the default mode.
     */
    SYSTEM_UI_VISIBLE(0),

    /**
     * Status Bar Hidden - Only status bar is hidden, navigation bar remains visible.
     * Provides extra vertical space while keeping navigation accessible.
     */
    STATUS_BAR_HIDDEN(1),

    /**
     * Fullscreen Immersive - Both status bar and navigation bar are hidden.
     * Maximum projection area. Swipe from edge to temporarily reveal system bars.
     */
    FULLSCREEN_IMMERSIVE(2),
    ;

    companion object {
        /**
         * Convert an integer value to DisplayMode.
         * Returns SYSTEM_UI_VISIBLE for unknown values (safe default).
         */
        fun fromValue(value: Int): DisplayMode = entries.find { it.value == value } ?: SYSTEM_UI_VISIBLE
    }
}

private val Context.displayModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_immersive_preferences", // Keep same name for migration compatibility
)

/**
 * Display mode preference with DataStore + SharedPreferences sync cache for ANR-free startup reads.
 * Migrates from legacy boolean immersive_mode_enabled preference.
 */
@Suppress("StaticFieldLeak")
class DisplayModePreference private constructor(
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: DisplayModePreference? = null

        fun getInstance(context: Context): DisplayModePreference =
            instance ?: synchronized(this) {
                instance ?: DisplayModePreference(context.applicationContext).also { instance = it }
            }

        // New int key for display mode
        private val KEY_DISPLAY_MODE = intPreferencesKey("display_mode")

        // Old boolean key for migration
        private val KEY_IMMERSIVE_MODE_ENABLED_LEGACY = booleanPreferencesKey("immersive_mode_enabled")

        // SharedPreferences keys for sync cache (ANR prevention)
        private const val SYNC_CACHE_PREFS_NAME = "carlink_immersive_sync_cache"
        private const val SYNC_CACHE_KEY_DISPLAY_MODE = "display_mode"
        private const val SYNC_CACHE_KEY_MIGRATED = "display_mode_migrated"

        // Legacy key for migration detection
        private const val SYNC_CACHE_KEY_ENABLED_LEGACY = "immersive_mode_enabled"
    }

    private val dataStore = appContext.displayModeDataStore

    // SharedPreferences sync cache for instant startup reads
    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    init {
        // Perform migration from boolean to int on first access
        migrateFromBooleanIfNeeded()
    }

    /**
     * Migrate from old boolean preference to new int preference.
     * Only runs once - uses a flag to track migration status.
     */
    private fun migrateFromBooleanIfNeeded() {
        if (syncCache.getBoolean(SYNC_CACHE_KEY_MIGRATED, false)) {
            return // Already migrated
        }

        // Check if old boolean preference exists
        if (syncCache.contains(SYNC_CACHE_KEY_ENABLED_LEGACY)) {
            val wasImmersive = syncCache.getBoolean(SYNC_CACHE_KEY_ENABLED_LEGACY, false)
            val newMode =
                if (wasImmersive) {
                    DisplayMode.FULLSCREEN_IMMERSIVE
                } else {
                    DisplayMode.SYSTEM_UI_VISIBLE
                }

            // Write new value to sync cache
            syncCache
                .edit()
                .putInt(SYNC_CACHE_KEY_DISPLAY_MODE, newMode.value)
                .putBoolean(SYNC_CACHE_KEY_MIGRATED, true)
                .apply()

            logInfo(
                "Migrated display mode preference: $wasImmersive -> ${newMode.name}",
                tag = "DisplayModePreference",
            )
        } else {
            // No old preference, just mark as migrated with default
            syncCache
                .edit()
                .putInt(SYNC_CACHE_KEY_DISPLAY_MODE, DisplayMode.SYSTEM_UI_VISIBLE.value)
                .putBoolean(SYNC_CACHE_KEY_MIGRATED, true)
                .apply()
        }
    }

    val displayModeFlow: Flow<DisplayMode> =
        dataStore.data.map { preferences ->
            // Try new key first, fall back to migration from old key
            preferences[KEY_DISPLAY_MODE]?.let { DisplayMode.fromValue(it) }
                ?: preferences[KEY_IMMERSIVE_MODE_ENABLED_LEGACY]?.let { wasImmersive ->
                    if (wasImmersive) DisplayMode.FULLSCREEN_IMMERSIVE else DisplayMode.SYSTEM_UI_VISIBLE
                }
                ?: DisplayMode.SYSTEM_UI_VISIBLE
        }

    /**
     * Returns the current display mode synchronously.
     * Uses SharedPreferences sync cache to avoid ANR.
     *
     * This is safe to call from the main thread during Activity.onCreate().
     */
    fun getDisplayModeSync(): DisplayMode =
        DisplayMode.fromValue(
            syncCache.getInt(SYNC_CACHE_KEY_DISPLAY_MODE, DisplayMode.SYSTEM_UI_VISIBLE.value),
        )

    /**
     * Sets the display mode preference.
     * Updates both DataStore and sync cache atomically.
     * Note: App restart required for changes to take full effect.
     */
    suspend fun setDisplayMode(mode: DisplayMode) {
        try {
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_DISPLAY_MODE] = mode.value
            }
            // Update sync cache for instant reads on next startup
            syncCache.edit().putInt(SYNC_CACHE_KEY_DISPLAY_MODE, mode.value).apply()
            logInfo(
                "Display mode preference saved: ${mode.name} (sync cache updated)",
                tag = "DisplayModePreference",
            )
        } catch (e: Exception) {
            logError("Failed to save display mode preference: $e", tag = "DisplayModePreference")
            throw e
        }
    }

}
