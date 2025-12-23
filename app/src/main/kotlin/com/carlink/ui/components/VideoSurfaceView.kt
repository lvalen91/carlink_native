package com.carlink.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.carlink.logging.logInfo
import com.carlink.logging.logVideoSurface
import com.carlink.logging.logWarn

/**
 * VideoSurfaceView - Native SurfaceView for H.264 Video Rendering
 *
 * Custom SurfaceView that provides:
 * - Direct Surface access for MediaCodec hardware decoding (HWC overlay path)
 * - Built-in multi-touch event handling
 * - SurfaceHolder lifecycle callbacks
 *
 * OPTIMIZATION: Uses SurfaceView instead of TextureView for:
 * - Lower latency (direct HWC composition vs GPU)
 * - Lower power consumption
 * - DRM content support
 *
 * Touch events are handled via onTouchEvent() with proper focus settings
 * to ensure multi-touch gestures are received correctly.
 *
 * @see <a href="https://source.android.com/docs/core/graphics/arch-tv">Android Graphics Architecture</a>
 */
class VideoSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        /**
         * Callback interface for surface and touch events.
         */
        interface Callback {
            /**
             * Called when the surface is created and ready for rendering.
             * @param surface The Surface to pass to MediaCodec
             * @param width Surface width in pixels
             * @param height Surface height in pixels
             */
            fun onSurfaceCreated(
                surface: Surface,
                width: Int,
                height: Int,
            )

            /**
             * Called when surface dimensions change.
             * @param width New width in pixels
             * @param height New height in pixels
             */
            fun onSurfaceChanged(
                width: Int,
                height: Int,
            )

            /**
             * Called when the surface is destroyed.
             * MediaCodec must stop using the surface before this returns.
             */
            fun onSurfaceDestroyed()

            /**
             * Called for touch events on this view.
             * @param event The MotionEvent containing touch data
             * @return true if the event was handled, false otherwise
             */
            fun onTouchEvent(event: MotionEvent): Boolean
        }

        /**
         * Callback for surface and touch events.
         */
        var callback: Callback? = null

        /**
         * Current surface width (0 if not created).
         */
        var surfaceWidth: Int = 0
            private set

        /**
         * Current surface height (0 if not created).
         */
        var surfaceHeight: Int = 0
            private set

        /**
         * Whether the surface is currently valid.
         */
        var isSurfaceCreated: Boolean = false
            private set

        init {
            // Register for surface callbacks
            holder.addCallback(this)

            // Enable focus for touch events
            // Required for SurfaceView to receive onTouchEvent calls
            // See: https://stackoverflow.com/questions/28979683/android-surfaceview-not-responding-to-touch-events
            isFocusable = true
            isFocusableInTouchMode = true

            logInfo("[VIDEO_SURFACE_VIEW] Initialized with focus enabled", tag = "UI")
        }

        // ==================== Touch Handling ====================

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Delegate to callback for touch handling
            // This allows the parent to handle multi-touch gestures
            //
            // Note: performClick() is not called here because this view handles
            // continuous touch input for CarPlay/Android Auto gesture streaming,
            // not discrete click actions. Accessibility services are not applicable
            // for this use case as the touch coordinates are forwarded to the
            // connected phone for processing.
            return callback?.onTouchEvent(event) ?: super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            // Required for accessibility - call super to handle accessibility events
            return super.performClick()
        }

        // ==================== SurfaceHolder.Callback ====================

        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceCreated = true
            logInfo("[VIDEO_SURFACE_VIEW] Surface created", tag = "UI")
            logVideoSurface { "SurfaceHolder created, awaiting dimensions" }

            // Note: We don't call callback here because dimensions aren't final yet
            // surfaceChanged will be called immediately after with correct dimensions
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            surfaceWidth = width
            surfaceHeight = height

            logInfo("[VIDEO_SURFACE_VIEW] Surface changed: ${width}x$height, format=$format", tag = "UI")
            logVideoSurface { "surfaceChanged: ${width}x$height, format=$format, isCreated=$isSurfaceCreated" }

            // If this is the first surfaceChanged after surfaceCreated, treat as creation
            // Otherwise treat as a resize
            if (isSurfaceCreated) {
                callback?.onSurfaceCreated(holder.surface, width, height)
            } else {
                callback?.onSurfaceChanged(width, height)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            logWarn("[VIDEO_SURFACE_VIEW] Surface destroyed", tag = "UI")
            logVideoSurface { "surfaceDestroyed: was ${surfaceWidth}x$surfaceHeight" }

            isSurfaceCreated = false
            surfaceWidth = 0
            surfaceHeight = 0

            // Notify callback - MediaCodec must stop before this returns
            callback?.onSurfaceDestroyed()
        }

        // ==================== Utility ====================

        /**
         * Get the current Surface, or null if not created.
         */
        fun getSurface(): Surface? = if (isSurfaceCreated) holder.surface else null
    }
