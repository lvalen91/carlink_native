package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article),
    PLAYBACK("Playback", Icons.Default.PlayCircle),
    ;

    companion object {
        val visibleTabs: List<SettingsTab> = entries
    }
}
