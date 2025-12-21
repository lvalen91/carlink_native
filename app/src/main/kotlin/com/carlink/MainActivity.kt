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
import com.carlink.util.AudioDebugLogger
import com.carlink.util.VideoDebugLogger
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.KnownDevices
import com.carlink.util.IconAssets
import com.carlink.ui.MainScreen
import com.carlink.ui.SettingsScreen
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.ImmersivePreference
import com.carlink.ui.theme.CarlinkTheme

/**
 * Main Activity - Entry Point for Carlink Native
 *
 * Responsibilities:
 * - App initialization and lifecycle management
 * - Permission handling (microphone, USB)
 * - Immersive fullscreen mode for automotive display
 * - Navigation between main projection and settings
 *
 * Ported from: example/lib/main.dart
 */
class MainActivity : ComponentActivity() {
    // Nullable to prevent UninitializedPropertyAccessException if Activity
    // is destroyed before initialization completes (e.g., low memory kill)
    private var carlinkManager: CarlinkManager? = null
    private var fileLogManager: FileLogManager? = null
    private var isImmersiveModeEnabled: Boolean = false

    // Permission launcher
    private val micPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            logInfo("Microphone permission ${if (isGranted) "granted" else "denied"}", tag = "MAIN")
        }

    /**
     * BroadcastReceiver for USB device detachment events.
     *
     * Provides immediate detection when the Carlinkit adapter is physically
     * disconnected, enabling faster recovery than waiting for USB transfer errors.
     *
     * This is a feature that neither the original Flutter carlink nor carlink_native
     * had - both relied on transfer error detection for physical disconnection.
     */
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == intent.action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                            tag = "MAIN"
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

        // Load immersive mode preference and apply BEFORE calculating display dimensions
        // This ensures correct viewport sizing - immersive mode uses full screen (1920x1080),
        // non-immersive uses usable area excluding system bars (1920x822)
        loadAndApplyImmersiveMode()

        // Initialize Carlink manager (must be AFTER immersive mode is applied)
        // so display dimensions are calculated correctly for the active mode
        initializeCarlinkManager()

        // Request microphone permission if needed
        requestMicrophonePermission()

        // Register USB detachment receiver for immediate disconnect detection
        registerUsbDetachReceiver()

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
                        isImmersiveMode = isImmersiveModeEnabled,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restore immersive mode when returning to app (only if preference is enabled)
        if (isImmersiveModeEnabled) {
            applyImmersiveMode()
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
        // In release builds, verbose video/audio pipeline logging is disabled for performance
        val isDebugBuild = BuildConfig.DEBUG
        Logger.setDebugLoggingEnabled(isDebugBuild)
        VideoDebugLogger.setDebugEnabled(isDebugBuild)
        AudioDebugLogger.setDebugEnabled(isDebugBuild)

        // Apply default log preset based on build type
        // Release: SILENT (errors only) - user can override via settings
        // Debug: NORMAL (standard logging)
        if (!isDebugBuild) {
            LogPreset.SILENT.apply()
        }

        logInfo("Carlink Native starting - version $appVersion", tag = "MAIN")
        logInfo("[LOGGING] Debug logging: ${if (isDebugBuild) "ENABLED" else "DISABLED (release build)"}", tag = "MAIN")

        if (isDebugBuild) {
            logInfo(
                "[LOGGING] Video pipeline debug tags enabled: VIDEO_USB, VIDEO_RING, VIDEO_CODEC, VIDEO_SURFACE, VIDEO_PERF",
                tag = "MAIN"
            )
            logInfo(
                "[LOGGING] Audio pipeline debug tags enabled: AUDIO_USB, AUDIO_BUFFER, AUDIO_TRACK, AUDIO_STREAM, AUDIO_PERF",
                tag = "MAIN"
            )
        }
    }

    private fun initializeCarlinkManager() {
        // Get window metrics to determine USABLE area (excluding system UI)
        // This matches Flutter's DisplayMetricsHandler.handleGetWindowBounds() approach
        // Using WindowMetrics API (minSdk 32 guarantees API 30+ availability)
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val windowInsets = windowMetrics.windowInsets

        // Get system bars insets (status bar, navigation bar, display cutouts)
        val insets = windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout()
        )

        // Calculate usable dimensions (window bounds minus system UI)
        val usableWidth = bounds.width() - insets.left - insets.right
        val usableHeight = bounds.height() - insets.top - insets.bottom

        // Get DPI and refresh rate from display metrics
        val displayMetrics = resources.displayMetrics
        val dpi = displayMetrics.densityDpi
        val refreshRate = windowManager.defaultDisplay.refreshRate.toInt()

        // Round to even numbers for H.264 compatibility
        val evenWidth = usableWidth and 1.inv()
        val evenHeight = usableHeight and 1.inv()

        // Load icons from assets for adapter initialization
        val (icon120, icon180, icon256) = IconAssets.loadIcons(this)
        val iconsLoaded = icon120 != null && icon180 != null && icon256 != null

        // Load user-configured adapter settings from sync cache (instant, no I/O blocking)
        // These are optional - only configured settings are sent to the adapter
        val userConfig = AdapterConfigPreference.getInstance(this).getUserConfigSync()

        val config =
            AdapterConfig(
                width = evenWidth,
                height = evenHeight,
                fps = refreshRate,
                dpi = dpi,
                icon120Data = icon120,
                icon180Data = icon180,
                icon256Data = icon256,
                // User-configured audio transfer mode (null if not configured)
                audioTransferMode = userConfig.audioTransferMode,
            )

        logInfo(
            "[WINDOW] Bounds: ${bounds.width()}x${bounds.height()}, " +
                "Usable: ${usableWidth}x${usableHeight}, " +
                "Insets: T:${insets.top} B:${insets.bottom} L:${insets.left} R:${insets.right}",
            tag = "MAIN"
        )
        logInfo("Display config: ${config.width}x${config.height}@${config.fps}fps, ${config.dpi}dpi", tag = "MAIN")
        logInfo("Icons loaded: $iconsLoaded (120: ${icon120?.size ?: 0}B, 180: ${icon180?.size ?: 0}B, 256: ${icon256?.size ?: 0}B)", tag = "MAIN")
        logInfo(
            "[ADAPTER_CONFIG] User config: audioTransferMode=${userConfig.audioTransferMode?.let { if (it) "bluetooth" else "adapter" } ?: "not configured"}",
            tag = "MAIN"
        )

        carlinkManager = CarlinkManager(this, config)
    }

    private fun requestMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED -> {
                logInfo("Microphone permission already granted", tag = "MAIN")
            }

            else -> {
                logInfo("Requesting microphone permission", tag = "MAIN")
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    /**
     * Loads immersive mode preference and applies it if enabled.
     * Uses synchronous SharedPreferences cache to avoid ANR.
     * Matches Flutter: main.dart lines 50-57
     */
    private fun loadAndApplyImmersiveMode() {
        // Read preference from sync cache (instant, no I/O blocking)
        // This ensures the viewport is correctly sized before the first build
        isImmersiveModeEnabled = ImmersivePreference.getInstance(this).isEnabledSync()

        if (isImmersiveModeEnabled) {
            applyImmersiveMode()
            logInfo("[IMMERSIVE] Enabled fullscreen immersive mode", tag = "MAIN")
        } else {
            logInfo("[IMMERSIVE] Non-immersive mode - AAOS managing system UI", tag = "MAIN")
        }
    }

    /**
     * Applies immersive fullscreen mode by hiding system bars.
     * Only called when isImmersiveModeEnabled is true.
     */
    private fun applyImmersiveMode() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
            registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbDetachReceiver, filter)
        }
        logInfo("[USB_DETACH] Registered USB detachment receiver", tag = "MAIN")
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
 * This matches Flutter's Navigator.push() behavior where the MainPage stays
 * mounted in the widget tree when SettingsPage is pushed on top.
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
    isImmersiveMode: Boolean,
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
            isImmersiveMode = isImmersiveMode,
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
