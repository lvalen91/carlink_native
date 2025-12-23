package com.carlink.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import com.carlink.BuildConfig
import com.carlink.MainActivity
import com.carlink.R

private const val TAG = "CARLINK_BROWSER"

/**
 * CarlinkMediaBrowserService - MediaBrowserService with foreground service support.
 *
 * PURPOSE:
 * Registers the Carlink app as a selectable media source in the AAOS media source switcher
 * AND maintains foreground priority when the adapter is connected to ensure:
 * - USB connection remains active when app is backgrounded
 * - Video/audio streaming continues uninterrupted
 * - Heartbeat and frame requests keep running
 *
 * FOREGROUND SERVICE:
 * When startConnectionForeground() is called:
 * - Service enters foreground mode with a silent, persistent notification
 * - Process priority elevated to prevent caching/killing
 * - All timers, USB transfers, and audio continue normally
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
 * - Service started by system when AAOS queries media sources
 * - startConnectionForeground() called when adapter connects
 * - stopConnectionForeground() called when adapter disconnects
 */
class CarlinkMediaBrowserService : MediaBrowserServiceCompat() {
    companion object {
        // Empty root ID for browse tree (no content to browse)
        private const val EMPTY_ROOT_ID = "carlink_empty_root"

        // Notification constants
        private const val NOTIFICATION_CHANNEL_ID = "carlink_connection"
        private const val NOTIFICATION_ID = 1001

        // Singleton holder for MediaSession token
        // Set by CarlinkPlugin when MediaSessionManager initializes
        @Volatile
        var mediaSessionToken: MediaSessionCompat.Token? = null

        // Singleton instance for foreground service control
        @Volatile
        private var instance: CarlinkMediaBrowserService? = null

        /**
         * Start foreground service mode when adapter connects.
         * Called from CarlinkManager when entering STREAMING state.
         */
        fun startConnectionForeground(context: Context) {
            // Start the service if not running
            val intent = Intent(context, CarlinkMediaBrowserService::class.java)
            intent.action = "ACTION_START_FOREGROUND"
            context.startForegroundService(intent)
        }

        /**
         * Stop foreground service mode when adapter disconnects.
         * Called from CarlinkManager when entering DISCONNECTED state.
         */
        @Suppress("UNUSED_PARAMETER") // API symmetry with startConnectionForeground
        fun stopConnectionForeground(context: Context) {
            instance?.stopForegroundMode()
        }
    }

    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == "ACTION_START_FOREGROUND") {
            startForegroundMode()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] onDestroy")
        instance = null
        stopForegroundMode()
        super.onDestroy()
    }

    // ==================== Foreground Service Methods ====================

    /**
     * Create notification channel for foreground service.
     * Uses IMPORTANCE_LOW for silent notifications that don't interrupt.
     */
    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Connection Status",
                NotificationManager.IMPORTANCE_LOW, // Silent - no sound, no popup
            ).apply {
                description = "Shows when Carlink adapter is connected"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Notification channel created")
    }

    /**
     * Build the foreground notification.
     * Uses MediaStyle for integration with system media controls.
     */
    private fun buildNotification(): Notification {
        // Intent to open app when notification tapped
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Carlink")
                .setContentText("Adapter connected")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent)

        // Add MediaStyle if session token available
        mediaSessionToken?.let { token: MediaSessionCompat.Token ->
            builder.setStyle(
                androidx.media.app.NotificationCompat
                    .MediaStyle()
                    .setMediaSession(token),
            )
        }

        return builder.build()
    }

    /**
     * Enter foreground mode with persistent notification.
     * Called when adapter connects and streaming starts.
     */
    private fun startForegroundMode() {
        if (isForeground) return

        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Entered foreground mode")
        } catch (e: Exception) {
            Log.e(TAG, "[BROWSER_SERVICE] Failed to start foreground: ${e.message}")
        }
    }

    /**
     * Exit foreground mode.
     * Called when adapter disconnects.
     */
    private fun stopForegroundMode() {
        if (!isForeground) return

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            if (BuildConfig.DEBUG) Log.d(TAG, "[BROWSER_SERVICE] Exited foreground mode")
        } catch (e: Exception) {
            Log.e(TAG, "[BROWSER_SERVICE] Failed to stop foreground: ${e.message}")
        }
    }
}
