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

/** AVD-based loading spinner with better GPU driver support on automotive platforms. */
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
            imageView.drawable?.let { drawable ->
                DrawableCompat.setTint(drawable, colorArgb)
                (drawable as? AnimatedVectorDrawable)?.let { avd ->
                    if (!avd.isRunning) avd.start()
                }
            }
        },
    )
}
