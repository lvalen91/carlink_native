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
import kotlinx.coroutines.flow.first
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

/**
 * DataStore instance for display mode preference.
 * Following Android best practices: singleton instance at top level.
 */
private val Context.displayModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_immersive_preferences", // Keep same name for migration compatibility
)

/**
 * Manages the display mode preference for the Carlink app.
 *
 * Overview:
 * Controls display behavior when projecting to GM AAOS infotainment systems.
 * Provides three modes for different levels of system UI visibility.
 *
 * Display Modes:
 * - SYSTEM_UI_VISIBLE (default): Both bars visible. AAOS manages display bounds.
 * - STATUS_BAR_HIDDEN: Status bar hidden, nav bar visible. Extra vertical space.
 * - FULLSCREEN_IMMERSIVE: Both bars hidden. Maximum projection area.
 *
 * Usage:
 * Setting changes can be previewed instantly in the Settings UI.
 * App restart is required for changes to take full effect (video dimensions).
 *
 * Technical Details:
 * - Storage: DataStore Preferences with SharedPreferences sync cache
 * - Sync Cache: SharedPreferences for instant startup reads (avoids ANR)
 * - Default: SYSTEM_UI_VISIBLE (AAOS-managed for compatibility)
 * - Target: Android API 32+ (GM AAOS RPO: IOK)
 * - Restart required: Changes affect MainActivity window flags at launch
 *
 * Migration:
 * Automatically migrates from old boolean immersive_mode_enabled preference:
 * - false -> SYSTEM_UI_VISIBLE
 * - true -> FULLSCREEN_IMMERSIVE
 */
@Suppress("StaticFieldLeak") // Uses applicationContext, not Activity context - no leak
class DisplayModePreference private constructor(
    context: Context,
) {
    // Store applicationContext to avoid Activity leaks
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

    /**
     * Flow for observing display mode changes.
     */
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
     * Returns the current display mode.
     *
     * Note: Prefer getDisplayModeSync() for startup reads to avoid ANR.
     */
    suspend fun getDisplayMode(): DisplayMode =
        try {
            val preferences = dataStore.data.first()
            preferences[KEY_DISPLAY_MODE]?.let { DisplayMode.fromValue(it) }
                ?: DisplayMode.SYSTEM_UI_VISIBLE
        } catch (e: Exception) {
            logError("Failed to read display mode preference: $e", tag = "DisplayModePreference")
            DisplayMode.SYSTEM_UI_VISIBLE
        }

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

    // ==================== Backward Compatibility ====================

    /**
     * Returns whether fullscreen immersive mode is enabled.
     * Provided for backward compatibility with existing code.
     *
     * @deprecated Use getDisplayModeSync() instead for full mode support.
     */
    @Deprecated(
        message = "Use getDisplayModeSync() instead",
        replaceWith = ReplaceWith("getDisplayModeSync() == DisplayMode.FULLSCREEN_IMMERSIVE"),
    )
    fun isImmersiveModeEnabled(): Boolean = getDisplayModeSync() == DisplayMode.FULLSCREEN_IMMERSIVE
}
