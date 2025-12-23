package com.carlink.ui

import android.view.MotionEvent
import android.view.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import com.carlink.ui.components.LoadingSpinner
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch

/**
 * Main Screen - Primary Projection Display Interface
 *
 * The main screen for the Carlink App, displays projection video and handles touch input.
 *
 * Key Responsibilities:
 * - Video Rendering: Displays H.264 video stream via SurfaceView (HWC overlay)
 * - Touch Input: Captures multitouch gestures and forwards to adapter
 * - Connection Lifecycle: Manages adapter state and displays status
 *
 * OPTIMIZATION: Uses SurfaceView for direct HWC overlay rendering:
 * - Lower latency than TextureView (no GPU composition)
 * - Lower power consumption
 * - Touch events handled directly by VideoSurfaceView
 */
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    displayMode: DisplayMode,
    onNavigateToSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // State
    var state by remember { mutableStateOf(CarlinkManager.State.DISCONNECTED) }
    var isResetting by remember { mutableStateOf(false) }
    val surfaceState = rememberVideoSurfaceState()

    // Log state changes for debugging
    LaunchedEffect(state) {
        logInfo("[UI_STATE] MainScreen connection state: $state", tag = "UI")
    }

    // Track if user is interacting with CarPlay projection
    var lastTouchTime by remember { mutableLongStateOf(0L) }
    val isUserInteractingWithProjection = state == CarlinkManager.State.STREAMING

    // Touch tracking
    val activeTouches = remember { mutableStateMapOf<Int, TouchPoint>() }

    // Initialize when surface is available
    // Pass actual surface dimensions to configure adapter with correct resolution
    LaunchedEffect(surfaceState.surface, surfaceState.width, surfaceState.height) {
        surfaceState.surface?.let { surface ->
            // Only initialize if we have valid dimensions
            if (surfaceState.width <= 0 || surfaceState.height <= 0) return@let

            carlinkManager.initialize(
                surface = surface,
                surfaceWidth = surfaceState.width,
                surfaceHeight = surfaceState.height,
                callback =
                    object : CarlinkManager.Callback {
                        override fun onStateChanged(newState: CarlinkManager.State) {
                            state = newState
                        }

                        override fun onMediaInfoChanged(mediaInfo: CarlinkManager.MediaInfo) {
                            // Can update UI with media info if needed
                        }

                        override fun onLogMessage(message: String) {
                            // Log messages handled by Logger
                        }

                        override fun onHostUIPressed() {
                            // Open settings overlay - video continues playing
                            // (MainScreen stays in composition with overlay architecture)
                            onNavigateToSettings()
                        }
                    },
            )
            // Start connection
            carlinkManager.start()
        }
    }

    val isLoading = state != CarlinkManager.State.STREAMING
    val colorScheme = MaterialTheme.colorScheme

    // Apply system bar insets based on display mode
    // This matches Flutter's SafeArea behavior with _disableSafeArea flag:
    // - FULLSCREEN_IMMERSIVE: No insets, video fills entire screen (1920x1080)
    // - Other modes: Apply insets to constrain video to usable area
    val baseModifier =
        Modifier
            .fillMaxSize()
            .background(Color.Black)

    val boxModifier =
        if (displayMode == DisplayMode.FULLSCREEN_IMMERSIVE) {
            baseModifier // No insets in fullscreen immersive mode - fill entire screen
        } else {
            baseModifier.windowInsetsPadding(WindowInsets.systemBars) // Apply insets
        }

    Box(modifier = boxModifier) {
        // Video surface with touch handling
        // Uses SurfaceView for direct HWC overlay rendering (lower latency, lower power)
        // Touch events are handled directly by VideoSurfaceView.onTouchEvent()
        VideoSurface(
            modifier = Modifier.fillMaxSize(),
            onSurfaceAvailable = { surface, width, height ->
                logInfo("[UI_SURFACE] Surface available: ${width}x$height", tag = "UI")
                surfaceState.onSurfaceAvailable(surface, width, height)
            },
            onSurfaceDestroyed = {
                logInfo("[UI_SURFACE] Surface destroyed", tag = "UI")
                surfaceState.onSurfaceDestroyed()
            },
            onSurfaceSizeChanged = { width, height ->
                logInfo("[UI_SURFACE] Surface size changed: ${width}x$height", tag = "UI")
                surfaceState.onSurfaceSizeChanged(width, height)
            },
            onTouchEvent = { event ->
                // Log touch events in debug builds (throttled to avoid spam)
                if (BuildConfig.DEBUG && isUserInteractingWithProjection) {
                    val now = System.currentTimeMillis()
                    if (now - lastTouchTime > 1000) { // Log at most once per second
                        logDebug(
                            "[UI_TOUCH] CarPlay projection touch: action=${event.actionMasked}, pointers=${event.pointerCount}",
                            tag = "UI",
                        )
                        lastTouchTime = now
                    }
                }
                handleTouchEvent(event, activeTouches, carlinkManager, surfaceState.width, surfaceState.height)
                true
            },
        )

        // Loading overlay
        if (isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(colorScheme.scrim.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Projection icon - vector drawable scales without quality loss
                    Image(
                        painter = painterResource(id = R.drawable.ic_phone_projection),
                        contentDescription = "Carlink",
                        modifier = Modifier.height(220.dp),
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Loading indicator - AVD for better AAOS hardware compatibility
                    LoadingSpinner(
                        color = colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Status text
                    Text(
                        text =
                            when (state) {
                                CarlinkManager.State.DISCONNECTED -> "[ Connect Adapter ]"
                                CarlinkManager.State.CONNECTING -> "[ Connecting... ]"
                                CarlinkManager.State.DEVICE_CONNECTED -> "[ Waiting for Phone ]"
                                CarlinkManager.State.STREAMING -> "[ Streaming ]"
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurface,
                    )
                }
            }

            // Control buttons (visible when loading)
            // Apply system bar insets to avoid overlap when not in immersive mode
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Settings button
                FilledTonalButton(
                    onClick = {
                        // Open settings overlay - video continues playing
                        onNavigateToSettings()
                    },
                    modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                    contentPadding =
                        PaddingValues(
                            horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                            vertical = AutomotiveDimens.ButtonPaddingVertical,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        modifier = Modifier.size(AutomotiveDimens.IconSize),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                // Reset button with animated icon transition
                FilledTonalButton(
                    onClick = {
                        if (!isResetting) {
                            isResetting = true
                            scope.launch {
                                try {
                                    carlinkManager.restart()
                                } finally {
                                    isResetting = false
                                }
                            }
                        }
                    },
                    enabled = !isResetting,
                    modifier = Modifier.height(AutomotiveDimens.ButtonMinHeight),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = colorScheme.errorContainer,
                            contentColor = colorScheme.onErrorContainer,
                        ),
                    contentPadding =
                        PaddingValues(
                            horizontal = AutomotiveDimens.ButtonPaddingHorizontal,
                            vertical = AutomotiveDimens.ButtonPaddingVertical,
                        ),
                ) {
                    AnimatedContent(
                        targetState = isResetting,
                        transitionSpec = {
                            (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                        },
                        label = "resetIconTransition",
                    ) { resetting ->
                        if (resetting) {
                            LoadingSpinner(
                                size = AutomotiveDimens.IconSize,
                                color = colorScheme.onErrorContainer,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset Device",
                                modifier = Modifier.size(AutomotiveDimens.IconSize),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset Device",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// Touch point data class
private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

/**
 * Handle touch events and forward to Carlink adapter.
 */
private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0) return

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)

    val action =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
            MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> MultiTouchAction.UP
            else -> return
        }

    // Normalize coordinates to 0-1 range
    val x = event.getX(pointerIndex) / surfaceWidth
    val y = event.getY(pointerIndex) / surfaceHeight

    // Update active touches
    when (action) {
        MultiTouchAction.DOWN -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        MultiTouchAction.MOVE -> {
            // Update all pointers on move
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = event.getX(i) / surfaceWidth
                val py = event.getY(i) / surfaceHeight
                activeTouches[id]?.let { existing ->
                    // Only update if moved significantly
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) {
                        activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                    }
                }
            }
        }

        MultiTouchAction.UP -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
        }

        else -> {}
    }

    // Build touch list and send
    val touchList =
        activeTouches.entries.mapIndexed { index, entry ->
            MessageSerializer.TouchPoint(
                x = entry.value.x,
                y = entry.value.y,
                action = entry.value.action,
                id = index,
            )
        }

    carlinkManager.sendMultiTouch(touchList)

    // Remove completed touches
    activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
}
