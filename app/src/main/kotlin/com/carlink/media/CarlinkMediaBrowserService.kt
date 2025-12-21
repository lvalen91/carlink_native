package com.carlink.media

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.carlink.BuildConfig

private const val TAG = "CARLINK_BROWSER"

/**
 * CarlinkMediaBrowserService - Minimal MediaBrowserService for AAOS media source registration.
 *
 * PURPOSE:
 * Registers the Carlink app as a selectable media source in the AAOS media source switcher.
 * This is a minimal implementation that:
 * - Provides an empty browse tree (no browsable content - we're a projection app)
 * - Exposes the MediaSession for playback control and now-playing info
 * - Allows AAOS to display the app in the media source selector
 *
 * WHY MINIMAL:
 * Unlike Spotify or local music apps, Carlink doesn't have its own media library.
 * It projects CarPlay/Android Auto from a connected phone via the CPC200-CCPA adapter.
 * The actual media content lives on the phone, not in this app.
 *
 * AAOS INTEGRATION:
 * ```
 * AAOS Media App ──► MediaBrowserService.onGetRoot() ──► Returns empty root
 *       │
 *       ├──► MediaBrowserService.onLoadChildren() ──► Returns empty list
 *       │
 *       └──► MediaSession (via sessionToken) ──► Now playing + controls
 * ```
 *
 * LIFECYCLE:
 * The service is started by the system when AAOS queries media sources.
 * It obtains the MediaSession token from the singleton holder that CarlinkPlugin populates.
 */
class CarlinkMediaBrowserService : MediaBrowserServiceCompat() {
    companion object {
        // Empty root ID for browse tree (no content to browse)
        private const val EMPTY_ROOT_ID = "carlink_empty_root"

        // Singleton holder for MediaSession token
        // Set by CarlinkPlugin when MediaSessionManager initializes
        @Volatile
        var mediaSessionToken: MediaSessionCompat.Token? = null
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onCreate")

        // Set the session token so AAOS can control playback
        mediaSessionToken?.let { token ->
            setSessionToken(token)
            if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Session token set")
        } ?: run {
            if (BuildConfig.DEBUG) Log.w(TAG, "[BROWSER_SERVICE] No session token available yet")
        }
    }

    /**
     * Called when a client (AAOS) wants to connect and browse.
     *
     * Returns a non-null BrowserRoot to allow connection.
     * The empty root ID indicates we have no browsable content.
     *
     * AAOS root hints are acknowledged but we return minimal structure
     * since we're a projection app without our own content library.
     */
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? {
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onGetRoot from: $clientPackageName (uid=$clientUid)")

        // Log AAOS root hints for debugging
        if (BuildConfig.DEBUG) {
            rootHints?.let { hints ->
                val maxRootChildren =
                    hints.getInt(
                        "android.media.browse.CONTENT_STYLE_SUPPORTED",
                        -1,
                    )
                Log.d(TAG, "[BROWSER_SERVICE] Root hints - maxChildren: $maxRootChildren")
            }
        }

        // Update session token if available (may have been set after onCreate)
        if (sessionToken == null) {
            mediaSessionToken?.let { token ->
                setSessionToken(token)
                if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Session token set in onGetRoot")
            }
        }

        // Return empty root - allows connection but indicates no browsable content
        // This is appropriate for projection apps like CarPlay/AA adapters
        return BrowserRoot(EMPTY_ROOT_ID, null)
    }

    /**
     * Called when a client wants to browse content under a parent ID.
     *
     * We return an empty list since we're a projection app without
     * our own media library. The actual content is on the connected phone.
     */
    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onLoadChildren for: $parentId")

        // Return empty list - no browsable content
        // AAOS will still show us as a media source, just without browse capability
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onDestroy")
        super.onDestroy()
    }
}
