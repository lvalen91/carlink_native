package com.carlink.media

import android.content.Context
import android.graphics.BitmapFactory
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.carlink.BuildConfig
import com.carlink.util.LogCallback

private const val TAG = "CARLINK_MEDIA"

/**
 * MediaSessionManager - Manages MediaSession for AAOS media source integration.
 *
 * PURPOSE:
 * Exposes the Carlink app as a selectable media source on Android Automotive OS (AAOS).
 * This enables:
 * - App appears in AAOS media source selector/switcher
 * - Now-playing metadata displayed in system UI, cluster, and widgets
 * - Steering wheel and system media controls routed to the app
 * - Album art displayed in system media interfaces
 *
 * ARCHITECTURE:
 * ```
 * CarPlay/AA Projection (CPC200-CCPA)
 *         │
 *         ▼
 *   CarlinkMediaInfo (from adapter)
 *         │
 *         ▼
 *   MediaSessionManager
 *         │
 *         ├── MediaSession (now playing, playback state)
 *         │
 *         └── MediaSession.Callback (steering wheel → SendCommand)
 *                    │
 *                    ▼
 *              AAOS System UI
 * ```
 *
 * SUPPORTED CONTROLS:
 * - Play/Pause: Toggles playback on connected phone
 * - Next/Previous: Track skip on connected phone
 * - Stop: Stops playback
 *
 * THREAD SAFETY:
 * MediaSession callbacks arrive on the main thread.
 * All public methods should be called from the main thread.
 */
class MediaSessionManager(
    private val context: Context,
    private val logCallback: LogCallback,
) {
    private var mediaSession: MediaSessionCompat? = null

    // Callback for routing media button events back to adapter
    private var mediaControlCallback: MediaControlCallback? = null

    // Current playback state
    private var isPlaying: Boolean = false
    private var currentPosition: Long = 0L

    /**
     * Callback interface for media control events from AAOS/steering wheel.
     */
    interface MediaControlCallback {
        fun onPlay()

        fun onPause()

        fun onStop()

        fun onSkipToNext()

        fun onSkipToPrevious()
    }

    /**
     * Initialize the MediaSession.
     *
     * Call this during plugin attachment to register with the system.
     */
    fun initialize() {
        if (mediaSession != null) {
            log("[MEDIA_SESSION] Already initialized")
            return
        }

        try {
            mediaSession =
                MediaSessionCompat(context, "CarlinkMediaSession").apply {
                    // Set callback for handling media button events
                    setCallback(mediaSessionCallback)

                    // Set supported actions
                    setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_NONE))

                    // Set initial empty metadata
                    setMetadata(
                        MediaMetadataCompat
                            .Builder()
                            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Carlink")
                            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                            .build(),
                    )

                    // Activate the session to make it visible to the system
                    isActive = true
                }

            log("[MEDIA_SESSION] Initialized and activated")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Failed to initialize: ${e.message}")
        }
    }

    /**
     * Release the MediaSession.
     *
     * Call this during plugin detachment.
     */
    fun release() {
        try {
            mediaSession?.let { session ->
                session.isActive = false
                session.release()
            }
            mediaSession = null
            mediaControlCallback = null
            log("[MEDIA_SESSION] Released")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Error during release: ${e.message}")
        }
    }

    /**
     * Set the callback for receiving media control events.
     */
    fun setMediaControlCallback(callback: MediaControlCallback?) {
        mediaControlCallback = callback
    }

    /**
     * Get the MediaSession token for use with MediaBrowserService.
     */
    fun getSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken

    /**
     * Update now-playing metadata from CarPlay/AA projection.
     *
     * @param title Song title or lyrics
     * @param artist Artist name
     * @param album Album name
     * @param appName Source app name (e.g., "Spotify", "Apple Music")
     * @param albumArt Album cover image bytes (JPEG/PNG)
     * @param duration Track duration in milliseconds (0 if unknown)
     */
    fun updateMetadata(
        title: String?,
        artist: String?,
        album: String?,
        appName: String?,
        albumArt: ByteArray?,
        duration: Long = 0L,
    ) {
        val session = mediaSession ?: return

        try {
            val builder =
                MediaMetadataCompat
                    .Builder()
                    .putString(
                        MediaMetadataCompat.METADATA_KEY_TITLE,
                        title ?: "Unknown",
                    ).putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        artist ?: "Unknown",
                    ).putString(
                        MediaMetadataCompat.METADATA_KEY_ALBUM,
                        album ?: "",
                    ).putString(
                        MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                        appName ?: "Carlink",
                    )

            // Add duration if known
            if (duration > 0) {
                builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            }

            // Decode and add album art
            albumArt?.let { bytes ->
                try {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                    }
                } catch (e: Exception) {
                    log("[MEDIA_SESSION] Failed to decode album art: ${e.message}")
                }
            }

            session.setMetadata(builder.build())

            log("[MEDIA_SESSION] Metadata updated: $title - $artist")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Failed to update metadata: ${e.message}")
        }
    }

    /**
     * Update playback state.
     *
     * @param playing Whether media is currently playing
     * @param position Current playback position in milliseconds
     */
    fun updatePlaybackState(
        playing: Boolean,
        position: Long = 0L,
    ) {
        val session = mediaSession ?: return

        isPlaying = playing
        currentPosition = position

        val state =
            if (playing) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }

        try {
            session.setPlaybackState(buildPlaybackState(state, position))
            log("[MEDIA_SESSION] Playback state: ${if (playing) "PLAYING" else "PAUSED"}")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Failed to update playback state: ${e.message}")
        }
    }

    /**
     * Set playback state to stopped/idle.
     */
    fun setStateStopped() {
        val session = mediaSession ?: return

        isPlaying = false
        currentPosition = 0L

        try {
            session.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_STOPPED))
            log("[MEDIA_SESSION] Playback state: STOPPED")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Failed to set stopped state: ${e.message}")
        }
    }

    /**
     * Set state to connecting/buffering.
     */
    fun setStateConnecting() {
        val session = mediaSession ?: return

        try {
            session.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_CONNECTING))

            // Set connecting metadata
            session.setMetadata(
                MediaMetadataCompat
                    .Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Connecting...")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Carlink")
                    .build(),
            )

            log("[MEDIA_SESSION] Playback state: CONNECTING")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Failed to set connecting state: ${e.message}")
        }
    }

    /**
     * Build a PlaybackState with the supported actions.
     */
    private fun buildPlaybackState(
        state: Int,
        position: Long = 0L,
    ): PlaybackStateCompat {
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        return PlaybackStateCompat
            .Builder()
            .setActions(actions)
            .setState(state, position, 1.0f)
            .build()
    }

    /**
     * MediaSession callback for handling media button events from AAOS/steering wheel.
     */
    private val mediaSessionCallback =
        object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                log("[MEDIA_SESSION] onPlay received")
                mediaControlCallback?.onPlay()
            }

            override fun onPause() {
                log("[MEDIA_SESSION] onPause received")
                mediaControlCallback?.onPause()
            }

            override fun onStop() {
                log("[MEDIA_SESSION] onStop received")
                mediaControlCallback?.onStop()
            }

            override fun onSkipToNext() {
                log("[MEDIA_SESSION] onSkipToNext received")
                mediaControlCallback?.onSkipToNext()
            }

            override fun onSkipToPrevious() {
                log("[MEDIA_SESSION] onSkipToPrevious received")
                mediaControlCallback?.onSkipToPrevious()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: android.content.Intent?): Boolean {
                log("[MEDIA_SESSION] Media button event received")
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        logCallback.log(message)
    }
}
