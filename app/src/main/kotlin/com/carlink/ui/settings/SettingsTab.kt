package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlink.BuildConfig

enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    CONTROL("Control", Icons.Default.Settings),
    HOME("Home", Icons.Default.Home),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        /** Tabs visible in the current build. HOME is debug-only. */
        val visible: List<SettingsTab>
            get() =
                entries.filter { tab ->
                    when (tab) {
                        HOME -> BuildConfig.DEBUG
                        LOGS -> !BuildConfig.DEBUG
                        else -> true
                    }
                }
    }
}
