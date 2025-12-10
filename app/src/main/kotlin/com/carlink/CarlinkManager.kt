package com.carlink

import android.content.Context
import android.hardware.usb.UsbManager
import android.view.Surface
import com.carlink.audio.DualStreamAudioManager
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.logging.logVideoUsb
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
import com.carlink.usb.UsbDeviceWrapper
import com.carlink.util.AppExecutors
import com.carlink.util.LogCallback
import com.carlink.video.H264Renderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var currentAudioDecodeType = 4 // Default to 48kHz stereo

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
    private var frameInterval: Timer? = null

    // Recovery tracking (matches Flutter CarlinkPlugin.kt)
    private var lastResetTime: Long = 0
    private var consecutiveResets: Int = 0

    // Media metadata tracking
    private var lastMediaSongName: String? = null
    private var lastMediaArtistName: String? = null
    private var lastMediaAlbumName: String? = null
    private var lastMediaAppName: String? = null
    private var lastAlbumCover: ByteArray? = null

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
                "[RES] Updating config from ${config.width}x${config.height} to ${evenWidth}x${evenHeight} " +
                    "(actual surface size)",
                tag = Logger.Tags.VIDEO
            )
            config = config.copy(width = evenWidth, height = evenHeight)
        }

        // Guard: If already initialized with the same Surface, just update callback
        // This prevents duplicate H264Renderer instances which cause Surface connection conflicts
        if (h264Renderer != null && this.videoSurface === surface) {
            logInfo("Already initialized with same Surface, updating callback only", tag = Logger.Tags.VIDEO)
            this.callback = callback
            return
        }

        // Clean up existing renderer before creating new one to prevent Surface conflicts
        if (h264Renderer != null) {
            logInfo("Cleaning up existing H264Renderer before re-initialization", tag = Logger.Tags.VIDEO)
            h264Renderer?.stop()
            h264Renderer = null
        }

        this.callback = callback
        this.videoSurface = surface

        logInfo(
            "[RES] Initializing with surface ${evenWidth}x${evenHeight} @ ${config.fps}fps, ${config.dpi}dpi",
            tag = Logger.Tags.VIDEO
        )

        // Detect platform for optimal audio configuration
        val platformInfo = PlatformDetector.detect(context)
        val audioConfig = AudioConfig.forPlatform(platformInfo)

        logInfo(
            "[PLATFORM] Using AudioConfig: sampleRate=${audioConfig.sampleRate}Hz, " +
                "bufferMult=${audioConfig.bufferMultiplier}x, prefill=${audioConfig.prefillThresholdMs}ms",
            tag = Logger.Tags.AUDIO
        )

        // Initialize H264 renderer with Surface for direct HWC rendering
        // Surface comes from SurfaceView - no GPU composition overhead
        h264Renderer =
            H264Renderer(
                context,
                config.width,
                config.height,
                surface,
                logCallback,
                executors,
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

        // Initialize MediaSession
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
                tag = Logger.Tags.VIDEO
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

        adapterDriver?.start(config)

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
        clearPairTimeout()
        clearFrameInterval()
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
        kotlinx.coroutines.delay(2000)
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
            }

            State.DISCONNECTED -> {
                mediaSessionManager?.setStateStopped()
            }

            else -> {} // Playback state updated when audio starts
        }
    }

    private suspend fun findDevice(): UsbDeviceWrapper? {
        var device: UsbDeviceWrapper? = null
        var attempts = 0

        while (device == null && attempts < 10) {
            device = UsbDeviceWrapper.findFirst(context, usbManager) { log(it) }

            if (device == null) {
                attempts++
                kotlinx.coroutines.delay(USB_WAIT_PERIOD_MS)
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
                clearPairTimeout()
                clearFrameInterval()

                // Start frame interval if needed for CarPlay
                // This periodic keyframe request keeps video streaming stable
                if (message.phoneType == PhoneType.CARPLAY) {
                    logInfo("[FRAME_INTERVAL] Starting periodic keyframe request (every 5s) for CarPlay", tag = Logger.Tags.VIDEO)
                    frameInterval =
                        Timer().apply {
                            scheduleAtFixedRate(
                                object : TimerTask() {
                                    override fun run() {
                                        adapterDriver?.sendCommand(CommandMapping.FRAME)
                                    }
                                },
                                5000,
                                5000,
                            )
                        }
                }

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
        when (command) {
            AudioCommand.AUDIO_SIRI_START -> {
                logInfo("Siri started - enabling microphone", tag = Logger.Tags.MIC)
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_PHONECALL_START -> {
                logInfo("Phone call started - enabling microphone", tag = Logger.Tags.MIC)
                startMicrophoneCapture(decodeType = 5, audioType = 3)
            }

            AudioCommand.AUDIO_SIRI_STOP -> {
                logInfo("Siri stopped - disabling microphone", tag = Logger.Tags.MIC)
                stopMicrophoneCapture()
            }

            AudioCommand.AUDIO_PHONECALL_STOP -> {
                logInfo("Phone call stopped - disabling microphone", tag = Logger.Tags.MIC)
                stopMicrophoneCapture()
            }

            else -> {}
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

        // Check for album cover
        val albumCover = payload["AlbumCover"] as? ByteArray
        if (albumCover != null) {
            lastAlbumCover = albumCover
        }

        // Extract text metadata
        (payload["MediaSongName"] as? String)?.takeIf { it.isNotEmpty() }?.let {
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

        // Only clear frame interval for non-recoverable errors
        clearFrameInterval()

        // Set state to disconnected
        setState(State.DISCONNECTED)
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

    private fun clearFrameInterval() {
        if (frameInterval != null) {
            logInfo("[FRAME_INTERVAL] Stopping periodic keyframe request", tag = Logger.Tags.VIDEO)
            frameInterval?.cancel()
            frameInterval = null
        }
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
                readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int
            ) {
                // Get the H264Renderer - if not available, discard data
                val renderer = h264Renderer ?: run {
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
                            tag = Logger.Tags.VIDEO
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
