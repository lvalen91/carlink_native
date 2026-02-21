package com.carlink

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.view.Surface
import androidx.core.content.edit
import com.carlink.audio.DualStreamAudioManager
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.gnss.GnssForwarder
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logVideoUsb
import com.carlink.logging.logWarn
import com.carlink.media.CarlinkMediaBrowserService
import com.carlink.media.MediaSessionManager
import com.carlink.navigation.NavigationStateManager
import com.carlink.platform.AudioConfig
import com.carlink.platform.PlatformDetector
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.AdapterDriver
import com.carlink.protocol.AudioCommand
import com.carlink.protocol.AudioDataMessage
import com.carlink.protocol.BoxSettingsMessage
import com.carlink.protocol.CommandMapping
import com.carlink.protocol.CommandMessage
import com.carlink.protocol.MediaDataMessage
import com.carlink.protocol.MediaType
import com.carlink.protocol.InfoMessage
import com.carlink.protocol.Message
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.NaviFocusMessage
import com.carlink.protocol.PeerBluetoothAddressMessage
import com.carlink.protocol.PhaseMessage
import com.carlink.protocol.PhoneType
import com.carlink.protocol.PluggedMessage
import com.carlink.protocol.SessionTokenMessage
import com.carlink.protocol.StatusValueMessage
import com.carlink.protocol.UnknownMessage
import com.carlink.protocol.UnpluggedMessage
import com.carlink.protocol.VideoStreamingSignal
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.MicSourceConfig
import com.carlink.ui.settings.WiFiBandConfig
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AppExecutors
import com.carlink.util.LogCallback
import com.carlink.video.H264Renderer
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
        val duration: Long,
        val position: Long,
        val isPlaying: Boolean,
    )

    /**
     * Callback interface for Carlink events.
     */
    interface Callback {
        fun onStateChanged(state: State)

        fun onStatusTextChanged(text: String)

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

    /**
     * Voice mode tracking for proper microphone lifecycle management.
     *
     * CRITICAL: CarPlay sends PHONECALL_START before SIRI_STOP when making calls via Siri.
     * Without tracking, SIRI_STOP would kill the phone call's microphone.
     *
     * Observed sequence (from USB capture analysis):
     *   217.13s: PHONECALL_START  → mic should stay active
     *   217.26s: SIRI_STOP        → must NOT stop mic (phone call active)
     */
    private enum class VoiceMode { NONE, SIRI, PHONECALL }

    private var activeVoiceMode = VoiceMode.NONE

    // GNSS
    private var gnssForwarder: GnssForwarder? = null

    // MediaSession
    private var mediaSessionManager: MediaSessionManager? = null

    // Timers
    private var pairTimeout: Timer? = null
    private var frameIntervalJob: Job? = null

    // Phone type tracking for frame interval decisions
    private var currentPhoneType: PhoneType? = null

    // Auto-reconnect on USB disconnect
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0

    // Phase 13 (negotiation_failed) — prevents auto-restart loop when iPhone rejects config
    private var negotiationRejected: Boolean = false

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
    private var lastDuration: Long = 0L
    private var lastPosition: Long = 0L
    private var lastIsPlaying: Boolean = true

    // Device identification for SPS/PPS caching
    private var deviceList: List<Pair<String, String>> = emptyList()  // (btMac, type) from BoxSettings #1
    private var connectedBtMac: String? = null                        // from PeerBluetoothAddress
    /** Clears cached media metadata to prevent stale data on reconnect. */
    private fun clearCachedMediaMetadata() {
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaAppName = null
        lastAlbumCover = null
        lastDuration = 0L
        lastPosition = 0L
        lastIsPlaying = true
        connectedBtMac = null
        deviceList = emptyList()
        NavigationStateManager.clear()
    }

    // Executors
    private val executors = AppExecutors()

    // LogCallback for Java components — routes to Logger with proper tags
    private val logCallback = object : LogCallback {
        override fun log(message: String) {
            this@CarlinkManager.log(message)
        }

        override fun log(tag: String, message: String) {
            Logger.d(message, tag)
        }

        override fun logPerf(tag: String, message: String) {
            if (Logger.isDebugLoggingEnabled() && Logger.isTagEnabled(tag)) {
                Logger.d(message, tag)
            }
        }
    }

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

        // Update config with actual surface dimensions ONLY if user didn't select a specific resolution
        // When userSelectedResolution=true, honor the user's choice; otherwise use actual surface size
        if (!config.userSelectedResolution && (config.width != evenWidth || config.height != evenHeight)) {
            logInfo(
                "[RES] Updating config from ${config.width}x${config.height} to ${evenWidth}x$evenHeight " +
                    "(actual surface size, AUTO mode)",
                tag = Logger.Tags.VIDEO,
            )
            config = config.copy(width = evenWidth, height = evenHeight)
        } else if (config.userSelectedResolution) {
            logInfo(
                "[RES] Using user-selected resolution ${config.width}x${config.height} " +
                    "(surface: ${evenWidth}x$evenHeight)",
                tag = Logger.Tags.VIDEO,
            )
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
                        "[LIFECYCLE] Surface stabilized at " +
                            "${pendingSurfaceWidth}x$pendingSurfaceHeight - updating codec",
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
            "[PLATFORM] Using VideoDecoder: " +
                "${platformInfo.hardwareH264DecoderName ?: "generic (createDecoderByType)"}" +
                if (platformInfo.requiresIntelMediaCodecFixes())
                    " [Intel VPU workaround enabled]" else "",
            tag = Logger.Tags.VIDEO,
        )

        // Initialize H264 renderer with Surface for direct HWC rendering
        h264Renderer =
            H264Renderer(
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

        // Set CSD extraction callback — persist SPS/PPS for future codec pre-warming
        h264Renderer?.setCsdExtractedCallback { sps, spsLen, pps, ppsLen ->
            val btMac = connectedBtMac ?: return@setCsdExtractedCallback
            val cacheKey = "${btMac}_${config.width}x${config.height}"
            val prefs = context.getSharedPreferences("carlink_csd_cache", Context.MODE_PRIVATE)

            val spsData = sps.copyOf(spsLen)
            val ppsData = if (pps != null && ppsLen > 0) pps.copyOf(ppsLen) else ByteArray(0)

            prefs.edit {
                putString("sps_$cacheKey", android.util.Base64.encodeToString(spsData, android.util.Base64.NO_WRAP))
                putString("pps_$cacheKey", android.util.Base64.encodeToString(ppsData, android.util.Base64.NO_WRAP))
            }

            logInfo("[DEVICE] CSD cached for $cacheKey: SPS=${spsLen}B, PPS=${ppsLen}B", tag = Logger.Tags.VIDEO)
        }

        // Start the H264 renderer to initialize MediaCodec and begin decoding
        // This MUST be called before feedDirect() - MediaCodec requires start() before queueInputBuffer()
        h264Renderer?.start()

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

        // Initialize GNSS forwarder for GPS → adapter → CarPlay pipeline (only if enabled)
        if (config.gpsForwarding) {
            gnssForwarder = GnssForwarder(
                context = context,
                sendGnssData = { adapterDriver?.sendGnssData(it) ?: false },
                logCallback = ::log,
            )
        }

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
            // Push session token to MediaBrowserService for AAOS cluster integration.
            // Uses updateSessionToken() to also push to an already-running service instance,
            // resolving the race where the system starts the service before this point.
            mediaSessionManager?.getSessionToken()?.let {
                CarlinkMediaBrowserService.updateSessionToken(it)
            }
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

        // Stop any existing connection before entering CONNECTING state
        // so that FGS started at CONNECTING isn't immediately stopped by stop()→DISCONNECTED
        if (adapterDriver != null) {
            stop()
        }

        setState(State.CONNECTING)
        setStatusText("Searching for adapter...")

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
            setStatusText("Adapter not found")
            return
        }

        log("Device found, opening")
        usbDevice = device
        setStatusText("Adapter found — opening...")

        if (!device.openWithPermission()) {
            logError("Failed to open USB device", tag = Logger.Tags.USB)
            setState(State.DISCONNECTED)
            setStatusText("USB permission denied")
            return
        }

        // Create video processor for direct USB -> codec data flow
        // This bypasses message parsing for zero-copy performance (DIRECT_HANDOFF)
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
                // Sample rate is hardcoded to 48kHz - not user configurable
                sampleRate = 48000,
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
                fps = userConfig.fps.fps,
                handDriveMode = userConfig.handDrive.value,
                gpsForwarding = userConfig.gpsForwarding,
            )
        config = refreshedConfig // Update stored config for other uses

        log("[INIT] Mode: ${adapterConfigPref.getInitializationInfo()}")
        log("[INIT] Audio mode: ${if (refreshedConfig.audioTransferMode) "BLUETOOTH" else "ADAPTER"}")

        setStatusText("Initializing adapter...")
        adapterDriver?.start(refreshedConfig, initMode.name, pendingChanges)
        setStatusText("Waiting for phone...")

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
    fun stop(reboot: Boolean = false) {
        logDebug("[LIFECYCLE] stop() called - clearing frame interval and phoneType", tag = Logger.Tags.VIDEO)
        clearPairTimeout()
        stopFrameInterval()
        cancelReconnect() // Cancel any pending auto-reconnect
        negotiationRejected = false // Clear rejection flag for fresh connection
        currentPhoneType = null // Clear phone type on disconnect
        clearCachedMediaMetadata() // Clear stale metadata to prevent race conditions on reconnect
        activeVoiceMode = VoiceMode.NONE // Reset voice mode on disconnect
        stopMicrophoneCapture()

        // Stop GPS forwarding before stopping adapter (only if forwarder was created)
        if (gnssForwarder != null) {
            adapterDriver?.sendCommand(CommandMapping.STOP_GNSS_REPORT)
            gnssForwarder?.stop()
        }

        // Graceful teardown: notify adapter before killing connection.
        // Must happen before adapterDriver?.stop() (send() checks isRunning).
        adapterDriver?.sendGracefulTeardown(reboot = reboot)

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
     * Uses Dispatchers.IO for stop()/start() — both do blocking USB I/O
     * (bulkTransfer, thread joins) that must never run on the main thread.
     */
    suspend fun restart() {
        setStatusText("Restarting...")
        withContext(Dispatchers.IO) { stop() }
        delay(2000)
        withContext(Dispatchers.IO) { start() }
    }

    /**
     * Send a key command.
     */
    private fun sendKey(command: CommandMapping): Boolean = adapterDriver?.sendCommand(command) ?: false

    /**
     * Send a multi-touch event.
     * Dispatched to IO — USB bulkTransfer must never block the UI thread.
     */
    fun sendMultiTouch(
        touches: List<MessageSerializer.TouchPoint>,
    ) {
        val driver = adapterDriver ?: return
        scope.launch(Dispatchers.IO) {
            driver.sendMultiTouch(touches)
        }
    }

    fun rebootAdapter() {
        logWarn("[LIFECYCLE] Reboot adapter requested", tag = Logger.Tags.ADAPTR)
        cancelReconnect()
        stopMicrophoneCapture()
        adapterDriver?.rebootAdapter()
        adapterDriver?.stop()
        adapterDriver = null
        usbDevice?.close()
        usbDevice = null
        if (audioInitialized) {
            audioManager?.release()
            audioInitialized = false
        }
        currentPhoneType = null
        activeVoiceMode = VoiceMode.NONE
        setState(State.DISCONNECTED)
    }

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

        gnssForwarder?.stop()
        gnssForwarder = null

        mediaSessionManager?.release()
        CarlinkMediaBrowserService.mediaSessionToken = null
        mediaSessionManager = null

        // Cancel coroutine scope to stop any in-flight coroutines that hold
        // references to this manager and its context
        scope.cancel()

        logInfo("CarlinkManager released", tag = Logger.Tags.ADAPTR)
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
     */
    fun resetVideoDecoder() {
        logInfo("[DEVICE_OPS] Resetting H264 video decoder", tag = Logger.Tags.VIDEO)
        h264Renderer?.reset()
        logInfo("[DEVICE_OPS] H264 video decoder reset completed", tag = Logger.Tags.VIDEO)
        // Ensure frame interval running after manual reset
        ensureFrameIntervalRunning()
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

        // Stop codec immediately - surface is dead
        h264Renderer?.stop()
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
        h264Renderer?.stop()
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

    private fun setStatusText(text: String) {
        callback?.onStatusTextChanged(text)
    }

    private fun updateMediaSessionState(state: State) {
        when (state) {
            State.CONNECTING -> {
                mediaSessionManager?.setStateConnecting()
                // Start foreground service early to maintain process priority during handshake
                CarlinkMediaBrowserService.startConnectionForeground(context)
                // Acquire wake lock to ensure USB operations aren't interrupted
                acquireWakeLock()
            }

            State.DISCONNECTED -> {
                mediaSessionManager?.setStateStopped()
                // Clear now-playing and stop foreground service
                CarlinkMediaBrowserService.clearNowPlaying()
                CarlinkMediaBrowserService.stopConnectionForeground(context)
                // Release wake lock - CPU can sleep now
                releaseWakeLock()
            }

            State.STREAMING -> {
                // Defensive: ensure FGS is running (idempotent if already started at CONNECTING)
                CarlinkMediaBrowserService.startConnectionForeground(context)
                // Ensure wake lock is held during streaming
                acquireWakeLock()
                // Start GPS forwarding to CarPlay (only if enabled in settings)
                if (config.gpsForwarding) {
                    gnssForwarder?.start()
                }
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
            wakeLock.acquire(2 * 60 * 60 * 1000L) // 2h safety timeout — released explicitly on DISCONNECTED
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
                logInfo(
                    "[PLUGGED] Device plugged: phoneType=${message.phoneType}, wifi=${message.wifi}",
                    tag = Logger.Tags.VIDEO,
                )
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

                // Enable GPS forwarding for CarPlay navigation (only if enabled in settings)
                if (config.gpsForwarding) {
                    adapterDriver?.sendCommand(CommandMapping.START_GNSS_REPORT)
                }

                setState(State.DEVICE_CONNECTED)
                setStatusText("Phone connected — starting session...")
            }

            is UnpluggedMessage -> {
                if (negotiationRejected) {
                    logDebug(
                        "[PHASE] Unplugged after negotiation rejection — not restarting",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    setStatusText("Phone unplugged")
                    scope.launch {
                        restart()
                    }
                }
            }

            // VideoStreamingSignal indicates video data was processed directly by videoProcessor
            // No data to process here - just update state
            VideoStreamingSignal -> {
                clearPairTimeout()

                if (state != State.STREAMING) {
                    logInfo("Video streaming started (direct processing)", tag = Logger.Tags.VIDEO)
                    setState(State.STREAMING)
                    setStatusText("Streaming")
                    // Safety net: ensure frame interval running when video starts
                    ensureFrameIntervalRunning()
                }
                // Video data already processed directly by videoProcessor (DIRECT_HANDOFF)
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
                } else if (message.command == CommandMapping.WIFI_DISCONNECTED) {
                    // WiFi status notification - adapter's WiFi hotspot has no phone connected
                    // This is informational only, NOT a session termination signal
                    // Real disconnects come via UnpluggedMessage (0x04)
                    logDebug("[WIFI] Adapter WiFi status: not connected", tag = Logger.Tags.ADAPTR)
                } else if (message.command == CommandMapping.INVALID) {
                    logWarn(
                        "[CMD] Unknown command id=${message.rawId} (0x${message.rawId.toString(16)})",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    logDebug(
                        "[CMD] ${message.command.name} (id=${message.rawId})",
                        tag = Logger.Tags.ADAPTR,
                    )
                }
            }

            is BoxSettingsMessage -> {
                if (message.isPhoneInfo) {
                    // BoxSettings #2: phone connected — has MDModel, btMacAddr
                    val phoneModel = message.json.optString("MDModel", "").ifEmpty { null }
                    val btMac = message.json.optString("btMacAddr", "").ifEmpty { null }
                    if (btMac != null) {
                        connectedBtMac = btMac
                        tryPrestageCodecCsd(btMac)
                    }
                    logInfo(
                        "[DEVICE] Phone identified: model=$phoneModel, bt=$btMac",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else {
                    // BoxSettings #1: adapter info — has DevList of previously paired devices
                    deviceList = parseDevList(message.json)
                    logInfo("[DEVICE] Paired devices: ${deviceList.size}", tag = Logger.Tags.ADAPTR)
                    // Hot-rejoin: if exactly 1 paired device, try cache lookup now
                    // (adapter may skip BoxSettings #2 and PeerBluetoothAddress)
                    if (deviceList.size == 1) {
                        val (mac, _) = deviceList[0]
                        connectedBtMac = mac
                        tryPrestageCodecCsd(mac)
                    }
                }
            }

            is PeerBluetoothAddressMessage -> {
                connectedBtMac = message.macAddress
                logInfo("[DEVICE] Peer BT address: ${message.macAddress}", tag = Logger.Tags.ADAPTR)
                tryPrestageCodecCsd(message.macAddress)
            }

            // Phase message — Phase 0 is a session termination signal (firmware kills AppleCarPlay)
            is PhaseMessage -> {
                if (message.phase == 13) {
                    logWarn(
                        "[PHASE] Phase 13 (negotiation_failed) — iPhone rejected configuration",
                        tag = Logger.Tags.ADAPTR,
                    )
                    negotiationRejected = true
                    setStatusText("iPhone rejected configuration")
                } else if (message.phase == 0 && negotiationRejected) {
                    logDebug(
                        "[PHASE] Phase 0 after negotiation rejection — not restarting",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else if (message.phase == 0 && state == State.STREAMING) {
                    logWarn(
                        "[PHASE] Phase 0 during STREAMING — session terminated by adapter",
                        tag = Logger.Tags.ADAPTR,
                    )
                    setStatusText("Session terminated — restarting...")
                    scope.launch { restart() }
                } else if (message.phase == 0) {
                    logDebug(
                        "[PHASE] Phase 0 during $state — ignoring (normal session negotiation)",
                        tag = Logger.Tags.ADAPTR,
                    )
                } else if (message.phase == 7) {
                    setStatusText("Phone connecting...")
                    logDebug("[PHASE] ${message.phase} (${message.phaseName})", tag = Logger.Tags.ADAPTR)
                } else {
                    logDebug("[PHASE] ${message.phase} (${message.phaseName})", tag = Logger.Tags.ADAPTR)
                }
            }

            // Navigation focus — echo back as recommended precaution (testing inconclusive)
            is NaviFocusMessage -> {
                if (message.isRequest) {
                    logDebug("[NAVI] Navigation video focus requested — echoing 508", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.sendCommand(CommandMapping.REQUEST_NAVI_SCREEN_FOCUS)
                } else {
                    logDebug("[NAVI] Navigation video focus released — echoing 509", tag = Logger.Tags.ADAPTR)
                    adapterDriver?.sendCommand(CommandMapping.RELEASE_NAVI_SCREEN_FOCUS)
                }
            }

            // Diagnostic messages — logged for protocol completeness and event correlation
            is StatusValueMessage -> {
                logDebug("[STATUS] Value=${message.value} (0x${message.value.toString(16)})", tag = Logger.Tags.ADAPTR)
            }

            is SessionTokenMessage -> {
                logDebug("[SESSION] Token received (${message.payloadSize}B encrypted)", tag = Logger.Tags.ADAPTR)
            }

            is InfoMessage -> {
                logDebug("[INFO] ${message.label}: ${message.value}", tag = Logger.Tags.ADAPTR)
            }

            is UnknownMessage -> {
                logWarn(
                    "[UNKNOWN] Unrecognized message type=0x${message.header.rawType.toString(16)} " +
                        "(${message.header.length}B)",
                    tag = Logger.Tags.ADAPTR,
                )
            }
        }

        // Handle audio commands for mic capture
        if (message is AudioDataMessage && message.command != null) {
            handleAudioCommand(message.command)
        }
    }

    private fun processAudioData(message: AudioDataMessage) {
        // Handle volume ducking
        message.volumeDuration?.let { duration ->
            audioManager?.setDucking(message.volume)
            return
        }

        // Skip command messages
        if (message.command != null) return

        // Skip if no audio data
        val audioData = message.data ?: return

        // Write audio with offset+length to avoid copy
        audioManager?.writeAudio(
            audioData, message.audioDataOffset, message.audioDataLength,
            message.audioType, message.decodeType,
        )
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
                logInfo("[AUDIO_CMD] Siri started - enabling microphone (mode: SIRI)", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.SIRI
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_PHONECALL_START -> {
                logInfo("[AUDIO_CMD] Phone call started - enabling microphone (mode: PHONECALL)", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.PHONECALL
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_SIRI_STOP -> {
                // CRITICAL: Don't stop mic if phone call is active (Siri-initiated call scenario)
                // USB capture shows: PHONECALL_START arrives ~130ms BEFORE SIRI_STOP
                if (activeVoiceMode == VoiceMode.PHONECALL) {
                    logInfo(
                        "[AUDIO_CMD] Siri stopped but phone call active - keeping mic for call",
                        tag = Logger.Tags.MIC,
                    )
                } else {
                    logInfo("[AUDIO_CMD] Siri stopped - disabling microphone", tag = Logger.Tags.MIC)
                    activeVoiceMode = VoiceMode.NONE
                    stopMicrophoneCapture()
                }
            }

            AudioCommand.AUDIO_PHONECALL_STOP -> {
                logInfo("[AUDIO_CMD] Phone call stopped - disabling microphone", tag = Logger.Tags.MIC)
                activeVoiceMode = VoiceMode.NONE
                stopMicrophoneCapture()
            }

            AudioCommand.AUDIO_MEDIA_START -> {
                logDebug("[AUDIO_CMD] Media audio START command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_MEDIA_STOP -> {
                logDebug("[AUDIO_CMD] Media audio STOP command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_ALERT_START -> {
                logDebug("[AUDIO_CMD] Alert started", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_ALERT_STOP -> {
                logDebug("[AUDIO_CMD] Alert stopped", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_OUTPUT_START -> {
                logDebug("[AUDIO_CMD] Audio output START command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_OUTPUT_STOP -> {
                logDebug("[AUDIO_CMD] Audio output STOP command received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_INCOMING_CALL_INIT -> {
                logInfo("[AUDIO_CMD] Incoming call ring (AudioCmd 14)", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.AUDIO_INPUT_CONFIG -> {
                logDebug("[AUDIO_CMD] Audio input configuration received", tag = Logger.Tags.AUDIO)
            }

            AudioCommand.UNKNOWN -> {
                logWarn("[AUDIO_CMD] Unknown audio command id=${command.id}", tag = Logger.Tags.AUDIO)
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
        // Route NaviJSON to NavigationStateManager for cluster display
        if (message.type == MediaType.NAVI_JSON) {
            NavigationStateManager.onNaviJson(message.payload)
            return
        }

        val payload = message.payload

        // Extract new song title (if present)
        val newSongName = (payload["MediaSongName"] as? String)?.takeIf { it.isNotEmpty() }

        // Detect song change — clear all cached metadata to prevent stale data mixing
        val previousSongName = lastMediaSongName
        if (newSongName != null && newSongName != previousSongName) {
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

        // Extract playback fields (missing before: position, duration, play status)
        val duration = (payload["MediaSongDuration"] as? Number)?.toLong() ?: lastDuration
        val position = (payload["MediaSongPlayTime"] as? Number)?.toLong() ?: lastPosition
        val playStatus = (payload["MediaPlayStatus"] as? Number)?.toInt()
        val isPlaying = if (playStatus != null) playStatus == 1 else lastIsPlaying

        // Cache playback fields
        lastDuration = duration
        lastPosition = position
        lastIsPlaying = isPlaying

        val mediaInfo =
            MediaInfo(
                songTitle = lastMediaSongName,
                songArtist = lastMediaArtistName,
                albumName = lastMediaAlbumName,
                appName = lastMediaAppName,
                albumCover = lastAlbumCover,
                duration = duration,
                position = position,
                isPlaying = isPlaying,
            )

        // Detect whether metadata actually changed (song change or new album cover)
        // Position-only ticks (~95% of messages) skip the expensive metadata path
        val metadataChanged = (newSongName != null && newSongName != previousSongName) ||
            albumCover != null

        if (metadataChanged) {
            // Full metadata update (title/artist/album/cover/duration)
            mediaSessionManager?.updateMetadata(
                title = mediaInfo.songTitle,
                artist = mediaInfo.songArtist,
                album = mediaInfo.albumName,
                appName = mediaInfo.appName,
                albumArt = mediaInfo.albumCover,
                duration = duration,
            )

            // Update foreground notification with current now-playing
            CarlinkMediaBrowserService.updateNowPlaying(mediaInfo.songTitle, mediaInfo.songArtist)
        }

        // Always update playback state (position ticks are the common case)
        mediaSessionManager?.updatePlaybackState(playing = isPlaying, position = position)
    }

    /**
     * Simple error handler for adapter communication failures.
     *
     * For USB disconnects, schedules auto-reconnect with exponential backoff.
     */
    private fun handleError(error: String) {
        clearPairTimeout()

        logError("Adapter error: $error", tag = Logger.Tags.ADAPTR)

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
            setStatusText("Connection failed — tap to retry")
            // Stop FGS since we're no longer attempting to reconnect
            CarlinkMediaBrowserService.stopConnectionForeground(context)
            return
        }

        // Maintain foreground priority during reconnect delay to prevent LMK kill
        CarlinkMediaBrowserService.startConnectionForeground(context)

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

        setStatusText("Reconnecting ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

        reconnectJob =
            scope.launch {
                delay(delay)

                // Only attempt if still disconnected
                if (state == State.DISCONNECTED) {
                    logInfo("[RECONNECT] Attempting reconnection...", tag = Logger.Tags.USB)
                    try {
                        withContext(Dispatchers.IO) { start() }
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

        // Only for CarPlay (wired and wireless) - using 2s keyframe interval for faster recovery
        if (phoneType != PhoneType.CARPLAY && phoneType != PhoneType.CARPLAY_WIRELESS) {
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
            logInfo(
                "[FRAME_INTERVAL] Stopping periodic keyframe request (wasActive=$wasActive)",
                tag = Logger.Tags.VIDEO,
            )
            frameIntervalJob?.cancel()
        } else {
            logDebug("[FRAME_INTERVAL] Stop called but job not active (wasActive=$wasActive)", tag = Logger.Tags.VIDEO)
        }
        frameIntervalJob = null
    }

    /**
     * Create a video processor for direct USB -> codec data flow.
     * [DIRECT_HANDOFF]: Data is already in a buffer. Feed codec directly or drop.
     *
     * Video header structure (20 bytes):
     * - offset 0: width (4 bytes)
     * - offset 4: height (4 bytes)
     * - offset 8: encoderState (4 bytes) - protocol ID: 3=AA, 7=CarPlay
     * - offset 12: pts (4 bytes) - SOURCE PRESENTATION TIMESTAMP (milliseconds)
     * - offset 16: flags (4 bytes) - always 0
     */
    private fun createVideoProcessor(): UsbDeviceWrapper.VideoDataProcessor {
        return object : UsbDeviceWrapper.VideoDataProcessor {
            override fun processVideoDirect(data: ByteArray, dataLength: Int, sourcePtsMs: Int) {
                val renderer = h264Renderer ?: run {
                    // Data already read by UsbDeviceWrapper — just discard by returning
                    val now = System.currentTimeMillis()
                    if (now - lastVideoDiscardWarningTime > 2000) {
                        lastVideoDiscardWarningTime = now
                        logWarn("Video frame discarded - H264Renderer not initialized.", tag = Logger.Tags.VIDEO)
                    }
                    return
                }

                logVideoUsb { "processVideoDirect: dataLength=$dataLength, pts=$sourcePtsMs" }

                // Skip 20-byte video header, feed H.264 data directly to codec
                if (dataLength > 20) {
                    renderer.feedDirect(data, 20, dataLength - 20)
                }
            }
        }
    }

    private fun tryPrestageCodecCsd(btMac: String) {
        val cacheKey = "${btMac}_${config.width}x${config.height}"
        val prefs = context.getSharedPreferences("carlink_csd_cache", Context.MODE_PRIVATE)
        val spsB64 = prefs.getString("sps_$cacheKey", null) ?: return
        val ppsB64 = prefs.getString("pps_$cacheKey", null) ?: return

        val sps = android.util.Base64.decode(spsB64, android.util.Base64.NO_WRAP)
        val pps = android.util.Base64.decode(ppsB64, android.util.Base64.NO_WRAP)

        logInfo("[DEVICE] CSD cache hit for $cacheKey — pre-warming codec", tag = Logger.Tags.VIDEO)
        h264Renderer?.configureWithCsd(sps, pps)
    }

    private fun parseDevList(json: JSONObject): List<Pair<String, String>> {
        val arr = json.optJSONArray("DevList") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id", "")
            val type = obj.optString("type", "")
            if (id.isNotEmpty()) Pair(id, type) else null
        }
    }

    private fun log(message: String) {
        logDebug(message, tag = Logger.Tags.ADAPTR)
    }
}
