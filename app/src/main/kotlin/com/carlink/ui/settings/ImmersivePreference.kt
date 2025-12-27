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
 * DataStore instance for immersive mode preference.
 * Following Android best practices: singleton instance at top level.
 */
private val Context.immersiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "carlink_immersive_preferences",
)

/**
 * Manages the immersive mode preference for the Carlink app.
 *
 * Overview:
 * Controls display behavior when projecting to GM AAOS infotainment systems.
 * Determines whether the app takes full-screen control (immersive) or defers
 * to the Android Automotive OS for display area management (non-immersive).
 *
 * Display Modes:
 * - Immersive (true): Fullscreen with hidden system UI bars. App controls
 *   entire display surface. Useful for maximum projection area.
 *
 * - Non-Immersive (false, default): AAOS manages display bounds, status bars,
 *   and navigation areas. Recommended for proper GM infotainment integration.
 *
 * Usage:
 * Setting changes persist across app sessions but require restart to apply.
 * Users toggle this via Settings UI when projection display issues occur or
 * when full-screen projection is desired.
 *
 * Technical Details:
 * - Storage: DataStore Preferences with SharedPreferences sync cache
 * - Sync Cache: SharedPreferences for instant startup reads (avoids ANR)
 * - Default: false (AAOS-managed for compatibility)
 * - Target: Android API 32+ (GM AAOS RPO: IOK)
 * - Restart required: Changes affect MainActivity window flags at launch
 *
 * ANR Prevention:
 * Uses SharedPreferences as a synchronous cache per Android Developer guidance.
 * DataStore I/O can block the main thread causing ANR if read synchronously.
 * SharedPreferences provides instant cached reads at startup while DataStore
 * remains the source of truth for Flow-based observation.
 *
 * Matches Flutter: immersive_preference.dart
 */
@Suppress("StaticFieldLeak") // Uses applicationContext, not Activity context - no leak
class ImmersivePreference private constructor(
    context: Context,
) {
    // Store applicationContext to avoid Activity leaks
    private val appContext: Context = context.applicationContext

    companion object {
        @Volatile
        private var instance: ImmersivePreference? = null

        fun getInstance(context: Context): ImmersivePreference =
            instance ?: synchronized(this) {
                instance ?: ImmersivePreference(context.applicationContext).also { instance = it }
            }

        private val KEY_IMMERSIVE_MODE_ENABLED = booleanPreferencesKey("immersive_mode_enabled")

        // SharedPreferences keys for sync cache (ANR prevention)
        private const val SYNC_CACHE_PREFS_NAME = "carlink_immersive_sync_cache"
        private const val SYNC_CACHE_KEY_ENABLED = "immersive_mode_enabled"
    }

    private val dataStore = appContext.immersiveDataStore

    // SharedPreferences sync cache for instant startup reads
    // Per Android Developer guidance: use SharedPreferences for synchronous access
    // to avoid blocking main thread with DataStore I/O
    private val syncCache =
        appContext.getSharedPreferences(
            SYNC_CACHE_PREFS_NAME,
            Context.MODE_PRIVATE,
        )

    /**
     * Flow for observing immersive mode state changes.
     */
    val isEnabledFlow: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[KEY_IMMERSIVE_MODE_ENABLED] ?: false
        }

    /**
     * Returns whether immersive fullscreen mode is enabled.
     * Uses synchronous SharedPreferences cache to avoid ANR.
     * Returns false by default, allowing AAOS to manage app scaling.
     *
     * This is safe to call from the main thread during Activity.onCreate().
     */
    fun isEnabledSync(): Boolean = syncCache.getBoolean(SYNC_CACHE_KEY_ENABLED, false)

    /**
     * Returns whether immersive fullscreen mode is enabled.
     * Returns false by default, allowing AAOS to manage app scaling.
     *
     * Note: Prefer isEnabledSync() for startup reads to avoid ANR.
     */
    suspend fun isEnabled(): Boolean =
        try {
            val preferences = dataStore.data.first()
            preferences[KEY_IMMERSIVE_MODE_ENABLED] ?: false
        } catch (e: Exception) {
            logError("Failed to read immersive preference: $e", tag = "ImmersivePreference")
            false
        }

    /**
     * Sets the immersive mode preference.
     * Updates both DataStore and sync cache atomically.
     * Note: App restart required for changes to take effect.
     */
    suspend fun setEnabled(enabled: Boolean) {
        try {
            // Update DataStore (source of truth)
            dataStore.edit { preferences ->
                preferences[KEY_IMMERSIVE_MODE_ENABLED] = enabled
            }
            // Update sync cache for instant reads on next startup
            syncCache.edit().putBoolean(SYNC_CACHE_KEY_ENABLED, enabled).apply()
            logInfo("Immersive preference saved: $enabled (sync cache updated)", tag = "ImmersivePreference")
        } catch (e: Exception) {
            logError("Failed to save immersive preference: $e", tag = "ImmersivePreference")
            throw e
        }
    }
}
