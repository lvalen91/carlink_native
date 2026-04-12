package com.carlink.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.carlink.BuildConfig
import com.carlink.MainActivity
import com.carlink.util.LogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    // Dedup: last values pushed to MediaSession
    private var lastPushedPlaying: Boolean? = null
    private var lastPushedPositionMs: Long = 0L
    private var lastPushedTimeNanos: Long = 0L

    /** Position must drift more than this from AAOS-extrapolated value to trigger a push (seek). */
    private val seekThresholdMs: Long = 2_000L

    // Album art cache — writes downscaled JPEG files to a FileProvider-backed
    // directory. Used additively alongside the inline METADATA_KEY_ALBUM_ART
    // Bitmap (see publishMetadata below for why the dual path is necessary).
    private val albumArtCache = AlbumArtCache(context)

    // Last-published art hash → (inline Bitmap, URI). Inline Bitmap is the PRIMARY
    // carrier (renders immediately on AAOS Media Center / ClusterHome). URI is
    // ADDITIVE — published only after AlbumArtCache confirms the backing file is
    // on disk. WHY dual-path: publishing METADATA_KEY_ALBUM_ART_URI alone without
    // a reachable file causes AAOS renderers to treat the entire metadata bundle
    // as corrupt — they suppress the whole card including title/artist text, not
    // just the art. Keeping the inline Bitmap as primary guarantees the card
    // always renders even if the URI pipeline fails.
    private var lastArtHash: Int = 0
    private var lastArtUri: Uri? = null
    private var lastInlineArt: Bitmap? = null
    private var lastArtJob: Job? = null

    // Scope for off-main bitmap decode + file write. Cancelled in release().
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Lock for thread-safe MediaSession access (USB thread + main thread)
    private val sessionLock = Any()

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

                    // Link MediaSession to our Activity so AAOS treats the media
                    // controls and the app UI as a single unit. Without this, newer
                    // AAOS firmware demotes the app to a background media source.
                    setSessionActivity(
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )

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
            scope.cancel()
            lastArtJob = null
            mediaSession?.let { session ->
                session.isActive = false
                session.release()
            }
            mediaSession = null
            mediaControlCallback = null
            lastArtHash = 0
            lastArtUri = null
            lastInlineArt?.let { if (!it.isRecycled) it.recycle() }
            lastInlineArt = null
            lastPushedPlaying = null
            lastPushedPositionMs = 0L
            lastPushedTimeNanos = 0L
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
        val hash = albumArt?.let { if (it.isEmpty()) 0 else it.contentHashCode() } ?: 0

        // PRIMARY path: decode a small inline Bitmap synchronously on the caller
        // thread (≤256px RGB_565 ≈ 130 KB, well under the 1 MB Binder cap even
        // across several subscribed controllers). AAOS Media Center + ClusterHome
        // render this immediately. If the subsequent URI write fails, the card
        // still renders text + art.
        // Falling back to lastInlineArt on null/empty bytes keeps the prior
        // song's art visible during AA's ~130ms gap between the text-only JSON
        // frame (song change detected) and the follow-up standalone ALBUM_COVER_AA
        // frame — avoids a brief blank flicker on track change.
        val inlineArt: Bitmap? = when {
            albumArt == null || albumArt.isEmpty() -> lastInlineArt  // keep last
            hash == lastArtHash -> lastInlineArt                     // same bytes
            else -> try {
                albumArtCache.decodeDisplayIcon(albumArt)
            } catch (e: Exception) {
                log("[MEDIA_SESSION] inline decode failed: ${e.message}")
                null
            }
        }
        if (inlineArt != null && inlineArt !== lastInlineArt) {
            lastInlineArt?.let { if (!it.isRecycled) it.recycle() }
            lastInlineArt = inlineArt
        }

        // URI on the synchronous path: only reuse if this frame's hash matches the
        // previously-published art. Never publish a stale-song URI for new bytes.
        val artUriForNow: Uri? = if (hash == lastArtHash) lastArtUri else null

        publishMetadata(
            session = synchronized(sessionLock) { mediaSession } ?: return,
            title = title,
            artist = artist,
            album = album,
            appName = appName,
            duration = duration,
            artUri = artUriForNow,
            inlineArt = inlineArt,
        )

        // ADDITIVE path: off-main decode + file write. URI keys only get published
        // after we verify the backing file actually exists and is non-empty.
        if (albumArt != null && hash != lastArtHash) {
            lastArtJob?.cancel()
            val titleCapture = title
            val artistCapture = artist
            val albumCapture = album
            val appNameCapture = appName
            val durationCapture = duration
            lastArtJob = scope.launch {
                val uri = try {
                    albumArtCache.put(albumArt)
                } catch (e: Exception) {
                    log("[MEDIA_SESSION] AlbumArtCache.put failed: ${e.message}")
                    null
                }
                if (uri == null) return@launch  // inline Bitmap already renders; skip URI publish
                mainHandler.post {
                    synchronized(sessionLock) {
                        val session = mediaSession ?: return@synchronized
                        lastArtHash = hash
                        lastArtUri = uri
                        publishMetadata(
                            session = session,
                            title = titleCapture,
                            artist = artistCapture,
                            album = albumCapture,
                            appName = appNameCapture,
                            duration = durationCapture,
                            artUri = uri,
                            inlineArt = lastInlineArt,
                        )
                    }
                }
            }
        }
        log("[MEDIA_SESSION] Metadata updated: $title - $artist (inlineArt=${inlineArt != null}, uriPending=${albumArt != null && hash != lastArtHash})")
    }

    /**
     * Build and publish a [MediaMetadataCompat] with the given fields. Keeps the
     * URI-based album art flow in one place.
     */
    private fun publishMetadata(
        session: MediaSessionCompat,
        title: String?,
        artist: String?,
        album: String?,
        appName: String?,
        duration: Long,
        artUri: Uri?,
        inlineArt: Bitmap? = null,
    ) {
        synchronized(sessionLock) {
            try {
                val builder = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown")
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album ?: "")
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, appName ?: "Carlink")
                if (duration > 0) {
                    builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                }
                // PRIMARY art — inline Bitmap via METADATA_KEY_ALBUM_ART +
                // METADATA_KEY_DISPLAY_ICON. This is the guaranteed render path:
                // AAOS Media Center + ClusterHome both pick these up first. A
                // dangling URI (file not yet written or write failed) caused
                // prior builds to render the card entirely blank (no text either);
                // keeping the inline Bitmap present ensures the card always renders.
                if (inlineArt != null && !inlineArt.isRecycled) {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, inlineArt)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, inlineArt)
                }
                // ADDITIVE art — URI keys are only set by callers that have
                // already verified the FileProvider backing file exists and is
                // non-empty on disk. Controllers that prefer URIs pick these up.
                if (artUri != null) {
                    val s = artUri.toString()
                    builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, s)
                    builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, s)
                    builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, s)
                }
                session.setMetadata(builder.build())
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to publish metadata: ${e.message}")
            }
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
        synchronized(sessionLock) {
            val session = mediaSession ?: return

            isPlaying = playing
            currentPosition = position

            // Deduplicate: only push to MediaSession on actual state change or seek.
            // AAOS extrapolates position from (position + speed * elapsed), so continuous
            // position ticks during normal playback are redundant.
            val stateChanged = playing != lastPushedPlaying
            val now = System.nanoTime()
            val elapsedMs = (now - lastPushedTimeNanos) / 1_000_000L
            val expectedPosition =
                if (lastPushedPlaying == true) {
                    lastPushedPositionMs + elapsedMs
                } else {
                    lastPushedPositionMs
                }
            val seekDetected = kotlin.math.abs(position - expectedPosition) > seekThresholdMs

            if (!stateChanged && !seekDetected) return

            val state =
                if (playing) {
                    PlaybackStateCompat.STATE_PLAYING
                } else {
                    PlaybackStateCompat.STATE_PAUSED
                }

            try {
                session.setPlaybackState(buildPlaybackState(state, position))
                lastPushedPlaying = playing
                lastPushedPositionMs = position
                lastPushedTimeNanos = now
                val reason = if (stateChanged) "state change" else "seek"
                log("[MEDIA_SESSION] Playback state: ${if (playing) "PLAYING" else "PAUSED"} ($reason, pos=${position}ms)")
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to update playback state: ${e.message}")
            }
        }
    }

    /**
     * Set playback state to stopped/idle.
     */
    fun setStateStopped() {
        synchronized(sessionLock) {
            val session = mediaSession ?: return

            isPlaying = false
            currentPosition = 0L
            lastArtHash = 0
            lastArtUri = null
            lastInlineArt?.let { if (!it.isRecycled) it.recycle() }
            lastInlineArt = null
            lastArtJob?.cancel()
            lastArtJob = null
            lastPushedPlaying = null
            lastPushedPositionMs = 0L
            lastPushedTimeNanos = 0L

            try {
                session.setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_STOPPED))

                // Clear metadata to prevent stale now-playing in cluster/system UI
                session.setMetadata(
                    MediaMetadataCompat
                        .Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Carlink")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                        .build(),
                )

                log("[MEDIA_SESSION] Playback state: STOPPED, metadata cleared")
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to set stopped state: ${e.message}")
            }
        }
    }

    /**
     * Set state to connecting/buffering.
     */
    fun setStateConnecting() {
        synchronized(sessionLock) {
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

        // Speed must be 0f when not playing — AAOS extrapolates position
        // from (state + speed + position + timestamp), so 1.0f while paused
        // causes the seek bar to drift in cluster and system media UI.
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0f

        return PlaybackStateCompat
            .Builder()
            .setActions(actions)
            .setState(state, position, speed)
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
