package com.carlink.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn

/**
 * SurfaceView for H.264 decoding via HWC overlay (lower latency than TextureView).
 * Handles Surface lifecycle and multi-touch events.
 */
class VideoSurfaceView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        interface Callback {
            fun onSurfaceCreated(
                surface: Surface,
                width: Int,
                height: Int,
            )

            fun onSurfaceChanged(
                width: Int,
                height: Int,
            )

            fun onSurfaceDestroyed()

            fun onTouchEvent(event: MotionEvent): Boolean
        }

        var callback: Callback? = null
        var surfaceWidth: Int = 0
            private set
        var surfaceHeight: Int = 0
            private set
        var isSurfaceCreated: Boolean = false
            private set

        init {
            holder.addCallback(this)
            isFocusable = true
            isFocusableInTouchMode = true
            logInfo("[VIDEO_SURFACE_VIEW] Initialized", tag = "UI")
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = callback?.onTouchEvent(event) ?: super.onTouchEvent(event)

        override fun surfaceCreated(holder: SurfaceHolder) {
            isSurfaceCreated = true
            logInfo("[VIDEO_SURFACE_VIEW] Surface created", tag = "UI")
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            surfaceWidth = width
            surfaceHeight = height
            logInfo("[VIDEO_SURFACE_VIEW] Surface: ${width}x$height", tag = "UI")

            if (isSurfaceCreated) {
                callback?.onSurfaceCreated(holder.surface, width, height)
            } else {
                callback?.onSurfaceChanged(width, height)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            logWarn("[VIDEO_SURFACE_VIEW] Surface destroyed", tag = "UI")
            isSurfaceCreated = false
            surfaceWidth = 0
            surfaceHeight = 0
            callback?.onSurfaceDestroyed()
        }

        fun getSurface(): Surface? = if (isSurfaceCreated) holder.surface else null
    }
