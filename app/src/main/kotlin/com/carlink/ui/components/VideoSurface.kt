package com.carlink.ui.components

import android.view.MotionEvent
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * Video Surface Component for H.264 Video Rendering
 *
 * Wraps a custom VideoSurfaceView (SurfaceView) for hardware-accelerated video playback.
 * Uses HWC overlay path for optimal performance - no GPU composition overhead.
 *
 * OPTIMIZATION: SurfaceView provides:
 * - Direct HWC overlay rendering (bypasses GPU composition)
 * - Lower latency than TextureView
 * - Lower power consumption
 * - DRM content support
 *
 * Touch events are handled directly by the VideoSurfaceView and forwarded
 * to the callback for CarPlay/Android Auto gesture handling.
 */
@Composable
fun VideoSurface(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null,
    onTouchEvent: ((MotionEvent) -> Boolean)? = null,
) {
    DisposableEffect(Unit) {
        logInfo("[VIDEO_SURFACE] VideoSurface composable created", tag = "UI")
        onDispose {
            logWarn("[VIDEO_SURFACE] VideoSurface composable disposed", tag = "UI")
            onSurfaceDestroyed()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            logInfo("[VIDEO_SURFACE] Creating VideoSurfaceView", tag = "UI")
            VideoSurfaceView(context).apply {
                callback =
                    object : VideoSurfaceView.Callback {
                        override fun onSurfaceCreated(
                            surface: Surface,
                            width: Int,
                            height: Int,
                        ) {
                            logInfo("[VIDEO_SURFACE] SurfaceView.onSurfaceCreated: ${width}x$height", tag = "UI")
                            onSurfaceAvailable(surface, width, height)
                        }

                        override fun onSurfaceChanged(
                            width: Int,
                            height: Int,
                        ) {
                            logInfo("[VIDEO_SURFACE] SurfaceView.onSurfaceChanged: ${width}x$height", tag = "UI")
                            onSurfaceSizeChanged?.invoke(width, height)
                        }

                        override fun onSurfaceDestroyed() {
                            logWarn("[VIDEO_SURFACE] SurfaceView.onSurfaceDestroyed", tag = "UI")
                            onSurfaceDestroyed()
                        }

                        override fun onTouchEvent(event: MotionEvent): Boolean = onTouchEvent?.invoke(event) ?: false
                    }
            }
        },
    )
}

/**
 * State holder for video surface
 */
class VideoSurfaceState {
    var surface: Surface? by mutableStateOf(null)
        private set

    var isCreated: Boolean by mutableStateOf(false)
        private set

    var width: Int by mutableIntStateOf(0)
        private set

    var height: Int by mutableIntStateOf(0)
        private set

    fun onSurfaceAvailable(
        surface: Surface,
        w: Int,
        h: Int,
    ) {
        this.surface = surface
        width = w
        height = h
        isCreated = true
    }

    fun onSurfaceDestroyed() {
        surface = null
        isCreated = false
    }

    fun onSurfaceSizeChanged(
        w: Int,
        h: Int,
    ) {
        width = w
        height = h
    }
}

@Composable
fun rememberVideoSurfaceState(): VideoSurfaceState = remember { VideoSurfaceState() }
