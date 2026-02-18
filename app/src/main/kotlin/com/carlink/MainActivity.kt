package com.carlink

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.carlink.logging.FileLogManager
import com.carlink.logging.LogPreset
import com.carlink.logging.Logger
import com.carlink.logging.apply
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.cluster.ClusterBindingState
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.KnownDevices
import com.carlink.ui.MainScreen
import com.carlink.ui.SettingsScreen
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModePreference
import com.carlink.ui.theme.CarlinkTheme
import com.carlink.util.IconAssets
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Main Activity - Entry Point for Carlink Native
 *
 * Responsibilities:
 * - App initialization and lifecycle management
 * - Permission handling (microphone, USB)
 * - Immersive fullscreen mode for automotive display
 * - Navigation between main projection and settings
 */
class MainActivity : ComponentActivity() {
    // Nullable to prevent UninitializedPropertyAccessException if Activity
    // is destroyed before initialization completes (e.g., low memory kill)
    private var carlinkManager: CarlinkManager? = null
    private var fileLogManager: FileLogManager? = null
    private var currentDisplayMode: DisplayMode = DisplayMode.SYSTEM_UI_VISIBLE

    // Permission launchers — chained: mic callback triggers location request
    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Location permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
        }

    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Microphone permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
            // Chain: request location after mic dialog completes
            requestLocationPermission()
        }

    /**
     * BroadcastReceiver for USB device detachment events.
     *
     * Provides immediate detection when the Carlinkit adapter is physically
     * disconnected, enabling faster recovery than waiting for USB transfer errors.
     *
     * Neither the original carlink nor early carlink_native versions had this —
     * both relied on transfer error detection for physical disconnection.
     */
    private val usbDetachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                    device?.let {
                        // Only handle if it's a known Carlinkit device
                        if (KnownDevices.isKnownDevice(it.vendorId, it.productId)) {
                            logWarn(
                                "[USB_DETACH] Carlinkit device detached: VID=0x${it.vendorId.toString(16)} " +
                                    "PID=0x${it.productId.toString(16)} path=${it.deviceName}",
                                tag = "MAIN",
                            )
                            // Notify CarlinkManager of the detachment (null-safe)
                            carlinkManager?.onUsbDeviceDetached()
                        }
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        enableEdgeToEdge()

        // Keep screen on during projection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize logging
        initializeLogging()

        // Load display mode preference and apply BEFORE calculating display dimensions
        // This ensures correct viewport sizing - fullscreen immersive uses full screen (1920x1080),
        // other modes use usable area excluding visible system bars
        loadAndApplyDisplayMode()

        // Initialize Carlink manager (must be AFTER immersive mode is applied)
        // so display dimensions are calculated correctly for the active mode
        initializeCarlinkManager()

        // Request permissions if needed (location is chained after mic dialog)
        requestMicrophonePermission()

        // Register USB detachment receiver for immediate disconnect detection
        registerUsbDetachReceiver()

        // Launch CarAppActivity to trigger Templates Host → cluster binding chain.
        // Delayed to avoid interrupting USB permission dialog on first connect.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDestroyed && !isFinishing) {
                launchCarAppActivity()
            }
        }, 4000)

        // Set up Compose UI
        // carlinkManager is guaranteed non-null here since initializeCarlinkManager()
        // completed synchronously above. Use !! with confidence.
        val manager = carlinkManager!!
        setContent {
            CarlinkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CarlinkApp(
                        carlinkManager = manager,
                        fileLogManager = fileLogManager,
                        displayMode = currentDisplayMode,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore display mode when returning to app
        // System may have shown bars while app was in background
        applyDisplayMode(currentDisplayMode)
    }

    override fun onStart() {
        super.onStart()
        // Resume video decoding when app returns to foreground
        // On AAOS, Surface may remain valid while app is in background, but
        // BufferQueue can stall. Resume codec and request keyframe for immediate video.
        logInfo("[LIFECYCLE] onStart - resuming video", tag = "MAIN")
        carlinkManager?.resumeVideo()
    }

    override fun onStop() {
        super.onStop()
        // Pause video decoding when app goes to background
        // On AAOS, when another app covers this app (Maps, Phone, etc.), the Surface
        // may remain valid but SurfaceFlinger stops consuming frames. This causes
        // BufferQueue to fill up, stalling the decoder. Flushing prevents this.
        // USB connection and audio continue unaffected.
        logInfo("[LIFECYCLE] onStop - pausing video", tag = "MAIN")
        carlinkManager?.pauseVideo()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Only re-launch cluster binding for actual USB re-attach events.
        // The "bring back" REORDER_TO_FRONT intent from launchCarAppActivity()
        // also arrives here (singleTop) — must NOT re-trigger the launch cycle.
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            logInfo("[LIFECYCLE] onNewIntent: USB_DEVICE_ATTACHED — re-launching cluster binding", tag = "MAIN")
            launchCarAppActivity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unregister USB detachment receiver
        unregisterUsbDetachReceiver()

        // Release resources (null-safe in case Activity destroyed before init completed)
        carlinkManager?.release()
        fileLogManager?.release()

        logInfo("MainActivity destroyed", tag = "MAIN")
    }

    private fun initializeLogging() {
        // Initialize file logging
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val appVersion = "${packageInfo.versionName}+${packageInfo.longVersionCode}"

        fileLogManager =
            FileLogManager(
                context = this,
                sessionPrefix = "carlink",
                appVersion = appVersion,
            )

        // Configure debug-only logging based on build type
        // In release builds, verbose pipeline logging is disabled for performance
        // Users can re-enable via Pipeline Debug preset in settings
        val isDebugBuild = BuildConfig.DEBUG
        Logger.setDebugLoggingEnabled(isDebugBuild)

        // Apply default log preset based on build type
        // Release: SILENT (errors only) - user can override via settings
        // Debug: NORMAL (standard logging)
        if (!isDebugBuild) {
            LogPreset.SILENT.apply()
        }

        logInfo("Carlink Native starting - version $appVersion", tag = "MAIN")
        logInfo("[LOGGING] Debug logging: ${if (isDebugBuild) "ENABLED" else "DISABLED (release build)"}", tag = "MAIN")
    }

    // TODO [CODEC_INIT_TIMING]: Test deferring codec initialization until AFTER USB permission granted.
    //
    // PROBLEM: GM AAOS USB permission popup causes surface destruction + recreation cycle.
    // Current sequence: codec init → permission popup → surface destroyed → codec reset (wasted work)
    // Proposed sequence: permission popup → granted → THEN codec init (single init at final size)
    //
    // CAVEAT: Mid-session USB reset would still trigger permission popup (GM AAOS doesn't
    // remember USB permissions by design). In that case, the codec is already running and
    // the current pause/resume lifecycle handling would still apply. This optimization
    // only benefits initial app launch, not reconnection scenarios.
    //
    // Test: Move carlinkManager.initializeVideo() call to after USB permission callback.
    // Ensure touch normalization and adapter config still receive correct dimensions.

    private fun initializeCarlinkManager() {
        // Get window metrics to determine USABLE area (excluding system UI)
        // Using WindowMetrics API (minSdk 32 guarantees API 30+ availability)
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val windowInsets = windowMetrics.windowInsets

        // Separate inset sources for per-mode SafeArea computation
        val systemBarInsets = windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars()
        )
        val cutoutInsets = windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.displayCutout()
        )

        // Compute video resolution and SafeArea insets per display mode
        val videoWidth: Int
        val videoHeight: Int
        val safeInsetTop: Int
        val safeInsetBottom: Int
        val safeInsetLeft: Int
        val safeInsetRight: Int

        when (currentDisplayMode) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // System bars + cutouts both reduce video area. No SafeArea needed.
                videoWidth = bounds.width() - systemBarInsets.left - systemBarInsets.right -
                    cutoutInsets.left - cutoutInsets.right
                videoHeight = bounds.height() - systemBarInsets.top - systemBarInsets.bottom -
                    cutoutInsets.top - cutoutInsets.bottom
                safeInsetTop = 0; safeInsetBottom = 0; safeInsetLeft = 0; safeInsetRight = 0
            }
            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Nav bar visible (subtract from video), status bar hidden (cutout exposed top/sides)
                videoWidth = bounds.width() - systemBarInsets.left - systemBarInsets.right
                videoHeight = bounds.height() - systemBarInsets.bottom
                safeInsetTop = cutoutInsets.top
                safeInsetBottom = 0 // nav bar covers bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }
            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                // Full screen, all cutout areas exposed
                videoWidth = bounds.width()
                videoHeight = bounds.height()
                safeInsetTop = cutoutInsets.top
                safeInsetBottom = cutoutInsets.bottom
                safeInsetLeft = cutoutInsets.left
                safeInsetRight = cutoutInsets.right
            }
        }

        // Get DPI and refresh rate from display metrics
        val displayMetrics = resources.displayMetrics
        val dpi = displayMetrics.densityDpi
        val refreshRate = display?.refreshRate?.toInt() ?: 60

        // Round to even numbers for H.264 compatibility
        val evenWidth = videoWidth and 1.inv()
        val evenHeight = videoHeight and 1.inv()

        // Build binary ViewArea/SafeArea data for CarPlay display insets
        val viewAreaData = buildViewAreaData(evenWidth, evenHeight)
        val safeAreaData = buildSafeAreaData(
            evenWidth, evenHeight,
            safeInsetTop, safeInsetBottom, safeInsetLeft, safeInsetRight,
        )

        // Load icons from assets for adapter initialization
        val (icon120, icon180, icon256) = IconAssets.loadIcons(this)
        val iconsLoaded = icon120 != null && icon180 != null && icon256 != null

        // Load user-configured adapter settings from sync cache (instant, no I/O blocking)
        // These are optional - only configured settings are sent to the adapter
        val userConfig = AdapterConfigPreference.getInstance(this).getUserConfigSync()

        // Apply video resolution preference
        // AUTO = use detected usable dimensions, otherwise use user-selected resolution
        val userSelectedResolution = !userConfig.videoResolution.isAuto
        val (configWidth, configHeight) = if (userConfig.videoResolution.isAuto) {
            Pair(evenWidth, evenHeight)
        } else {
            // User selected a specific resolution - use it for adapter config
            // Note: Surface size remains the actual display size for touch normalization
            Pair(userConfig.videoResolution.width, userConfig.videoResolution.height)
        }

        // Map user config enums to AdapterConfig values
        val micType =
            when (userConfig.micSource) {
                com.carlink.ui.settings.MicSourceConfig.APP -> "os"
                com.carlink.ui.settings.MicSourceConfig.PHONE -> "box"
            }
        val wifiType =
            when (userConfig.wifiBand) {
                com.carlink.ui.settings.WiFiBandConfig.BAND_5GHZ -> "5ghz"
                com.carlink.ui.settings.WiFiBandConfig.BAND_24GHZ -> "24ghz"
            }

        val config =
            AdapterConfig(
                width = configWidth,
                height = configHeight,
                fps = userConfig.fps.fps,
                dpi = dpi,
                // Mark if user explicitly selected a resolution (non-AUTO)
                userSelectedResolution = userSelectedResolution,
                icon120Data = icon120,
                icon180Data = icon180,
                icon256Data = icon256,
                // User-configured audio transfer mode (false=adapter, true=bluetooth)
                audioTransferMode = userConfig.audioTransferMode,
                // Hardcoded to 48kHz - professional quality audio for GM AAOS
                sampleRate = 48000,
                // User-configured mic, wifi, call quality, and media delay
                micType = micType,
                wifiType = wifiType,
                callQuality = userConfig.callQuality.value,
                mediaDelay = userConfig.mediaDelay.delayMs,
                viewAreaData = viewAreaData,
                safeAreaData = safeAreaData,
            )

        logInfo(
            "[WINDOW] Bounds: ${bounds.width()}x${bounds.height()}, " +
                "Video: ${evenWidth}x$evenHeight, " +
                "Cutout: T:${cutoutInsets.top} B:${cutoutInsets.bottom} " +
                "L:${cutoutInsets.left} R:${cutoutInsets.right}, " +
                "DisplayMode: ${currentDisplayMode.name}",
            tag = "MAIN",
        )
        logInfo("Display config: ${config.width}x${config.height}@${config.fps}fps, ${config.dpi}dpi", tag = "MAIN")
        logInfo(
            "Icons loaded: $iconsLoaded (120: ${icon120?.size ?: 0}B, 180: ${icon180?.size ?: 0}B, 256: ${icon256?.size ?: 0}B)",
            tag = "MAIN",
        )
        logInfo(
            "[ADAPTER_CONFIG] User config: audioTransferMode=${if (userConfig.audioTransferMode) "bluetooth" else "adapter"}, " +
                "sampleRate=48000Hz (hardcoded), mic=$micType, wifi=$wifiType, callQuality=${userConfig.callQuality.name}, " +
                "mediaDelay=${userConfig.mediaDelay.name}(${userConfig.mediaDelay.delayMs}ms), " +
                "resolution=${userConfig.videoResolution.toStorageString()} (adapter: ${configWidth}x$configHeight)",
            tag = "MAIN",
        )

        carlinkManager = CarlinkManager(this, config)
    }

    /** Build HU_VIEWAREA_INFO (24 bytes): [screen_w, screen_h, view_w, view_h, originX, originY] */
    private fun buildViewAreaData(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(width).putInt(height)   // screen dims
            .putInt(width).putInt(height)   // viewarea dims (same)
            .putInt(0).putInt(0)            // origin
            .array()

    /** Build HU_SAFEAREA_INFO (20 bytes): [safe_w, safe_h, originX, originY, drawOutside] */
    private fun buildSafeAreaData(
        videoW: Int, videoH: Int,
        insetTop: Int, insetBottom: Int, insetLeft: Int, insetRight: Int,
    ): ByteArray {
        val safeW = videoW - insetLeft - insetRight
        val safeH = videoH - insetTop - insetBottom
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(safeW).putInt(safeH)
            .putInt(insetLeft).putInt(insetTop)
            .putInt(1) // drawUIOutsideSafeArea = true
            .array()
    }

    private fun requestMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Microphone permission already granted", tag = "MAIN")
                // Already granted — chain to location request directly
                requestLocationPermission()
            }

            else -> {
                logInfo("Requesting microphone permission", tag = "MAIN")
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                // Location will be requested in mic launcher callback
            }
        }
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Location permission already granted", tag = "MAIN")
            }

            else -> {
                logInfo("Requesting location permission", tag = "MAIN")
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    /**
     * Loads display mode preference and applies it.
     * Uses synchronous SharedPreferences cache to avoid ANR.
     */
    private fun loadAndApplyDisplayMode() {
        // Read preference from sync cache (instant, no I/O blocking)
        // This ensures the viewport is correctly sized before the first build
        currentDisplayMode = DisplayModePreference.getInstance(this).getDisplayModeSync()

        applyDisplayMode(currentDisplayMode)
        logInfo("[DISPLAY_MODE] Applied mode: ${currentDisplayMode.name}", tag = "MAIN")
    }

    /**
     * Applies the specified display mode by showing/hiding system bars.
     *
     * @param mode The display mode to apply
     */
    private fun applyDisplayMode(mode: DisplayMode) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        when (mode) {
            DisplayMode.SYSTEM_UI_VISIBLE -> {
                // Show all system bars - let AAOS manage display bounds
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }

            DisplayMode.STATUS_BAR_HIDDEN -> {
                // Hide status bar only, keep navigation bar visible
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            DisplayMode.FULLSCREEN_IMMERSIVE -> {
                // Hide all system bars for maximum projection area
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /**
     * Registers the USB detachment BroadcastReceiver.
     *
     * This enables immediate detection of physical adapter removal,
     * providing faster recovery than waiting for USB transfer errors.
     */
    private fun registerUsbDetachReceiver() {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, filter)
        }
        logInfo("[USB_DETACH] Registered USB detachment receiver", tag = "MAIN")
    }

    /**
     * Launches CarAppActivity in a separate task to trigger Templates Host binding.
     * Combined with taskAffinity="zeno.carlink.templates" and singleTask launch mode,
     * this opens in its own task stack without disturbing MainActivity.
     *
     * Guarded by ClusterBindingState.sessionAlive — only launches if no live session exists.
     * RendererServiceBinder.terminate() can kill the session on USB re-enumeration,
     * so this must be callable from both onCreate() and onNewIntent().
     */
    private fun launchCarAppActivity() {
        if (ClusterBindingState.sessionAlive) {
            logInfo("[CLUSTER] Cluster session still alive — will retry after teardown", tag = "MAIN")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing && !ClusterBindingState.sessionAlive) {
                    logInfo("[CLUSTER] Old session torn down — retrying launch", tag = "MAIN")
                    launchCarAppActivity()
                }
            }, 4000)
            return
        }

        try {
            val intent = Intent().apply {
                setClassName(this@MainActivity, "androidx.car.app.activity.CarAppActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            startActivity(intent)
            logInfo("[CLUSTER] Launched CarAppActivity for Templates Host binding", tag = "MAIN")

            // Bring MainActivity back quickly — binding is IPC-based and doesn't
            // need CarAppActivity in the foreground. 1s is enough for the handshake.
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isDestroyed && !isFinishing) {
                    val bringBack = Intent(this@MainActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(bringBack)
                    logInfo("[CLUSTER] Brought MainActivity back to foreground", tag = "MAIN")
                }
            }, 1000)
        } catch (e: Exception) {
            logWarn("[CLUSTER] Failed to launch CarAppActivity: ${e.message}", tag = "MAIN")
        }
    }

    /**
     * Unregisters the USB detachment BroadcastReceiver.
     */
    private fun unregisterUsbDetachReceiver() {
        try {
            unregisterReceiver(usbDetachReceiver)
            logInfo("[USB_DETACH] Unregistered USB detachment receiver", tag = "MAIN")
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered or already unregistered
            logWarn("[USB_DETACH] Receiver already unregistered: ${e.message}", tag = "MAIN")
        }
    }
}

/**
 * Main Composable App with Overlay Navigation
 *
 * ARCHITECTURE: Uses overlay/stack pattern instead of screen replacement.
 * MainScreen stays composed when SettingsScreen is pushed on top.
 *
 * WHY: The VideoSurface in MainScreen uses a TextureView with SurfaceTexture.
 * When MainScreen is replaced (disposed), the SurfaceTexture is destroyed,
 * causing "BufferQueue has been abandoned" errors if the MediaCodec is still
 * running. By keeping MainScreen always in composition and overlaying
 * SettingsScreen on top, the video continues playing uninterrupted.
 *
 * BEHAVIOR:
 * - MainScreen is ALWAYS rendered (video keeps playing)
 * - SettingsScreen slides in ON TOP when showSettings is true
 * - Back button closes SettingsScreen overlay
 * - Video is never stopped during navigation
 */
@Composable
fun CarlinkApp(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    displayMode: DisplayMode,
) {
    var showSettings by remember { mutableStateOf(false) }

    // Log screen changes for debugging
    LaunchedEffect(showSettings) {
        val screenName = if (showSettings) "SettingsScreen (overlay)" else "MainScreen (Projection)"
        logInfo("[UI_NAV] Active screen: $screenName", tag = "UI")
    }

    // Handle back button to close settings overlay
    BackHandler(enabled = showSettings) {
        logInfo("[UI_NAV] Back pressed: Closing SettingsScreen overlay", tag = "UI")
        showSettings = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // MainScreen is ALWAYS in composition - VideoSurface never gets disposed
        // This keeps the SurfaceTexture alive and video playing uninterrupted
        MainScreen(
            carlinkManager = carlinkManager,
            displayMode = displayMode,
            onNavigateToSettings = {
                logInfo("[UI_NAV] Opening SettingsScreen overlay (video continues)", tag = "UI")
                showSettings = true
            },
        )

        // SettingsScreen slides in ON TOP of MainScreen
        // MainScreen remains visible underneath (just covered)
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            SettingsScreen(
                carlinkManager = carlinkManager,
                fileLogManager = fileLogManager,
                onNavigateBack = {
                    logInfo("[UI_NAV] Closing SettingsScreen overlay", tag = "UI")
                    showSettings = false
                },
            )
        }
    }
}
