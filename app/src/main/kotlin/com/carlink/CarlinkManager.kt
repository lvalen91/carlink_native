package com.carlink

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.view.Surface
import com.carlink.audio.DualStreamAudioManager
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logVideoUsb
import com.carlink.logging.logWarn
import com.carlink.media.CarlinkMediaBrowserService
import com.carlink.media.MediaSessionManager
import com.carlink.platform.AudioConfig
import com.carlink.platform.PlatformDetector
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.AdapterDriver
import com.carlink.protocol.AudioCommand
import com.carlink.protocol.AudioDataMessage
import com.carlink.protocol.CommandMapping
import com.carlink.protocol.CommandMessage
import com.carlink.protocol.MediaDataMessage
import com.carlink.protocol.Message
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.PhoneType
import com.carlink.protocol.PluggedMessage
import com.carlink.protocol.TouchAction
import com.carlink.protocol.UnpluggedMessage
import com.carlink.protocol.VideoDataMessage
import com.carlink.protocol.VideoStreamingSignal
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.MicSourceConfig
import com.carlink.ui.settings.WiFiBandConfig
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AppExecutors
import com.carlink.util.LogCallback
import com.carlink.video.H264Renderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference

/**
 * Main Carlink Manager
 *
 * Central orchestrator for the Carlink native application:
 * - USB device lifecycle management
 * - Protocol communication via AdapterDriver
 * - Video rendering via H264Renderer
 * - Audio playback via DualStreamAudioManager
 * - Microphone capture for Siri/calls
 * - MediaSession integration for AAOS
 *
 * Ported from: lib/carlink.dart
 */
class CarlinkManager(
    private val context: Context,
    initialConfig: AdapterConfig = AdapterConfig.DEFAULT,
) {
    // Config can be updated when actual surface dimensions are known
    private var config: AdapterConfig = initialConfig

    companion object {
        private const val USB_WAIT_PERIOD_MS = 3000L
        private const val PAIR_TIMEOUT_MS = 15000L

        // Recovery constants (matches Flutter CarlinkPlugin.kt)
        private const val RESET_THRESHOLD = 3
        private const val RESET_WINDOW_MS = 30_000L

        // Auto-reconnect constants
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_RECONNECT_DELAY_MS = 2000L // Start with 2 seconds
        private const val MAX_RECONNECT_DELAY_MS = 30000L // Cap at 30 seconds

        // Surface debouncing - wait for size to stabilize before updating codec
        private const val SURFACE_DEBOUNCE_MS = 150L
    }

    /**
     * Connection state enum.
     */
    enum class State {
        DISCONNECTED,
        CONNECTING,
        DEVICE_CONNECTED,
        STREAMING,
    }

    /**
     * Media metadata information.
     */
    data class MediaInfo(
        val songTitle: String?,
        val songArtist: String?,
        val albumName: String?,
        val appName: String?,
        val albumCover: ByteArray?,
    )

    /**
     * Callback interface for Carlink events.
     */
    interface Callback {
        fun onStateChanged(state: State)

        fun onMediaInfoChanged(mediaInfo: MediaInfo)

        fun onLogMessage(message: String)

        fun onHostUIPressed()
    }

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Main)

    // Current state
    private val currentState = AtomicReference(State.DISCONNECTED)
    val state: State get() = currentState.get()

    // Callback
    private var callback: Callback? = null

    // USB
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDeviceWrapper? = null

    // Wake lock to prevent CPU sleep during USB streaming
    // PARTIAL_WAKE_LOCK keeps CPU running but allows screen to turn off
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Carlink::UsbStreamingWakeLock",
        )

    // Protocol
    private var adapterDriver: AdapterDriver? = null

    // Video
    private var h264Renderer: H264Renderer? = null
    private var videoSurface: Surface? = null
    private var videoInitialized = false // Track if video subsystem is ready for decoding
    private var lastVideoDiscardWarningTime = 0L // Throttle discard warnings

    // Audio
    private var audioManager: DualStreamAudioManager? = null
    private var audioInitialized = false

    // Microphone
    private var microphoneManager: MicrophoneCaptureManager? = null
    private var isMicrophoneCapturing = false
    private var currentMicDecodeType = 5 // 16kHz mono
    private var currentMicAudioType = 3 // Siri/voice input
    private var micSendTimer: Timer? = null

    // MediaSession
    private var mediaSessionManager: MediaSessionManager? = null

    // Timers
    private var pairTimeout: Timer? = null
    private var frameIntervalJob: Job? = null

    // Phone type tracking for frame interval decisions
    private var currentPhoneType: PhoneType? = null

    // Recovery tracking (matches Flutter CarlinkPlugin.kt)
    private var lastResetTime: Long = 0
    private var consecutiveResets: Int = 0

    // Auto-reconnect on USB disconnect
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0

    // Surface update debouncing - prevents repeated codec recreation during rapid surface size changes
    private var surfaceUpdateJob: Job? = null
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var pendingCallback: Callback? = null

    // Media metadata tracking
    private var lastMediaSongName: String? = null
    private var lastMediaArtistName: String? = null
    private var lastMediaAlbumName: String? = null
    private var lastMediaAppName: String? = null
    private var lastAlbumCover: ByteArray? = null

    /** Clears cached media metadata to prevent stale data on reconnect. */
    private fun clearCachedMediaMetadata() {
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaAppName = null
        lastAlbumCover = null
    }

    // Executors
    private val executors = AppExecutors()

    // LogCallback for Java components
    private val logCallback = LogCallback { message -> log(message) }

    /**
     * Initialize the manager with a Surface and actual surface dimensions.
     *
     * Uses SurfaceView's Surface directly for optimal HWC overlay rendering.
     * This bypasses GPU composition for lower latency and power consumption.
     *
     * @param surface The Surface from SurfaceView to render video to
     * @param surfaceWidth Actual width of the surface in pixels
     * @param surfaceHeight Actual height of the surface in pixels
     * @param callback Callbacks for state changes and events
     */
    fun initialize(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        callback: Callback,
    ) {
        // Round to even numbers for H.264 compatibility
        val evenWidth = surfaceWidth and 1.inv()
        val evenHeight = surfaceHeight and 1.inv()

        // Update config with actual surface dimensions
        // This ensures adapter is configured for the correct resolution based on actual layout
        // (with or without system bar insets depending on immersive mode)
        if (config.width != evenWidth || config.height != evenHeight) {
            logInfo(
                "[RES] Updating config from ${config.width}x${config.height} to ${evenWidth}x$evenHeight " +
                    "(actual surface size)",
                tag = Logger.Tags.VIDEO,
            )
            config = config.copy(width = evenWidth, height = evenHeight)
        }

        // LIFECYCLE FIX: If renderer exists, always update surface via setOutputSurface().
        //
        // CRITICAL: Do NOT use reference equality (===) to check if Surface is "the same".
        // After app goes to background, the Surface Java object may be the same reference,
        // but the underlying native BufferQueue is DESTROYED and recreated.
        // The codec will be rendering to a dead buffer → "BufferQueue has been abandoned" error.
        //
        // Solution: Always call setOutputSurface() when initialize() is called with an existing
        // renderer. This ensures the codec always has a valid native surface.
        // See: https://developer.android.com/reference/android/media/MediaCodec#setOutputSurface
        //
        // DEBOUNCE FIX: Surface size changes rapidly during layout (996→960→965→969→992).
        // Each change triggers codec recreation. Debounce to wait for size stabilization.
        if (h264Renderer != null) {
            // Store pending values
            pendingSurface = surface
            pendingSurfaceWidth = evenWidth
            pendingSurfaceHeight = evenHeight
            pendingCallback = callback

            // Cancel any pending update
            surfaceUpdateJob?.cancel()

            // Debounce: wait for surface size to stabilize before updating codec
            surfaceUpdateJob =
                scope.launch {
                    delay(SURFACE_DEBOUNCE_MS)

                    // Use the latest pending values after debounce
                    val finalSurface = pendingSurface ?: return@launch
                    val finalCallback = pendingCallback ?: return@launch

                    logInfo(
                        "[LIFECYCLE] Surface stabilized at ${pendingSurfaceWidth}x$pendingSurfaceHeight - updating codec",
                        tag = Logger.Tags.VIDEO,
                    )

                    this@CarlinkManager.callback = finalCallback
                    this@CarlinkManager.videoSurface = finalSurface
                    // Resume with new surface - this calls setOutputSurface() internally
                    h264Renderer?.resume(finalSurface)
                }
            return
        }

        // First-time initialization - create new renderer
        this.callback = callback
        this.videoSurface = surface

        logInfo(
            "[RES] Initializing with surface ${evenWidth}x$evenHeight @ ${config.fps}fps, ${config.dpi}dpi",
            tag = Logger.Tags.VIDEO,
        )

        // Detect platform for optimal audio configuration
        // Pass user-configured sample rate from AdapterConfig (overrides platform default)
        val platformInfo = PlatformDetector.detect(context)
        val audioConfig = AudioConfig.forPlatform(platformInfo, userSampleRate = config.sampleRate)

        logInfo(
            "[PLATFORM] Using AudioConfig: sampleRate=${audioConfig.sampleRate}Hz, " +
                "bufferMult=${audioConfig.bufferMultiplier}x, prefill=${audioConfig.prefillThresholdMs}ms",
            tag = Logger.Tags.AUDIO,
        )
        logInfo(
            "[PLATFORM] Using VideoDecoder: ${platformInfo.hardwareH264DecoderName ?: "generic (createDecoderByType)"}",
            tag = Logger.Tags.VIDEO,
        )

        // Initialize H264 renderer with Surface for direct HWC rendering
        // Surface comes from SurfaceView - no GPU composition overhead
        // Pass platform-detected codec name for optimal hardware decoder selection
        h264Renderer =
            H264Renderer(
                context,
                config.width,
                config.height,
                surface,
                logCallback,
                executors,
                platformInfo.hardwareH264DecoderName,
            )

        // Set keyframe callback - after codec reset, we need to request a new IDR frame
        // from the adapter. Without SPS/PPS + keyframe, the decoder cannot produce output.
        h264Renderer?.setKeyframeRequestCallback {
            logInfo("[KEYFRAME] Requesting keyframe after codec reset", tag = Logger.Tags.VIDEO)
            adapterDriver?.sendCommand(CommandMapping.FRAME)
        }

        // Start the H264 renderer to initialize MediaCodec and begin decoding
        // This MUST be called before processData() - MediaCodec requires start() before queueInputBuffer()
        h264Renderer?.start()

        // Mark video as initialized - videoProcessor will now process frames instead of discarding
        videoInitialized = true
        logInfo("Video subsystem initialized and ready for decoding", tag = Logger.Tags.VIDEO)

        // Initialize audio manager with platform-specific config
        audioManager =
            DualStreamAudioManager(
                logCallback,
                audioConfig,
            )

        // Initialize microphone manager
        microphoneManager =
            MicrophoneCaptureManager(
                context,
                logCallback,
            )

        // Initialize MediaSession only for ADAPTER audio mode (not Bluetooth)
        // In Bluetooth mode, audio goes through phone BT → car stereo directly,
        // so we don't want this app to appear as an active media source in AAOS.
        // This prevents the vehicle from switching audio source to the app when
        // the user opens or returns to it.
        if (!config.audioTransferMode) {
            mediaSessionManager =
                MediaSessionManager(context, logCallback).apply {
                    initialize()
                    setMediaControlCallback(
                        object : MediaSessionManager.MediaControlCallback {
                            override fun onPlay() {
                                sendKey(CommandMapping.PLAY)
                            }

                            override fun onPause() {
                                sendKey(CommandMapping.PAUSE)
                            }

                            override fun onStop() {
                                sendKey(CommandMapping.PAUSE)
                            }

                            override fun onSkipToNext() {
                                sendKey(CommandMapping.NEXT)
                            }

                            override fun onSkipToPrevious() {
                                sendKey(CommandMapping.PREV)
                            }
                        },
                    )
                }
            // Share session token with MediaBrowserService for AAOS cluster integration
            CarlinkMediaBrowserService.mediaSessionToken = mediaSessionManager?.getSessionToken()
            logInfo("MediaSession initialized (ADAPTER audio mode)", tag = Logger.Tags.ADAPTR)
        } else {
            logInfo("MediaSession skipped (BLUETOOTH audio mode - audio via phone BT)", tag = Logger.Tags.ADAPTR)
        }

        logInfo("CarlinkManager initialized", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Start connection to the adapter.
     */
    suspend fun start() {
        // Guard: Ensure H264Renderer is initialized before starting connection
        // This prevents video data from being discarded when app starts via MediaBrowserService
        // before MainActivity/Surface is ready
        if (h264Renderer == null) {
            logWarn(
                "H264Renderer not initialized - Surface not ready. " +
                    "Video will be discarded until initialize() is called with valid Surface.",
                tag = Logger.Tags.VIDEO,
            )
        }

        setState(State.CONNECTING)

        // Stop any existing connection
        if (adapterDriver != null) {
            stop()
        }

        // Reset video renderer (only if initialized)
        h264Renderer?.reset()

        // Initialize audio
        if (!audioInitialized) {
            audioInitialized = audioManager?.initialize() ?: false
            if (audioInitialized) {
                logInfo("Audio playback initialized", tag = Logger.Tags.AUDIO)
            }
        }

        // Find device
        log("Searching for Carlinkit device...")
        val device = findDevice()
        if (device == null) {
            logError("Failed to find Carlinkit device", tag = Logger.Tags.USB)
            setState(State.DISCONNECTED)
            return
        }

        log("Device found, opening")
        usbDevice = device

        if (!device.openWithPermission()) {
            logError("Failed to open USB device", tag = Logger.Tags.USB)
            setState(State.DISCONNECTED)
            return
        }

        // Create video processor for direct USB -> ring buffer data flow
        // This bypasses message parsing for zero-copy performance (matches Flutter architecture)
        val videoProcessor = createVideoProcessor()

        // Create and start adapter driver
        adapterDriver =
            AdapterDriver(
                usbDevice = device,
                messageHandler = ::handleMessage,
                errorHandler = ::handleError,
                logCallback = ::log,
                videoProcessor = videoProcessor,
            )

        // Determine initialization mode based on first-run state and pending changes
        val adapterConfigPref = AdapterConfigPreference.getInstance(context)
        val initMode = adapterConfigPref.getInitializationMode()
        val pendingChanges = adapterConfigPref.getPendingChangesSync()

        // Refresh user-configurable settings from preference store before starting
        // This ensures changes made in Settings screen are applied on next connection
        // (display settings like width/height are kept from original config)
        val userConfig = adapterConfigPref.getUserConfigSync()
        val refreshedConfig =
            config.copy(
                audioTransferMode = userConfig.audioTransferMode,
                sampleRate = userConfig.sampleRate.hz,
                micType =
                    when (userConfig.micSource) {
                        MicSourceConfig.APP -> "os"
                        MicSourceConfig.PHONE -> "box"
                    },
                wifiType =
                    when (userConfig.wifiBand) {
                        WiFiBandConfig.BAND_5GHZ -> "5ghz"
                        WiFiBandConfig.BAND_24GHZ -> "24ghz"
                    },
                callQuality = userConfig.callQuality.value,
            )
        config = refreshedConfig // Update stored config for other uses

        log("[INIT] Mode: ${adapterConfigPref.getInitializationInfo()}")
        log("[INIT] Audio mode: ${if (refreshedConfig.audioTransferMode) "BLUETOOTH" else "ADAPTER"}")

        adapterDriver?.start(refreshedConfig, initMode.name, pendingChanges)

        // Mark first init completed and clear pending changes after successful start
        // This runs in a coroutine to handle the suspend functions
        CoroutineScope(Dispatchers.IO).launch {
            if (initMode == AdapterConfigPreference.InitMode.FULL) {
                adapterConfigPref.markFirstInitCompleted()
            }
            if (pendingChanges.isNotEmpty()) {
                adapterConfigPref.clearPendingChanges()
            }
        }

        // Start pair timeout
        clearPairTimeout()
        pairTimeout =
            Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            adapterDriver?.sendCommand(CommandMapping.WIFI_PAIR)
                        }
                    },
                    PAIR_TIMEOUT_MS,
                )
            }
    }

    /**
     * Stop and disconnect.
     */
    fun stop() {
        logDebug("[LIFECYCLE] stop() called - clearing frame interval and phoneType", tag = Logger.Tags.VIDEO)
        clearPairTimeout()
        stopFrameInterval()
        cancelReconnect() // Cancel any pending auto-reconnect
        currentPhoneType = null // Clear phone type on disconnect
        clearCachedMediaMetadata() // Clear stale metadata to prevent race conditions on reconnect
        stopMicrophoneCapture()

        adapterDriver?.stop()
        adapterDriver = null

        usbDevice?.close()
        usbDevice = null

        // Stop audio
        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
            logInfo("Audio released on stop", tag = Logger.Tags.AUDIO)
        }

        setState(State.DISCONNECTED)
    }

    /**
     * Restart the connection.
     */
    suspend fun restart() {
        stop()
        delay(2000)
        start()
    }

    /**
     * Send a key command.
     */
    fun sendKey(command: CommandMapping): Boolean = adapterDriver?.sendCommand(command) ?: false

    /**
     * Send a touch event.
     */
    fun sendTouch(
        action: TouchAction,
        x: Float,
        y: Float,
    ): Boolean {
        val normalizedX = x / config.width
        val normalizedY = y / config.height
        return adapterDriver?.sendTouch(action, normalizedX, normalizedY) ?: false
    }

    /**
     * Send a multi-touch event.
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>): Boolean = adapterDriver?.sendMultiTouch(touches) ?: false

    // ========== Capture Playback Injection Methods ==========

    /**
     * Check if the video renderer is ready for data injection.
     * Used by capture playback to verify the pipeline is ready.
     */
    fun isRendererReady(): Boolean = h264Renderer != null && videoInitialized

    // Track injected video count
    private var injectedVideoCount = 0

    /**
     * Inject raw H.264 video data directly into the renderer.
     * Used by capture playback to replay captured video frames.
     *
     * @param data Raw H.264 NAL data (without protocol headers)
     * @param flags Optional flags (e.g., keyframe indicator)
     */
    fun injectVideoData(data: ByteArray, flags: Int = 0) {
        if (!videoInitialized) {
            logDebug("[PLAYBACK_INJECT] Video not initialized, discarding frame", tag = Logger.Tags.VIDEO)
            return
        }
        injectedVideoCount++
        if (injectedVideoCount <= 5 || injectedVideoCount % 100 == 0) {
            logDebug("[PLAYBACK_INJECT] Injecting video #$injectedVideoCount: ${data.size} bytes", tag = Logger.Tags.VIDEO)
        }
        h264Renderer?.processData(data, flags)
    }

    // Track injected audio count for debugging
    private var injectedAudioCount = 0

    /**
     * Inject audio data directly into the audio manager.
     * Used by capture playback to replay captured audio.
     *
     * @param data Raw PCM audio data
     * @param audioType Audio type (1=media, 2=nav, 3=voice, etc.)
     * @param decodeType Decode type (audio format/sample rate indicator)
     */
    fun injectAudioData(data: ByteArray, audioType: Int, decodeType: Int) {
        if (!audioInitialized) {
            logDebug("[PLAYBACK_INJECT] Audio not initialized, discarding audio", tag = Logger.Tags.AUDIO)
            return
        }
        injectedAudioCount++
        if (injectedAudioCount <= 5 || injectedAudioCount % 500 == 0) {
            logDebug(
                "[PLAYBACK_INJECT] Injecting audio #$injectedAudioCount: ${data.size} bytes, " +
                    "type=$audioType, decode=$decodeType",
                tag = Logger.Tags.AUDIO,
            )
        }
        audioManager?.writeAudio(data, audioType, decodeType)
    }

    /**
     * Prepare for capture playback mode.
     * Stops USB adapter search but keeps renderers alive and ensures audio is initialized.
     */
    fun prepareForPlayback() {
        logInfo("[PLAYBACK_INJECT] Preparing for playback mode", tag = Logger.Tags.VIDEO)
        // Stop USB communication but keep renderers
        adapterDriver?.stop()
        adapterDriver = null
        usbDevice?.close()
        usbDevice = null
        cancelReconnect()
        clearPairTimeout()
        stopFrameInterval()

        // Ensure audio is initialized for playback
        if (!audioInitialized && audioManager != null) {
            audioInitialized = audioManager?.initialize() ?: false
            if (audioInitialized) {
                logInfo("[PLAYBACK_INJECT] Audio initialized for playback", tag = Logger.Tags.AUDIO)
            } else {
                logError("[PLAYBACK_INJECT] Failed to initialize audio for playback", tag = Logger.Tags.AUDIO)
            }
        }
    }

    /**
     * Resume normal adapter mode after playback.
     */
    fun resumeAdapterMode() {
        logInfo("[PLAYBACK_INJECT] Resuming adapter mode", tag = Logger.Tags.VIDEO)
        // Restart will reinitialize USB connection
        scope.launch {
            start()
        }
    }

    // ========== End Playback Injection Methods ==========

    /**
     * Release all resources.
     */
    fun release() {
        stop()

        h264Renderer?.stop()
        h264Renderer = null

        audioManager?.release()
        audioManager = null

        microphoneManager?.stop()
        microphoneManager = null

        mediaSessionManager?.release()
        CarlinkMediaBrowserService.mediaSessionToken = null
        mediaSessionManager = null

        logInfo("CarlinkManager released", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Get performance statistics.
     */
    fun getPerformanceStats(): Map<String, Any> =
        buildMap {
            put("state", state.name)
            adapterDriver?.getPerformanceStats()?.let { putAll(it) }
        }

    /**
     * Handle USB device detachment event.
     * Called by MainActivity when USB_DEVICE_DETACHED broadcast is received.
     *
     * This provides immediate detection of physical adapter removal,
     * rather than waiting for USB transfer errors.
     */
    fun onUsbDeviceDetached() {
        logWarn("[USB] Device detached broadcast received", tag = Logger.Tags.USB)

        // Only handle if we have an active connection
        if (state == State.DISCONNECTED) {
            logInfo("[USB] Already disconnected, ignoring detach", tag = Logger.Tags.USB)
            return
        }

        // Trigger recovery through the error handler path
        // This ensures consistent recovery behavior
        handleError("USB device physically disconnected")
    }

    /**
     * Resets the H.264 video decoder/renderer.
     *
     * This operation resets the MediaCodec decoder without disconnecting the USB device.
     * Useful for recovering from video decoding errors or codec issues.
     *
     * Matches Flutter: DeviceOperations.resetH264Renderer()
     */
    fun resetVideoDecoder() {
        logInfo("[DEVICE_OPS] Resetting H264 video decoder", tag = Logger.Tags.VIDEO)
        h264Renderer?.reset()
        logInfo("[DEVICE_OPS] H264 video decoder reset completed", tag = Logger.Tags.VIDEO)
        // Ensure frame interval running after manual reset
        ensureFrameIntervalRunning()
    }

    /**
     * Stops the video decoder/renderer without stopping the USB connection.
     *
     * IMPORTANT: Must be called BEFORE navigating away from the projection screen
     * to prevent BufferQueue abandoned errors. The codec outputs frames to the
     * SurfaceTexture, which is destroyed when the VideoSurface composable is disposed.
     * If the codec is still running when the surface is destroyed, it causes
     * "BufferQueue has been abandoned" errors.
     *
     * The video will automatically restart when the user returns to the MainScreen
     * and a new SurfaceTexture becomes available (via LaunchedEffect).
     */
    fun stopVideo() {
        logInfo("[VIDEO] Stopping video decoder before navigation", tag = Logger.Tags.VIDEO)
        h264Renderer?.stop()
        logInfo("[VIDEO] Video decoder stopped - safe to destroy surface", tag = Logger.Tags.VIDEO)
    }

    /**
     * Handle Surface destruction - pause codec IMMEDIATELY.
     *
     * CRITICAL: This is called when SurfaceView's Surface is destroyed, which happens
     * BEFORE onStop() is called. If we wait for onStop(), the codec will try to render
     * to a dead surface causing "BufferQueue has been abandoned" errors.
     *
     * Call this from VideoSurface's onSurfaceDestroyed callback.
     */
    fun onSurfaceDestroyed() {
        logInfo("[LIFECYCLE] Surface destroyed - pausing codec immediately", tag = Logger.Tags.VIDEO)

        // Cancel any pending surface updates
        surfaceUpdateJob?.cancel()
        surfaceUpdateJob = null
        pendingSurface = null

        // Clear surface reference - it's now invalid
        videoSurface = null

        // Pause codec immediately to prevent rendering to dead surface
        h264Renderer?.pause()
    }

    /**
     * Pause video decoding when app goes to background.
     *
     * On AAOS, when the app is covered by another app (e.g., Maps, Phone), the Surface
     * may remain valid but SurfaceFlinger stops consuming frames. This causes the
     * BufferQueue to fill up, stalling the decoder. When the user returns, video
     * appears blank while audio continues normally.
     *
     * This method flushes the codec to prevent BufferQueue stalls. The USB connection
     * and audio playback continue unaffected.
     *
     * NOTE: Surface destruction is handled separately by onSurfaceDestroyed() which
     * is called when the Surface is actually destroyed (may be before or after onStop).
     *
     * Call this from Activity.onStop().
     */
    fun pauseVideo() {
        logInfo("[LIFECYCLE] Pausing video for background", tag = Logger.Tags.VIDEO)
        h264Renderer?.pause()
    }

    /**
     * Resume video decoding when app returns to foreground.
     *
     * After pauseVideo(), the codec is in a flushed state. This method restarts the
     * codec and requests a keyframe so video can resume immediately.
     *
     * NOTE: The main surface update happens in initialize() when the new Surface is created.
     * If onStart() is called before the Surface is ready, we skip resume here and let
     * initialize() handle it when the Surface becomes available.
     *
     * Call this from Activity.onStart().
     */
    fun resumeVideo() {
        logInfo("[LIFECYCLE] Resuming video for foreground", tag = Logger.Tags.VIDEO)

        // If surface is null (destroyed and not yet recreated), skip resume.
        // initialize() will handle resume when new Surface becomes available.
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logInfo(
                "[LIFECYCLE] Surface not ready yet - resume will happen via initialize()",
                tag = Logger.Tags.VIDEO,
            )
            return
        }

        // Pass current surface to resume
        h264Renderer?.resume(surface)

        // Also request keyframe through adapter if connected
        if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
            adapterDriver?.sendCommand(CommandMapping.FRAME)
        }
    }

    // ==================== Private Methods ====================

    private fun setState(newState: State) {
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            callback?.onStateChanged(newState)
            updateMediaSessionState(newState)
        }
    }

    private fun updateMediaSessionState(state: State) {
        when (state) {
            State.CONNECTING -> {
                mediaSessionManager?.setStateConnecting()
                // Acquire wake lock early to ensure USB operations aren't interrupted
                acquireWakeLock()
            }

            State.DISCONNECTED -> {
                mediaSessionManager?.setStateStopped()
                // Stop foreground service when disconnected
                CarlinkMediaBrowserService.stopConnectionForeground(context)
                // Release wake lock - CPU can sleep now
                releaseWakeLock()
            }

            State.STREAMING -> {
                // Start foreground service to keep app active when backgrounded
                CarlinkMediaBrowserService.startConnectionForeground(context)
                // Ensure wake lock is held during streaming
                acquireWakeLock()
            }

            else -> {} // Playback state updated when audio starts
        }
    }

    /**
     * Acquires a partial wake lock to prevent CPU sleep during USB streaming.
     * This ensures USB transfers and heartbeats continue when the app is backgrounded.
     */
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 60 * 1000L) // 10 minute timeout as safety
            logInfo("[WAKE_LOCK] Acquired partial wake lock for USB streaming", tag = Logger.Tags.USB)
        }
    }

    /**
     * Releases the wake lock, allowing CPU to sleep.
     */
    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            logInfo("[WAKE_LOCK] Released wake lock", tag = Logger.Tags.USB)
        }
    }

    private suspend fun findDevice(): UsbDeviceWrapper? {
        var device: UsbDeviceWrapper? = null
        var attempts = 0

        while (device == null && attempts < 10) {
            device = UsbDeviceWrapper.findFirst(context, usbManager) { log(it) }

            if (device == null) {
                attempts++
                delay(USB_WAIT_PERIOD_MS)
            }
        }

        if (device != null) {
            log("Carlinkit device found!")
        }

        return device
    }

    private fun handleMessage(message: Message) {
        when (message) {
            is PluggedMessage -> {
                logInfo("[PLUGGED] Device plugged: phoneType=${message.phoneType}, wifi=${message.wifi}", tag = Logger.Tags.VIDEO)
                clearPairTimeout()
                stopFrameInterval() // Stop any existing timer (clean slate)

                // Reset reconnect attempts on successful connection
                reconnectAttempts = 0

                // Store phone type for frame interval decisions during recovery
                currentPhoneType = message.phoneType
                logDebug("[PLUGGED] Stored currentPhoneType=$currentPhoneType", tag = Logger.Tags.VIDEO)

                // Start frame interval for CarPlay
                // This periodic keyframe request keeps video streaming stable
                // Protocol specifies FRAME command every 5 seconds during session
                ensureFrameIntervalRunning()

                setState(State.DEVICE_CONNECTED)
            }

            is UnpluggedMessage -> {
                scope.launch {
                    restart()
                }
            }

            is VideoDataMessage -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started", tag = Logger.Tags.VIDEO)
                    setState(State.STREAMING)
                    // Safety net: ensure frame interval running when video starts
                    ensureFrameIntervalRunning()
                }

                // Feed video data to renderer (fallback when direct processing not used)
                message.data?.let { data ->
                    h264Renderer?.processData(data, message.flags)
                }
            }

            // VideoStreamingSignal indicates video data was processed directly by videoProcessor
            // No data to process here - just update state
            VideoStreamingSignal -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started (direct processing)", tag = Logger.Tags.VIDEO)
                    setState(State.STREAMING)
                    // Safety net: ensure frame interval running when video starts
                    ensureFrameIntervalRunning()
                }
                // Video data already processed directly into ring buffer by videoProcessor
            }

            is AudioDataMessage -> {
                clearPairTimeout()
                processAudioData(message)
            }

            is MediaDataMessage -> {
                clearPairTimeout()
                processMediaMetadata(message)
            }

            is CommandMessage -> {
                if (message.command == CommandMapping.REQUEST_HOST_UI) {
                    callback?.onHostUIPressed()
                } else if (message.command == CommandMapping.PROJECTION_DISCONNECTED) {
                    scope.launch {
                        restart()
                    }
                }
            }

            else -> {}
        }

        // Handle audio commands for mic capture
        if (message is AudioDataMessage && message.command != null) {
            handleAudioCommand(message.command)
        }
    }

    private fun processAudioData(message: AudioDataMessage) {
        // Handle volume ducking
        message.volumeDuration?.let { duration ->
            audioManager?.setDucking(message.volume?.toFloat() ?: 1.0f)
            return
        }

        // Skip command messages
        if (message.command != null) return

        // Skip if no audio data
        val audioData = message.data ?: return

        // Write audio
        audioManager?.writeAudio(audioData, message.audioType, message.decodeType)
    }

    private fun handleAudioCommand(command: AudioCommand) {
        logDebug("[AUDIO_CMD] Received audio command: ${command.name} (id=${command.id})", tag = Logger.Tags.AUDIO)

        when (command) {
            AudioCommand.AUDIO_NAVI_START -> {
                logInfo("[AUDIO_CMD] Navigation audio START command received", tag = Logger.Tags.AUDIO)
                // Nav audio data will start arriving - track is created on first packet
            }

            AudioCommand.AUDIO_NAVI_STOP -> {
                logInfo("[AUDIO_CMD] Navigation audio STOP command received", tag = Logger.Tags.AUDIO)
                // Signal nav stopped - stop accepting new packets, but don't flush yet
                // (NAVI_COMPLETE will handle final cleanup)
                audioManager?.onNavStopped()
            }

            AudioCommand.AUDIO_NAVI_COMPLETE -> {
                logInfo("[AUDIO_CMD] Navigation audio COMPLETE command received", tag = Logger.Tags.AUDIO)
                // Explicit end-of-prompt signal from adapter - clean shutdown
                audioManager?.stopNavTrack()
            }

            AudioCommand.AUDIO_SIRI_START -> {
                logInfo("[AUDIO_CMD] Siri started - enabling microphone", tag = Logger.Tags.MIC)
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_PHONECALL_START -> {
                logInfo("[AUDIO_CMD] Phone call started - enabling microphone", tag = Logger.Tags.MIC)
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_SIRI_STOP -> {
                logInfo("[AUDIO_CMD] Siri stopped - disabling microphone", tag = Logger.Tags.MIC)
                stopMicrophoneCapture()
                audioManager?.stopVoiceTrack()
            }

            AudioCommand.AUDIO_PHONECALL_STOP -> {
                logInfo("[AUDIO_CMD] Phone call stopped - disabling microphone", tag = Logger.Tags.MIC)
                stopMicrophoneCapture()
                audioManager?.stopCallTrack()
            }

            AudioCommand.AUDIO_MEDIA_START -> {
                logDebug("[AUDIO_CMD] Media audio START command received", tag = Logger.Tags.AUDIO)
                // Media audio data will start arriving - track is created on first packet
            }

            AudioCommand.AUDIO_MEDIA_STOP -> {
                logDebug("[AUDIO_CMD] Media audio STOP command received", tag = Logger.Tags.AUDIO)
                // Media track typically stays active, but log for debugging
            }

            AudioCommand.AUDIO_OUTPUT_START -> {
                logDebug("[AUDIO_CMD] Audio output START command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_OUTPUT_STOP -> {
                logDebug("[AUDIO_CMD] Audio output STOP command received", tag = Logger.Tags.AUDIO)
            }

            else -> {
                logDebug("[AUDIO_CMD] Unhandled audio command: ${command.name}", tag = Logger.Tags.AUDIO)
            }
        }
    }

    private fun startMicrophoneCapture(
        decodeType: Int,
        audioType: Int,
    ) {
        if (isMicrophoneCapturing) {
            if (currentMicDecodeType == decodeType && currentMicAudioType == audioType) {
                return
            }
            stopMicrophoneCapture()
        }

        val started = microphoneManager?.start() ?: false
        if (started) {
            isMicrophoneCapturing = true
            currentMicDecodeType = decodeType
            currentMicAudioType = audioType

            // Start send loop
            micSendTimer =
                Timer().apply {
                    scheduleAtFixedRate(
                        object : TimerTask() {
                            override fun run() {
                                sendMicrophoneData()
                            }
                        },
                        0,
                        20,
                    ) // 20ms interval
                }

            logInfo("Microphone capture started", tag = Logger.Tags.MIC)
        }
    }

    private fun stopMicrophoneCapture() {
        if (!isMicrophoneCapturing) return

        micSendTimer?.cancel()
        micSendTimer = null

        microphoneManager?.stop()
        isMicrophoneCapturing = false

        logInfo("Microphone capture stopped", tag = Logger.Tags.MIC)
    }

    private fun sendMicrophoneData() {
        if (!isMicrophoneCapturing) return

        val data = microphoneManager?.readChunk(maxBytes = 640) ?: return
        if (data.isNotEmpty()) {
            adapterDriver?.sendAudio(
                data = data,
                decodeType = currentMicDecodeType,
                audioType = currentMicAudioType,
            )
        }
    }

    private fun processMediaMetadata(message: MediaDataMessage) {
        val payload = message.payload

        // Extract new song title (if present)
        val newSongName = (payload["MediaSongName"] as? String)?.takeIf { it.isNotEmpty() }

        // If song title changed, clear all cached metadata to prevent stale data mixing
        if (newSongName != null && newSongName != lastMediaSongName) {
            lastMediaSongName = null
            lastMediaArtistName = null
            lastMediaAlbumName = null
            lastAlbumCover = null
            // Keep appName - typically doesn't change mid-session
        }

        // Extract text metadata
        newSongName?.let {
            lastMediaSongName = it
        }
        (payload["MediaArtistName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            lastMediaArtistName = it
        }
        (payload["MediaAlbumName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            lastMediaAlbumName = it
        }
        (payload["MediaAPPName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            lastMediaAppName = it
        }

        // Process album cover after song change detection
        val albumCover = payload["AlbumCover"] as? ByteArray
        if (albumCover != null) {
            lastAlbumCover = albumCover
        }

        val mediaInfo =
            MediaInfo(
                songTitle = lastMediaSongName,
                songArtist = lastMediaArtistName,
                albumName = lastMediaAlbumName,
                appName = lastMediaAppName,
                albumCover = lastAlbumCover,
            )

        callback?.onMediaInfoChanged(mediaInfo)

        // Update MediaSession
        mediaSessionManager?.updateMetadata(
            title = mediaInfo.songTitle,
            artist = mediaInfo.songArtist,
            album = mediaInfo.albumName,
            appName = mediaInfo.appName,
            albumArt = mediaInfo.albumCover,
        )
        mediaSessionManager?.updatePlaybackState(playing = true)
    }

    /**
     * Simple error handler matching Flutter CarlinkPlugin.kt pattern.
     *
     * Recovery strategy (simple, proven):
     * 1. Check if error is recoverable
     * 2. Track consecutive resets within time window
     * 3. If threshold reached, perform emergency cleanup
     * 4. Otherwise, notify and let system recover naturally
     * 5. For USB disconnects, schedule auto-reconnect with exponential backoff
     */
    private fun handleError(error: String) {
        clearPairTimeout()

        logError("Adapter error: $error", tag = Logger.Tags.ADAPTR)

        // Check if this is a recoverable error and track for error recovery
        if (isRecoverableError(error)) {
            // For recoverable errors (codec reset), keep frame interval running
            // This ensures periodic keyframe requests continue during recovery
            handleCodecReset()
            // Don't change state for recoverable errors - stay in STREAMING if we were
            return
        }

        // Only stop frame interval for non-recoverable errors
        stopFrameInterval()
        currentPhoneType = null

        // Set state to disconnected
        setState(State.DISCONNECTED)

        // Schedule auto-reconnect for USB disconnect errors
        if (isUsbDisconnectError(error)) {
            scheduleReconnect()
        }
    }

    /**
     * Checks if an error indicates USB disconnect (physical or transfer failure).
     */
    private fun isUsbDisconnectError(error: String): Boolean {
        val lowerError = error.lowercase()
        return lowerError.contains("disconnect") ||
            lowerError.contains("detach") ||
            lowerError.contains("transfer") ||
            lowerError.contains("usb")
    }

    /**
     * Schedule an auto-reconnect attempt with exponential backoff.
     *
     * After USB disconnect, attempts to reconnect automatically:
     * - Attempt 1: 2 seconds delay
     * - Attempt 2: 4 seconds delay
     * - Attempt 3: 8 seconds delay
     * - Attempt 4: 16 seconds delay
     * - Attempt 5: 30 seconds delay (capped)
     *
     * Gives up after MAX_RECONNECT_ATTEMPTS to prevent infinite loops.
     */
    private fun scheduleReconnect() {
        // Cancel any existing reconnect attempt
        reconnectJob?.cancel()

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logWarn(
                "[RECONNECT] Max attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up. " +
                    "User must manually restart.",
                tag = Logger.Tags.USB,
            )
            reconnectAttempts = 0
            return
        }

        // Calculate delay with exponential backoff, capped at max
        val delay =
            minOf(
                INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempts),
                MAX_RECONNECT_DELAY_MS,
            )
        reconnectAttempts++

        logInfo(
            "[RECONNECT] Scheduling attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delay}ms",
            tag = Logger.Tags.USB,
        )

        reconnectJob =
            scope.launch {
                delay(delay)

                // Only attempt if still disconnected
                if (state == State.DISCONNECTED) {
                    logInfo("[RECONNECT] Attempting reconnection...", tag = Logger.Tags.USB)
                    try {
                        start()
                    } catch (e: Exception) {
                        logError("[RECONNECT] Reconnection failed: ${e.message}", tag = Logger.Tags.USB)
                        // handleError will be called by start() failure, which will schedule next attempt
                    }
                } else {
                    logInfo("[RECONNECT] Already connected, cancelling reconnect", tag = Logger.Tags.USB)
                    reconnectAttempts = 0
                }
            }
    }

    /**
     * Cancel any pending reconnect attempt.
     */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    /**
     * Checks if an error is recoverable.
     * Matches Flutter: CarlinkPlugin.kt isRecoverableError()
     */
    private fun isRecoverableError(error: String): Boolean {
        val lowerError = error.lowercase()
        return when {
            // MediaCodec-specific errors that typically indicate recoverable issues
            lowerError.contains("reset codec") -> true

            lowerError.contains("mediacodec") && lowerError.contains("illegalstateexception") -> true

            lowerError.contains("codecexception") -> true

            // Surface texture related errors that may be recoverable
            lowerError.contains("surface") && lowerError.contains("invalid") -> true

            else -> false
        }
    }

    /**
     * Handles MediaCodec reset tracking with thread-safe error recovery.
     * Matches Flutter: CarlinkPlugin.kt handleCodecReset()
     *
     * Synchronized to prevent race conditions when multiple threads
     * encounter errors simultaneously.
     */
    @Synchronized
    private fun handleCodecReset() {
        val currentTime = System.currentTimeMillis()

        // Reset counter if outside the window
        if (currentTime - lastResetTime > RESET_WINDOW_MS) {
            consecutiveResets = 0
        }

        consecutiveResets++
        lastResetTime = currentTime

        logInfo("[ERROR RECOVERY] Reset count: $consecutiveResets in window", tag = Logger.Tags.ADAPTR)

        // CRITICAL: Ensure frame interval is running after codec reset
        // This fixes the bug where timer stopped during disconnect and never restarted
        logDebug("[ERROR RECOVERY] Ensuring frame interval running after codec reset", tag = Logger.Tags.VIDEO)
        ensureFrameIntervalRunning()

        // If we've hit the threshold, perform complete cleanup
        if (consecutiveResets >= RESET_THRESHOLD) {
            logWarn("[ERROR RECOVERY] Threshold reached, performing complete system cleanup", tag = Logger.Tags.ADAPTR)
            performEmergencyCleanup()
            consecutiveResets = 0 // Reset counter after cleanup
        }
    }

    /**
     * Performs emergency cleanup to prevent cascade failures.
     * Matches Flutter: CarlinkPlugin.kt performEmergencyCleanup()
     *
     * Simple cleanup - just reset video and close USB.
     * Does NOT attempt automatic restart (let user/system decide).
     */
    private fun performEmergencyCleanup() {
        try {
            logWarn("[EMERGENCY CLEANUP] Starting conservative system cleanup", tag = Logger.Tags.ADAPTR)

            // Reset video renderer using manager
            try {
                h264Renderer?.reset()
                logInfo("[EMERGENCY CLEANUP] Video renderer reset", tag = Logger.Tags.ADAPTR)
            } catch (e: Exception) {
                logError("[EMERGENCY CLEANUP] Video reset error: ${e.message}", tag = Logger.Tags.ADAPTR)
            }

            // Close USB connection properly
            try {
                adapterDriver?.stop()
                adapterDriver = null
                usbDevice?.close()
                usbDevice = null
                logInfo("[EMERGENCY CLEANUP] USB connection closed", tag = Logger.Tags.ADAPTR)
            } catch (e: Exception) {
                logError("[EMERGENCY CLEANUP] USB close error: ${e.message}", tag = Logger.Tags.ADAPTR)
            }

            logWarn("[EMERGENCY CLEANUP] Conservative cleanup finished", tag = Logger.Tags.ADAPTR)
        } catch (e: Exception) {
            logError("[EMERGENCY CLEANUP] State error: ${e.message}", tag = Logger.Tags.ADAPTR)
        }
    }

    private fun clearPairTimeout() {
        pairTimeout?.cancel()
        pairTimeout = null
    }

    /**
     * Ensures the periodic keyframe request is running for CarPlay connections.
     *
     * Safe to call multiple times - will not create duplicate jobs.
     * Uses coroutines for better lifecycle management and error handling.
     *
     * FRAME command interval reduced from 5s to 2s to improve video recovery
     * after H.264 decoder drift. Since adapter only sends SPS+PPS at session start,
     * more frequent keyframe requests help recover from progressive pixelation faster.
     */
    @Synchronized
    private fun ensureFrameIntervalRunning() {
        val phoneType = currentPhoneType
        val jobActive = frameIntervalJob?.isActive == true

        // Only for CarPlay - using 2s keyframe interval for faster recovery
        if (phoneType != PhoneType.CARPLAY) {
            logDebug("[FRAME_INTERVAL] Skipping - phoneType=$phoneType (not CarPlay)", tag = Logger.Tags.VIDEO)
            return
        }

        // Already running - nothing to do
        if (jobActive) {
            logDebug("[FRAME_INTERVAL] Already running - no action needed", tag = Logger.Tags.VIDEO)
            return
        }

        logInfo("[FRAME_INTERVAL] Starting periodic keyframe request (every 2s) for CarPlay", tag = Logger.Tags.VIDEO)

        frameIntervalJob =
            scope.launch(Dispatchers.IO) {
                // OPTIMIZATION: Send immediate keyframe request on start for faster video recovery
                // Research: pi-carplay-main achieves near-instant recovery by not delaying first request
                // This ensures keyframe is requested immediately after reset/resume, not after 2s delay
                val immediateSent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                logInfo("[FRAME_INTERVAL] Immediate keyframe request sent=$immediateSent", tag = Logger.Tags.VIDEO)

                var requestCount = 0
                while (isActive) {
                    delay(2000)
                    requestCount++
                    val sent = adapterDriver?.sendCommand(CommandMapping.FRAME) ?: false
                    logDebug("[FRAME_INTERVAL] Keyframe request #$requestCount sent=$sent", tag = Logger.Tags.VIDEO)
                }
                logDebug("[FRAME_INTERVAL] Coroutine ended after $requestCount requests", tag = Logger.Tags.VIDEO)
            }
    }

    /**
     * Stops the periodic keyframe request.
     */
    @Synchronized
    private fun stopFrameInterval() {
        val wasActive = frameIntervalJob?.isActive == true
        if (wasActive) {
            logInfo("[FRAME_INTERVAL] Stopping periodic keyframe request (wasActive=$wasActive)", tag = Logger.Tags.VIDEO)
            frameIntervalJob?.cancel()
        } else {
            logDebug("[FRAME_INTERVAL] Stop called but job not active (wasActive=$wasActive)", tag = Logger.Tags.VIDEO)
        }
        frameIntervalJob = null
    }

    /**
     * Create a video processor for direct USB -> ring buffer data flow.
     * This bypasses message parsing and matches Flutter's zero-copy architecture.
     *
     * The processor reads USB data directly into the H264Renderer's ring buffer,
     * skipping the 20-byte video header (width, height, flags, length, unknown).
     */
    private fun createVideoProcessor(): UsbDeviceWrapper.VideoDataProcessor {
        return object : UsbDeviceWrapper.VideoDataProcessor {
            override fun processVideoDirect(
                payloadLength: Int,
                readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int,
            ) {
                // Get the H264Renderer - if not available, discard data
                val renderer =
                    h264Renderer ?: run {
                        // Still need to read and discard the data to prevent USB buffer overflow
                        val discardBuffer = ByteArray(payloadLength)
                        readCallback(discardBuffer, 0, payloadLength)

                        // Log warning (throttled to every 2 seconds to avoid log spam)
                        val now = System.currentTimeMillis()
                        if (now - lastVideoDiscardWarningTime > 2000) {
                            lastVideoDiscardWarningTime = now
                            logWarn(
                                "Video frame discarded - H264Renderer not initialized. " +
                                    "Ensure initialize(surface) is called before video streaming starts.",
                                tag = Logger.Tags.VIDEO,
                            )
                        }
                        return
                    }

                // Use processDataDirect to write directly to ring buffer
                // payloadLength includes the 20-byte video header
                // skipBytes=20 tells the ring buffer to skip the header when reading
                logVideoUsb { "processVideoDirect: payloadLength=$payloadLength" }

                renderer.processDataDirect(payloadLength, 20) { buffer, offset ->
                    // Read USB data directly into the ring buffer
                    readCallback(buffer, offset, payloadLength)
                }
            }
        }
    }

    private fun log(message: String) {
        logDebug(message, tag = Logger.Tags.ADAPTR)
        callback?.onLogMessage(message)
    }
}
