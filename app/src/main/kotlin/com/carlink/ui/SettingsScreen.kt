package com.carlink.ui

import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.CarlinkManager
import com.carlink.logging.FileLogManager
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.AdapterConfigurationDialog
import com.carlink.ui.settings.ConfigurationOptionCard
import com.carlink.ui.settings.DisplayModeDialog
import com.carlink.ui.settings.LogsTabContent
import com.carlink.ui.settings.AudioSourceConfig
import com.carlink.ui.settings.CallQualityConfig
import com.carlink.ui.settings.DebugModePreference
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModePreference
import com.carlink.ui.settings.MicSourceConfig
import com.carlink.ui.settings.SettingsTab
import com.carlink.ui.settings.VideoResolutionConfig
import com.carlink.ui.settings.WiFiBandConfig
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings screen with NavigationRail for device control, display settings, and log management. */
@Composable
fun SettingsScreen(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    onNavigateBack: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.CONTROL) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // Debug mode preference - controls visibility of developer tabs
    val debugModePreference = remember { DebugModePreference.getInstance(context) }
    val debugModeEnabled by debugModePreference.debugModeEnabledFlow.collectAsStateWithLifecycle(
        initialValue = debugModePreference.isDebugModeEnabledSync(),
    )

    // Get visible tabs based on debug mode
    val visibleTabs = remember(debugModeEnabled) {
        SettingsTab.getVisibleTabs(debugModeEnabled)
    }

    // Auto-switch to CONTROL tab if current tab becomes hidden (debug mode disabled)
    LaunchedEffect(debugModeEnabled, selectedTab) {
        if (selectedTab.requiresDebugMode && !debugModeEnabled) {
            logInfo("[UI_STATE] Debug mode disabled - switching from $selectedTab to CONTROL", tag = "UI")
            selectedTab = SettingsTab.CONTROL
        }
    }

    // Log settings screen entry and tab changes
    LaunchedEffect(Unit) {
        logInfo("[UI_STATE] SettingsScreen opened - user is in app settings (NOT viewing CarPlay projection)", tag = "UI")
    }

    LaunchedEffect(selectedTab) {
        logInfo("[UI_STATE] Settings tab changed: $selectedTab", tag = "UI")
    }

    // Get app version
    val appVersion =
        remember {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${packageInfo.versionName}+${packageInfo.longVersionCode}"
            } catch (e: PackageManager.NameNotFoundException) {
                logWarn("[SettingsScreen] Failed to get package info: ${e.message}")
                "Unknown"
            }
        }

    val view = LocalView.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.surface,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            // Left sidebar with NavigationRail
            Column(
                modifier = Modifier.fillMaxHeight(),
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onNavigateBack()
                        },
                        modifier = Modifier.size(AutomotiveDimens.ButtonMinHeight),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(AutomotiveDimens.IconSize),
                        )
                    }
                }

                // NavigationRail with tabs - Expanded to fill available space
                // Items centered vertically to match Flutter NavigationRail behavior
                NavigationRail(
                    modifier = Modifier.weight(1f),
                    containerColor = colorScheme.surface,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    visibleTabs.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                selectedTab = tab
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                )
                            },
                            label = { Text(tab.title) },
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                // App version at bottom - always visible after NavigationRail
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Version: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (appVersion.isEmpty()) "- - -" else appVersion,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        color = if (appVersion.isEmpty()) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                    )
                }
            }

            // Tab content - fills remaining space
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(1f),
            ) {
                when (selectedTab) {
                    SettingsTab.CONTROL -> ControlTabContent(carlinkManager, debugModePreference)
                    SettingsTab.LOGS -> LogsTabContent(context, fileLogManager)
                }
            }
        }
    }
}

private enum class ButtonSeverity {
    NORMAL, // Primary action (blue/primary)
    WARNING, // Warning action (tertiary/amber)
    DESTRUCTIVE, // Destructive action (error/red)
}

@Composable
private fun ControlTabContent(
    carlinkManager: CarlinkManager,
    debugModePreference: DebugModePreference,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    val isDeviceConnected = carlinkManager.state != CarlinkManager.State.DISCONNECTED

    val displayModePreference = remember { DisplayModePreference.getInstance(context) }
    val currentDisplayMode by displayModePreference.displayModeFlow.collectAsStateWithLifecycle(
        initialValue = DisplayMode.SYSTEM_UI_VISIBLE,
    )
    var showDisplayModeDialog by remember { mutableStateOf(false) }

    val adapterConfigPreference = remember { AdapterConfigPreference.getInstance(context) }
    var showAdapterConfigDialog by remember { mutableStateOf(false) }

    // Debug mode state
    val debugModeEnabled by debugModePreference.debugModeEnabledFlow.collectAsStateWithLifecycle(
        initialValue = debugModePreference.isDebugModeEnabledSync(),
    )

    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val maxContentWidth = (containerWidthDp * 0.75f).coerceIn(400.dp, 1200.dp)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = "Device Control",
                    icon = Icons.Default.Devices,
                ) {
                    ControlButton(
                        label = "Disconnect Phone",
                        icon = Icons.Default.PhoneDisabled,
                        severity = ButtonSeverity.WARNING,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            logWarn("[UI_ACTION] Disconnect Phone button clicked", tag = "UI")
                            carlinkManager.stop()
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = "Close Adapter",
                        icon = Icons.Default.PowerOff,
                        severity = ButtonSeverity.DESTRUCTIVE,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            logWarn("[UI_ACTION] Close Adapter button clicked", tag = "UI")
                            carlinkManager.stop()
                        },
                    )
                }

                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = "System Reset",
                    icon = Icons.Default.RestartAlt,
                ) {
                    ControlButton(
                        label = "Reset Video Decoder",
                        icon = Icons.Default.VideoSettings,
                        severity = ButtonSeverity.NORMAL,
                        enabled = !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            logWarn("[UI_ACTION] Reset Video Decoder button clicked", tag = "UI")
                            carlinkManager.resetVideoDecoder()
                        },
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ControlButton(
                        label = "Reset USB Device",
                        icon = Icons.Default.Usb,
                        severity = ButtonSeverity.DESTRUCTIVE,
                        enabled = isDeviceConnected && !isProcessing,
                        isProcessing = isProcessing,
                        onClick = {
                            logWarn("[UI_ACTION] Reset USB Device button clicked", tag = "UI")
                            isProcessing = true
                            scope.launch {
                                try {
                                    carlinkManager.restart()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = "Display Mode",
                    icon = Icons.Default.DisplaySettings,
                ) {
                    // Configure button showing current mode
                    FilledTonalButton(
                        onClick = { showDisplayModeDialog = true },
                        modifier = Modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector =
                                when (currentDisplayMode) {
                                    DisplayMode.SYSTEM_UI_VISIBLE -> Icons.Default.FullscreenExit
                                    DisplayMode.STATUS_BAR_HIDDEN -> Icons.Default.Layers
                                    DisplayMode.FULLSCREEN_IMMERSIVE -> Icons.Default.Fullscreen
                                },
                            contentDescription = "Configure display mode",
                            modifier = Modifier.size(AutomotiveDimens.IconSize),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text =
                                when (currentDisplayMode) {
                                    DisplayMode.SYSTEM_UI_VISIBLE -> "System UI"
                                    DisplayMode.STATUS_BAR_HIDDEN -> "Hide Status"
                                    DisplayMode.FULLSCREEN_IMMERSIVE -> "Fullscreen"
                                },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }

                // Adapter Configuration Card
                ControlCard(
                    modifier = Modifier.weight(1f),
                    title = "Adapter Configuration",
                    icon = Icons.Default.SettingsInputComponent,
                ) {
                    // Configure button
                    FilledTonalButton(
                        onClick = { showAdapterConfigDialog = true },
                        modifier = Modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configure adapter",
                            modifier = Modifier.size(AutomotiveDimens.IconSize),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Configure",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Developer Options Card - full width
            DeveloperOptionsCard(
                debugModeEnabled = debugModeEnabled,
                onDebugModeChanged = { enabled ->
                    scope.launch {
                        debugModePreference.setDebugModeEnabled(enabled)
                        logInfo("[UI_ACTION] Debug mode ${if (enabled) "enabled" else "disabled"}", tag = "UI")
                    }
                },
            )
        }
    }

    // Adapter Configuration Dialog
    if (showAdapterConfigDialog) {
        AdapterConfigurationDialog(
            adapterConfigPreference = adapterConfigPreference,
            carlinkManager = carlinkManager,
            currentDisplayMode = currentDisplayMode,
            onDismiss = { showAdapterConfigDialog = false },
        )
    }

    // Display Mode Dialog with live preview
    if (showDisplayModeDialog) {
        DisplayModeDialog(
            displayModePreference = displayModePreference,
            onDismiss = { showDisplayModeDialog = false },
            onApplyAndRestart = { newMode ->
                showDisplayModeDialog = false
                scope.launch {
                    // Save the new display mode
                    displayModePreference.setDisplayMode(newMode)
                    // Stop adapter and exit
                    carlinkManager.stop()
                    kotlinx.coroutines.delay(500)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            },
        )
    }
}

/**
 * Material 3 Control Card container
 */
@Composable
private fun ControlCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
        ) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            content()
        }
    }
}

/**
 * Developer Options Card with debug mode toggle.
 * When enabled, shows the Logs tab in the NavigationRail.
 */
@Composable
private fun DeveloperOptionsCard(
    debugModeEnabled: Boolean,
    onDebugModeChanged: (Boolean) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Title and description
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Developer Options",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enable the Logs tab",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Switch
            Switch(
                checked = debugModeEnabled,
                onCheckedChange = onDebugModeChanged,
            )
        }
    }
}

/**
 * Material 3 Control Button with animated state transitions
 */
@Composable
private fun ControlButton(
    label: String,
    icon: ImageVector,
    severity: ButtonSeverity,
    enabled: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    when (severity) {
        ButtonSeverity.DESTRUCTIVE -> {
            Button(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(AutomotiveDimens.ButtonMinHeight),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AnimatedContent(
                    targetState = isProcessing,
                    transitionSpec = {
                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                    },
                    label = "iconTransition",
                ) { processing ->
                    if (processing) {
                        LoadingSpinner(
                            size = 24.dp,
                            color = colorScheme.onError,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        ButtonSeverity.WARNING -> {
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(AutomotiveDimens.ButtonMinHeight),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = colorScheme.tertiaryContainer,
                        contentColor = colorScheme.onTertiaryContainer,
                    ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AnimatedContent(
                    targetState = isProcessing,
                    transitionSpec = {
                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                    },
                    label = "iconTransition",
                ) { processing ->
                    if (processing) {
                        LoadingSpinner(
                            size = 24.dp,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        ButtonSeverity.NORMAL -> {
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled && !isProcessing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(AutomotiveDimens.ButtonMinHeight),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                AnimatedContent(
                    targetState = isProcessing,
                    transitionSpec = {
                        (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut())
                    },
                    label = "iconTransition",
                ) { processing ->
                    if (processing) {
                        LoadingSpinner(
                            size = 24.dp,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
