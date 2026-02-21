package com.carlink.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Dark color scheme optimized for automotive (reduces glare, high contrast). */
private val DarkColorScheme =
    darkColorScheme(
        // Primary colors - Dark teal/cyan based on #003E49
        primary = Color(0xFF5FD5ED), // Light cyan for primary actions
        onPrimary = Color(0xFF003640), // Dark teal text on primary
        primaryContainer = Color(0xFF004E5C), // Medium teal container
        onPrimaryContainer = Color(0xFFB5EEFF), // Light cyan text on container
        // Secondary colors - Complementary cool tones
        secondary = Color(0xFFB0CBCE), // Light blue-gray
        onSecondary = Color(0xFF1B3438), // Dark blue-gray
        secondaryContainer = Color(0xFF324B4F), // Medium blue-gray container
        onSecondaryContainer = Color(0xFFCCE7EA), // Very light blue-gray
        // Tertiary colors - Warm accent (yellow-orange for warnings)
        tertiary = Color(0xFFFFB951), // Warm yellow-orange
        onTertiary = Color(0xFF432C00), // Dark brown
        tertiaryContainer = Color(0xFF604000), // Medium brown container
        onTertiaryContainer = Color(0xFFFFDDB0), // Light cream
        // Error colors - Bright red for destructive actions (tone 20)
        error = Color(0xFFBA1A1A), // Deep vibrant red (tone 20)
        onError = Color(0xFFFFFFFF), // White text on red
        errorContainer = Color(0xFF93000A), // Dark red container
        onErrorContainer = Color(0xFFFFDAD6), // Light pink text
        // Surface colors - Dark backgrounds with proper elevation
        surface = Color(0xFF0E1415), // Deep dark teal-gray
        onSurface = Color(0xFFDDE4E5), // Light gray text
        surfaceContainerHighest = Color(0xFF30393A), // Elevated surfaces (cards)
        surfaceContainerHigh = Color(0xFF252E2F),
        surfaceContainer = Color(0xFF1A2324),
        surfaceContainerLow = Color(0xFF171D1E),
        surfaceContainerLowest = Color(0xFF090F10),
        surfaceVariant = Color(0xFF30393A), // Same as surfaceContainerHighest
        onSurfaceVariant = Color(0xFF889394), // Outline color for secondary text
        // Outline colors - Borders and dividers
        outline = Color(0xFF889394),
        outlineVariant = Color(0xFF3F484A),
        // Scrim - Modal overlays
        scrim = Color(0xFF000000),
        // Inverse colors - Contrasting elements
        inverseSurface = Color(0xFFDDE4E5),
        inverseOnSurface = Color(0xFF2B3132),
        inversePrimary = Color(0xFF006780),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF006780),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB5EEFF),
        onPrimaryContainer = Color(0xFF001F28),
        secondary = Color(0xFF4A6366),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE7EA),
        onSecondaryContainer = Color(0xFF051F22),
        tertiary = Color(0xFF7A5900),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDDB0),
        onTertiaryContainer = Color(0xFF261900),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        surface = Color(0xFFF5FAFB),
        onSurface = Color(0xFF171D1E),
        // Surface container colors for cards and elevated surfaces (teal-tinted)
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFEFF5F6),
        surfaceContainer = Color(0xFFE9EFF0),
        surfaceContainerHigh = Color(0xFFE3EAEB),
        surfaceContainerHighest = Color(0xFFDDE4E6),
        surfaceVariant = Color(0xFFDBE4E6),
        onSurfaceVariant = Color(0xFF3F484A),
        outline = Color(0xFF6F797A),
        outlineVariant = Color(0xFFBFC8CA),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2B3132),
        inverseOnSurface = Color(0xFFECF2F3),
        inversePrimary = Color(0xFF5FD5ED),
    )

/** Material 3 theme with automotive-optimized colors and typography. */
@Composable
fun CarlinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // Follow AAOS system theme
    dynamicColor: Boolean = false, // Disabled for consistent branding
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColorScheme
            }

            else -> {
                LightColorScheme
            }
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CarlinkTypography,
        content = content,
    )
}

val CarlinkTypography =
    Typography(
        // Large display for status text
        displayLarge =
            Typography().displayLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
        // Titles for sections
        titleLarge =
            Typography().titleLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
        // Labels for buttons (larger for touch targets)
        labelLarge =
            Typography().labelLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
    )

object AutomotiveDimens {
    val ButtonMinHeight = 72.dp
    val ButtonPaddingHorizontal = 24.dp
    val ButtonPaddingVertical = 20.dp
    val IconSize = 28.dp
}
