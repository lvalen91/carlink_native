package com.carlink.ui.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.capturePlaybackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "capture_playback_prefs",
)

/**
 * Preference store for Capture Playback settings.
 *
 * Manages:
 * - Playback mode enabled/disabled
 * - Selected capture file URI
 */
class CapturePlaybackPreference private constructor(
    private val context: Context,
) {
    companion object {
        private val KEY_PLAYBACK_ENABLED = booleanPreferencesKey("playback_enabled")
        private val KEY_CAPTURE_JSON_URI = stringPreferencesKey("capture_json_uri")
        private val KEY_CAPTURE_BIN_URI = stringPreferencesKey("capture_bin_uri")

        @Volatile
        private var instance: CapturePlaybackPreference? = null

        fun getInstance(context: Context): CapturePlaybackPreference =
            instance ?: synchronized(this) {
                instance ?: CapturePlaybackPreference(context.applicationContext).also {
                    instance = it
                }
            }
    }

    val playbackEnabledFlow: Flow<Boolean> =
        context.capturePlaybackDataStore.data.map { prefs ->
            prefs[KEY_PLAYBACK_ENABLED] ?: false
        }

    val captureJsonUriFlow: Flow<Uri?> =
        context.capturePlaybackDataStore.data.map { prefs ->
            prefs[KEY_CAPTURE_JSON_URI]?.let { Uri.parse(it) }
        }

    val captureBinUriFlow: Flow<Uri?> =
        context.capturePlaybackDataStore.data.map { prefs ->
            prefs[KEY_CAPTURE_BIN_URI]?.let { Uri.parse(it) }
        }

    suspend fun setPlaybackEnabled(enabled: Boolean) {
        context.capturePlaybackDataStore.edit { prefs ->
            prefs[KEY_PLAYBACK_ENABLED] = enabled
        }
    }

    suspend fun setCaptureFiles(
        jsonUri: Uri?,
        binUri: Uri?,
    ) {
        context.capturePlaybackDataStore.edit { prefs ->
            if (jsonUri != null) {
                prefs[KEY_CAPTURE_JSON_URI] = jsonUri.toString()
            } else {
                prefs.remove(KEY_CAPTURE_JSON_URI)
            }
            if (binUri != null) {
                prefs[KEY_CAPTURE_BIN_URI] = binUri.toString()
            } else {
                prefs.remove(KEY_CAPTURE_BIN_URI)
            }
        }
    }

    suspend fun clearCaptureFiles() {
        context.capturePlaybackDataStore.edit { prefs ->
            prefs.remove(KEY_CAPTURE_JSON_URI)
            prefs.remove(KEY_CAPTURE_BIN_URI)
        }
    }

    /**
     * Synchronous read for initialization (uses runBlocking - only use on init).
     */
    fun getPlaybackEnabledSync(): Boolean =
        runBlocking {
            context.capturePlaybackDataStore.data.first()[KEY_PLAYBACK_ENABLED] ?: false
        }

    fun getCaptureJsonUriSync(): Uri? =
        runBlocking {
            context.capturePlaybackDataStore.data.first()[KEY_CAPTURE_JSON_URI]?.let { Uri.parse(it) }
        }

    fun getCaptureBinUriSync(): Uri? =
        runBlocking {
            context.capturePlaybackDataStore.data.first()[KEY_CAPTURE_BIN_URI]?.let { Uri.parse(it) }
        }
}
