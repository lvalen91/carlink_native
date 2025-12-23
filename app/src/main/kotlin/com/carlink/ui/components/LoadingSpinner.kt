package com.carlink.ui.components

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.carlink.R

/**
 * Loading spinner using Animated Vector Drawable (AVD) for optimal hardware compatibility.
 *
 * AVD uses Android's native animation system which has better GPU driver support
 * than Compose canvas-based animations on automotive platforms (especially Intel-based
 * GM AAOS head units).
 *
 * @param modifier Modifier for the spinner
 * @param size Size of the spinner (default 48.dp)
 * @param color Tint color for the spinner (default primary color)
 */
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val colorArgb = color.toArgb()

    AndroidView(
        modifier = modifier.size(size),
        factory = { context ->
            ImageView(context).apply {
                val drawable = ContextCompat.getDrawable(context, R.drawable.avd_spinner)
                drawable?.let { avd ->
                    DrawableCompat.setTint(avd, colorArgb)
                    setImageDrawable(avd)
                    (avd as? AnimatedVectorDrawable)?.start()
                }
            }
        },
        update = { imageView ->
            // Update tint color if it changes
            imageView.drawable?.let { drawable ->
                DrawableCompat.setTint(drawable, colorArgb)
                // Ensure animation is running
                (drawable as? AnimatedVectorDrawable)?.let { avd ->
                    if (!avd.isRunning) {
                        avd.start()
                    }
                }
            }
        },
    )
}
