package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Settings Tab Enum with title and icon.
 * Matches Flutter: settings_enums.dart SettingsTab
 */
enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.Filled.Article),
    ;

    companion object {
        val visibleTabs: List<SettingsTab> = entries
    }
}
