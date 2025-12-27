package com.carlink.ui

import android.content.pm.PackageManager
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.CarlinkManager
import com.carlink.logging.FileExportService
import com.carlink.logging.FileLogManager
import com.carlink.logging.LogPreset
import com.carlink.logging.LoggingPreferences
import com.carlink.logging.apply
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.components.LoadingSpinner
import com.carlink.ui.settings.AdapterConfigPreference
import com.carlink.ui.settings.AudioSourceConfig
import com.carlink.ui.settings.CallQualityConfig
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DisplayModePreference
import com.carlink.ui.settings.MicSourceConfig
import com.carlink.ui.settings.SampleRateConfig
import com.carlink.ui.settings.SettingsTab
import com.carlink.ui.settings.WiFiBandConfig
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings Screen - NavigationRail Settings Interface
 *
 * Uses Material 3 NavigationRail for tablet/automotive landscape layout.
 * Matches the Flutter implementation with vertical sidebar navigation.
 *
 * Provides access to:
 * - Status: Real-time adapter status from protocol messages
 * - Control: Device control commands (reset, disconnect, etc.) and Display Control
 * - Logs: Log file management, log level selection, and export
 *
 * Ported from: example/lib/settings_page.dart
 */
@Composable
fun SettingsScreen(
    carlinkManager: CarlinkManager,
    fileLogManager: FileLogManager?,
    onNavigateBack: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(SettingsTab.CONTROL) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

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

    // Get view for haptic feedback - Matches Flutter HapticFeedback.lightImpact()
    val view = LocalView.current

    // Matches Flutter SafeArea - Apply system bar insets
    // Surface provides opaque background when used as overlay
    // Use colorScheme.surface to match NavigationRail containerColor
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
                // Back button at top - Matches Flutter _buildBackButton with haptic feedback
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
                    SettingsTab.visibleTabs.forEach { tab ->
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
                    SettingsTab.CONTROL -> ControlTabContent(carlinkManager)
                    SettingsTab.LOGS -> LogsTabContent(context, fileLogManager)
                }
            }
        }
    }
}

// ==================== CONTROL TAB ====================
// Matches Flutter: control_tab_content.dart

/**
 * Button severity levels for semantic color mapping (matches Flutter)
 */
private enum class ButtonSeverity {
    NORMAL, // Primary action (blue/primary)
    WARNING, // Warning action (tertiary/amber)
    DESTRUCTIVE, // Destructive action (error/red)
}

/**
 * Control Tab - Device control commands and Display Control
 * Matches Flutter control_tab_content.dart
 * NOTE: Media Controls are NOT in Flutter Settings - they belong on main screen
 */
@Composable
private fun ControlTabContent(carlinkManager: CarlinkManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    // Check device connection state - Matches Flutter _isDeviceConnected()
    val isDeviceConnected = carlinkManager.state != CarlinkManager.State.DISCONNECTED

    // Display mode preference
    val displayModePreference = remember { DisplayModePreference.getInstance(context) }
    val currentDisplayMode by displayModePreference.displayModeFlow.collectAsStateWithLifecycle(
        initialValue = DisplayMode.SYSTEM_UI_VISIBLE,
    )
    var showDisplayModeDialog by remember { mutableStateOf(false) }

    // Adapter configuration preference
    val adapterConfigPreference = remember { AdapterConfigPreference.getInstance(context) }
    var showAdapterConfigDialog by remember { mutableStateOf(false) }

    // Responsive max width - 75% of container width, clamped between 400dp and 1200dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val maxContentWidth = (containerWidthDp * 0.75f).coerceIn(400.dp, 1200.dp)

    // Centered scrollable content with responsive max width constraint
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
            // Two cards side by side (Device Control + System Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Device Control Card - Matches Flutter _buildDeviceControlCard
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
                            carlinkManager.stop()
                        },
                    )
                }

                // System Reset Card - Matches Flutter _buildSystemResetCard
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

            // Two cards side by side (Display Control + Adapter Configuration)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Display Control Card - Display mode configuration
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
        }
    }

    // Adapter Configuration Dialog
    if (showAdapterConfigDialog) {
        AdapterConfigurationDialog(
            adapterConfigPreference = adapterConfigPreference,
            carlinkManager = carlinkManager,
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

// ==================== ADAPTER CONFIGURATION DIALOG ====================

/**
 * Adapter Configuration Dialog
 *
 * Scrollable popup dialog for configuring adapter initialization settings.
 * Designed to be extensible - new configuration options can be easily added.
 *
 * Structure:
 * - Header with icon and title
 * - Scrollable content with configuration options
 * - Footer with Save/Default/Cancel buttons
 */
@Composable
private fun AdapterConfigurationDialog(
    adapterConfigPreference: AdapterConfigPreference,
    carlinkManager: CarlinkManager?,
    onDismiss: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // Load saved values from preferences
    val savedAudioSource by adapterConfigPreference.audioSourceFlow.collectAsStateWithLifecycle(
        initialValue = AudioSourceConfig.DEFAULT,
    )
    val savedSampleRate by adapterConfigPreference.sampleRateFlow.collectAsStateWithLifecycle(
        initialValue = SampleRateConfig.DEFAULT,
    )
    val savedMicSource by adapterConfigPreference.micSourceFlow.collectAsStateWithLifecycle(
        initialValue = MicSourceConfig.DEFAULT,
    )
    val savedWifiBand by adapterConfigPreference.wifiBandFlow.collectAsStateWithLifecycle(
        initialValue = WiFiBandConfig.DEFAULT,
    )
    val savedCallQuality by adapterConfigPreference.callQualityFlow.collectAsStateWithLifecycle(
        initialValue = CallQualityConfig.DEFAULT,
    )

    // Local state for editing - allows cancel without saving
    var selectedAudioSource by remember { mutableStateOf(savedAudioSource) }
    var selectedSampleRate by remember { mutableStateOf(savedSampleRate) }
    var selectedMicSource by remember { mutableStateOf(savedMicSource) }
    var selectedWifiBand by remember { mutableStateOf(savedWifiBand) }
    var selectedCallQuality by remember { mutableStateOf(savedCallQuality) }

    // Sync local state when saved value loads (for initial load)
    LaunchedEffect(savedAudioSource) { selectedAudioSource = savedAudioSource }
    LaunchedEffect(savedSampleRate) { selectedSampleRate = savedSampleRate }
    LaunchedEffect(savedMicSource) { selectedMicSource = savedMicSource }
    LaunchedEffect(savedWifiBand) { selectedWifiBand = savedWifiBand }
    LaunchedEffect(savedCallQuality) { selectedCallQuality = savedCallQuality }

    // Track if any changes were made
    // All adapter configuration changes require app restart
    val hasChanges =
        selectedAudioSource != savedAudioSource ||
            selectedSampleRate != savedSampleRate ||
            selectedMicSource != savedMicSource ||
            selectedWifiBand != savedWifiBand ||
            selectedCallQuality != savedCallQuality

    // Responsive dialog width - 60% of container width, clamped between 320dp and 600dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val dialogMaxWidth = (containerWidthDp * 0.6f).coerceIn(320.dp, 600.dp)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = dialogMaxWidth),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsInputComponent,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Adapter Configuration",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle - all changes require restart
                Text(
                    text = "Changes require app restart to apply",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Scrollable content area for configuration options
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Audio Source Configuration Option
                    ConfigurationOptionCard(
                        title = "Audio Source",
                        description = "Select how audio is routed from your phone",
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                    ) {
                        // Audio source selection buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Bluetooth button
                            AudioSourceButton(
                                label = "Bluetooth",
                                icon = Icons.Default.Bluetooth,
                                isSelected = selectedAudioSource == AudioSourceConfig.BLUETOOTH,
                                onClick = { selectedAudioSource = AudioSourceConfig.BLUETOOTH },
                                modifier = Modifier.weight(1f),
                            )

                            // Adapter button
                            AudioSourceButton(
                                label = "Adapter",
                                icon = Icons.Default.Usb,
                                isSelected = selectedAudioSource == AudioSourceConfig.ADAPTER,
                                onClick = { selectedAudioSource = AudioSourceConfig.ADAPTER },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Current selection indicator
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                when (selectedAudioSource) {
                                    AudioSourceConfig.BLUETOOTH -> "Audio via phone Bluetooth to car stereo"
                                    AudioSourceConfig.ADAPTER -> "Audio via USB through this app"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }

                    // Sample Rate Configuration Option
                    ConfigurationOptionCard(
                        title = "Sample Rate",
                        description = "Audio sample rate for media playback",
                        icon = Icons.Default.GraphicEq,
                    ) {
                        // Sample rate selection buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 44.1kHz button
                            AudioSourceButton(
                                label = "44.1 kHz",
                                icon = Icons.Default.MusicNote,
                                isSelected = selectedSampleRate == SampleRateConfig.RATE_44100,
                                onClick = { selectedSampleRate = SampleRateConfig.RATE_44100 },
                                modifier = Modifier.weight(1f),
                            )

                            // 48kHz button
                            AudioSourceButton(
                                label = "48 kHz",
                                icon = Icons.Default.HighQuality,
                                isSelected = selectedSampleRate == SampleRateConfig.RATE_48000,
                                onClick = { selectedSampleRate = SampleRateConfig.RATE_48000 },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Current selection indicator
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                when (selectedSampleRate) {
                                    SampleRateConfig.RATE_44100 -> "CD quality (44.1kHz)"
                                    SampleRateConfig.RATE_48000 -> "Professional quality (48kHz)"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }

                    // Microphone Source Configuration
                    ConfigurationOptionCard(
                        title = "Microphone Source",
                        description = "Select which microphone to use for voice input",
                        icon = Icons.Default.Mic,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AudioSourceButton(
                                label = "App",
                                icon = Icons.Default.SettingsInputComponent,
                                isSelected = selectedMicSource == MicSourceConfig.APP,
                                onClick = { selectedMicSource = MicSourceConfig.APP },
                                modifier = Modifier.weight(1f),
                            )
                            AudioSourceButton(
                                label = "Phone",
                                icon = Icons.Default.PhoneAndroid,
                                isSelected = selectedMicSource == MicSourceConfig.PHONE,
                                onClick = { selectedMicSource = MicSourceConfig.PHONE },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                when (selectedMicSource) {
                                    MicSourceConfig.APP -> "This device captures mic input from Android/OS"
                                    MicSourceConfig.PHONE -> "Phone uses its own mic (or adapter mic if present)"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }

                    // WiFi Band Configuration
                    ConfigurationOptionCard(
                        title = "WiFi Band",
                        description = "Wireless band for CarPlay connection",
                        icon = Icons.Default.Wifi,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AudioSourceButton(
                                label = "5 GHz",
                                icon = Icons.Default.Speed,
                                isSelected = selectedWifiBand == WiFiBandConfig.BAND_5GHZ,
                                onClick = { selectedWifiBand = WiFiBandConfig.BAND_5GHZ },
                                modifier = Modifier.weight(1f),
                            )
                            AudioSourceButton(
                                label = "2.4 GHz",
                                icon = Icons.Default.SignalCellularAlt,
                                isSelected = selectedWifiBand == WiFiBandConfig.BAND_24GHZ,
                                onClick = { selectedWifiBand = WiFiBandConfig.BAND_24GHZ },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                when (selectedWifiBand) {
                                    WiFiBandConfig.BAND_5GHZ -> "Better speed, less interference (recommended)"
                                    WiFiBandConfig.BAND_24GHZ -> "Better range, more interference"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }

                    // Call Quality Configuration
                    ConfigurationOptionCard(
                        title = "Call Quality",
                        description = "Audio quality for phone calls",
                        icon = Icons.Default.Call,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AudioSourceButton(
                                label = "Normal",
                                icon = Icons.Default.Phone,
                                isSelected = selectedCallQuality == CallQualityConfig.NORMAL,
                                onClick = { selectedCallQuality = CallQualityConfig.NORMAL },
                                modifier = Modifier.weight(1f),
                            )
                            AudioSourceButton(
                                label = "Clear",
                                icon = Icons.Default.PhoneInTalk,
                                isSelected = selectedCallQuality == CallQualityConfig.CLEAR,
                                onClick = { selectedCallQuality = CallQualityConfig.CLEAR },
                                modifier = Modifier.weight(1f),
                            )
                            AudioSourceButton(
                                label = "HD",
                                icon = Icons.Default.Hd,
                                isSelected = selectedCallQuality == CallQualityConfig.HD,
                                onClick = { selectedCallQuality = CallQualityConfig.HD },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                when (selectedCallQuality) {
                                    CallQualityConfig.NORMAL -> "Standard call quality"
                                    CallQualityConfig.CLEAR -> "Enhanced clarity"
                                    CallQualityConfig.HD -> "Highest quality audio"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer with action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }

                    // Default button
                    TextButton(
                        onClick = {
                            scope.launch {
                                adapterConfigPreference.resetToDefaults()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Default")
                    }

                    // Apply & Restart button - all adapter config changes require restart
                    Button(
                        onClick = {
                            scope.launch {
                                // Save all configuration
                                adapterConfigPreference.setAudioSource(selectedAudioSource)
                                adapterConfigPreference.setSampleRate(selectedSampleRate)
                                adapterConfigPreference.setMicSource(selectedMicSource)
                                adapterConfigPreference.setWifiBand(selectedWifiBand)
                                adapterConfigPreference.setCallQuality(selectedCallQuality)

                                // All adapter config changes require restart
                                carlinkManager?.stop()
                                kotlinx.coroutines.delay(500)
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        },
                        modifier = Modifier.weight(1.5f),
                        enabled = hasChanges,
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply & Restart")
                    }
                }
            }
        }
    }
}

/**
 * Configuration Option Card - Container for a single configuration option
 *
 * Provides consistent styling for configuration options in the dialog.
 * Designed to be reusable for future configuration options.
 */
@Composable
private fun ConfigurationOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row with icon and title
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Option content
            content()
        }
    }
}

/**
 * Audio Source Selection Button
 *
 * Toggle button with animated visual indicator for selected state.
 */
@Composable
private fun AudioSourceButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Animated properties for smooth selection transitions
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primaryContainer else colorScheme.surfaceContainer,
        label = "backgroundColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        label = "borderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.outline,
        label = "borderColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
        label = "contentColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        label = "iconScale",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(AutomotiveDimens.ButtonMinHeight),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border =
            BorderStroke(
                width = borderWidth,
                color = borderColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier =
                    Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                color = contentColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

// ==================== DISPLAY MODE DIALOG ====================

/**
 * Display Mode Dialog with Live Preview
 *
 * Allows users to select between three display modes with instant visual preview.
 * Changes are previewed immediately by toggling system bars, but require restart
 * to properly reconfigure video surface dimensions.
 *
 * Modes:
 * - SYSTEM_UI_VISIBLE: Both status bar and navigation bar always visible
 * - STATUS_BAR_HIDDEN: Only status bar hidden, navigation bar remains visible
 * - FULLSCREEN_IMMERSIVE: Both bars hidden, swipe to reveal temporarily
 */
@Composable
private fun DisplayModeDialog(
    displayModePreference: DisplayModePreference,
    onDismiss: () -> Unit,
    onApplyAndRestart: (DisplayMode) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Get window for live preview
    val window = (context as? ComponentActivity)?.window

    // Load saved mode from preferences
    val savedMode by displayModePreference.displayModeFlow.collectAsStateWithLifecycle(
        initialValue = DisplayMode.SYSTEM_UI_VISIBLE,
    )

    // Local state for preview - allows cancel without saving
    var selectedMode by remember { mutableStateOf(savedMode) }

    // Sync local state when saved value loads (for initial load)
    LaunchedEffect(savedMode) {
        selectedMode = savedMode
    }

    // LIVE PREVIEW: Apply mode instantly when selection changes
    LaunchedEffect(selectedMode) {
        window?.let { applyDisplayModePreview(it, selectedMode) }
    }

    // Restore original mode when dialog dismissed without saving
    DisposableEffect(Unit) {
        onDispose {
            window?.let { applyDisplayModePreview(it, savedMode) }
        }
    }

    val hasChanges = selectedMode != savedMode

    // Responsive dialog width - 60% of container width, clamped between 320dp and 600dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val dialogMaxWidth = (containerWidthDp * 0.6f).coerceIn(320.dp, 600.dp)

    Dialog(onDismissRequest = {
        // Restore original mode on cancel
        window?.let { applyDisplayModePreview(it, savedMode) }
        onDismiss()
    }) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = dialogMaxWidth),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header with icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Display Mode",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle with preview indicator
                Text(
                    text = "Preview changes instantly • Restart required to apply",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Scrollable content area
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Configuration option card
                    ConfigurationOptionCard(
                        title = "App Immersion",
                        description = "Control system UI visibility during projection",
                        icon = Icons.Default.Layers,
                    ) {
                        // Three mode buttons in a row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DisplayModeButton(
                                label = "System UI",
                                icon = Icons.Default.FullscreenExit,
                                isSelected = selectedMode == DisplayMode.SYSTEM_UI_VISIBLE,
                                onClick = { selectedMode = DisplayMode.SYSTEM_UI_VISIBLE },
                                modifier = Modifier.weight(1f),
                            )
                            DisplayModeButton(
                                label = "Hide Status",
                                icon = Icons.Default.Layers,
                                isSelected = selectedMode == DisplayMode.STATUS_BAR_HIDDEN,
                                onClick = { selectedMode = DisplayMode.STATUS_BAR_HIDDEN },
                                modifier = Modifier.weight(1f),
                            )
                            DisplayModeButton(
                                label = "Fullscreen",
                                icon = Icons.Default.Fullscreen,
                                isSelected = selectedMode == DisplayMode.FULLSCREEN_IMMERSIVE,
                                onClick = { selectedMode = DisplayMode.FULLSCREEN_IMMERSIVE },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Description for selected mode
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text =
                                when (selectedMode) {
                                    DisplayMode.SYSTEM_UI_VISIBLE -> {
                                        "Status bar and navigation always visible. AAOS manages display bounds."
                                    }

                                    DisplayMode.STATUS_BAR_HIDDEN -> {
                                        "Status bar hidden, navigation bar visible. Extra vertical space."
                                    }

                                    DisplayMode.FULLSCREEN_IMMERSIVE -> {
                                        "All system UI hidden. Swipe edge to temporarily reveal."
                                    }
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer with action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Cancel - restores original mode
                    TextButton(
                        onClick = {
                            window?.let { applyDisplayModePreview(it, savedMode) }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }

                    // Apply & Restart
                    Button(
                        onClick = { onApplyAndRestart(selectedMode) },
                        modifier = Modifier.weight(1.5f),
                        enabled = hasChanges,
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Apply & Restart")
                    }
                }
            }
        }
    }
}

/**
 * Applies the display mode preview by showing/hiding system bars.
 * Used for instant visual feedback in the DisplayModeDialog.
 */
private fun applyDisplayModePreview(
    window: android.view.Window,
    mode: DisplayMode,
) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)

    when (mode) {
        DisplayMode.SYSTEM_UI_VISIBLE -> {
            // Show all system bars - let AAOS manage display bounds
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        DisplayMode.STATUS_BAR_HIDDEN -> {
            // Hide status bar only, keep navigation bar visible
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.show(WindowInsetsCompat.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        DisplayMode.FULLSCREEN_IMMERSIVE -> {
            // Hide all system bars for maximum projection area
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/**
 * Display Mode Selection Button
 *
 * Toggle button with animated visual indicator for selected state.
 * Styled to match AudioSourceButton for consistency.
 */
@Composable
private fun DisplayModeButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Animated properties for smooth selection transitions
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primaryContainer else colorScheme.surfaceContainer,
        label = "backgroundColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        label = "borderWidth",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.outline,
        label = "borderColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
        label = "contentColor",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        label = "iconScale",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.height(AutomotiveDimens.ButtonMinHeight),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        border =
            BorderStroke(
                width = borderWidth,
                color = borderColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier =
                    Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                color = contentColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ==================== LOGS TAB ====================
// Matches Flutter: logs_tab_content.dart

/**
 * Logs Tab - Log file management with log level selector
 * Matches Flutter logs_tab_content.dart
 */
@Composable
private fun LogsTabContent(
    context: android.content.Context,
    fileLogManager: FileLogManager?,
) {
    val scope = rememberCoroutineScope()
    var logFiles by remember { mutableStateOf(emptyList<File>()) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showDebugWarningDialog by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    // File export state - stores the file to export when SAF picker returns
    // Using both pendingExportFile and isExporting prevents race conditions on rapid clicks
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // SAF Document Creator launcher - matches Flutter ACTION_CREATE_DOCUMENT
    // Lifecycle-aware registration handled by Compose; I/O delegated to FileExportService
    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            // Capture file reference immediately to avoid race conditions
            val fileToExport = pendingExportFile
            if (uri != null && fileToExport != null) {
                // Delegate I/O to FileExportService (runs on Dispatchers.IO)
                scope.launch {
                    val result = FileExportService.writeFileToUri(context, uri, fileToExport)
                    result
                        .onSuccess { bytesWritten ->
                            Toast.makeText(context, "Exported: ${fileToExport.name}", Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            logError("[FILE_EXPORT] Export failed: ${error.message}", tag = "FILE_LOG")
                            Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    // Clear state after completion
                    pendingExportFile = null
                    isExporting = false
                }
            } else {
                logInfo("[FILE_EXPORT] User cancelled document picker", tag = "FILE_LOG")
                pendingExportFile = null
                isExporting = false
            }
        }

    // Logging preferences
    val loggingPreferences = remember { LoggingPreferences.getInstance(context) }
    val isLoggingEnabled by loggingPreferences.loggingEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val currentLogLevel by loggingPreferences.logLevelFlow.collectAsStateWithLifecycle(initialValue = LogPreset.NORMAL)

    // Check if debug build
    val isDebugBuild =
        remember {
            try {
                val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            } catch (e: PackageManager.NameNotFoundException) {
                logWarn("[SettingsScreen] Failed to check debug build status: ${e.message}")
                false
            }
        }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    LaunchedEffect(fileLogManager) {
        logFiles = fileLogManager?.getLogFiles() ?: emptyList()
    }

    if (fileLogManager == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "File logging not available",
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Responsive max width - 75% of container width, clamped between 400dp and 1200dp
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val maxContentWidth = (containerWidthDp * 0.75f).coerceIn(400.dp, 1200.dp)

    // Centered scrollable content with responsive max width constraint
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
            // File Logging Card - Matches Flutter _buildFileLoggingCard
            LoggingControlCard(
                title = "File Logging",
                icon = Icons.AutoMirrored.Filled.Article,
            ) {
                // Toggle row - Matches Flutter SwitchListTile
                // Using Row instead of ListItem to avoid dark background
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Save logs to file",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Store app logs in private storage for debugging",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = isLoggingEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                // Check for debug build warning
                                if (enabled && isDebugBuild) {
                                    showDebugWarningDialog = true
                                } else {
                                    loggingPreferences.setLoggingEnabled(enabled)
                                    if (enabled) {
                                        fileLogManager.enable()
                                    } else {
                                        fileLogManager.disable()
                                    }
                                    logFiles = fileLogManager.getLogFiles()
                                }
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Log Level Selector - Matches Flutter _buildLogLevelSelector exactly
                // Uses a tappable container box, not a button
                Column {
                    Text(
                        text = "Log Level",
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = { showLogLevelDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, colorScheme.outline),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentLogLevel.displayName,
                                    style =
                                        MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    color = currentLogLevel.color,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = currentLogLevel.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // File Logging Status - Embedded inside File Logging card
                // Matches Flutter _buildFileLoggingStatusRows (inside same card)
                val totalSize = fileLogManager.getTotalLogSize()
                val currentFileSize = fileLogManager.getCurrentLogFileSize()

                if (isLoggingEnabled || logFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "File Logging Status",
                                style =
                                    MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FileLoggingStatusRow(
                                label = "Status",
                                value = if (isLoggingEnabled) "Active" else "Disabled",
                            )
                            FileLoggingStatusRow(
                                label = "Current file size",
                                value = formatBytes(currentFileSize),
                            )
                            FileLoggingStatusRow(
                                label = "Total files",
                                value = "${logFiles.size}",
                            )
                            FileLoggingStatusRow(
                                label = "Total size",
                                value = "${String.format(Locale.US, "%.2f", totalSize / (1024.0 * 1024.0))} MB",
                            )
                        }
                    }
                }
            }

            // Log Files Card - Matches Flutter _buildLogFilesCardWithActions
            LoggingControlCard(
                title = "Log Files",
                icon = Icons.Default.Folder,
            ) {
                // File count text - Matches Flutter
                Text(
                    text = "${logFiles.size} log file${if (logFiles.size != 1) "s" else ""} available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Log file items with export button - Matches Flutter _buildLogFileItem
                logFiles.forEach { file ->
                    LogFileItem(
                        file = file,
                        dateFormat = dateFormat,
                        isExportEnabled = !isExporting,
                        onDelete = { showDeleteDialog = file },
                        onExport = {
                            // Prevent double-clicks by checking isExporting state
                            if (!isExporting) {
                                // Launch SAF document picker with suggested filename
                                // Matches Flutter: FileExportService.createDocument()
                                logInfo("[FILE_EXPORT] Starting export for: ${file.name}", tag = "FILE_LOG")
                                isExporting = true
                                pendingExportFile = file
                                createDocumentLauncher.launch(file.name)
                            }
                        },
                    )
                }

                // Help text - Matches Flutter
                if (logFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Use the save icon to export a log file to your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    // Log Level Selector Dialog - Matches Flutter _LogLevelSelectorDialog
    if (showLogLevelDialog) {
        LogLevelSelectorDialog(
            currentLevel = currentLogLevel,
            onDismiss = { showLogLevelDialog = false },
            onSelectLevel = { level ->
                scope.launch {
                    loggingPreferences.setLogLevel(level)
                    level.apply()
                    showLogLevelDialog = false
                }
            },
        )
    }

    // Debug APK Warning Dialog - Matches Flutter _DebugApkWarningDialog
    if (showDebugWarningDialog) {
        AlertDialog(
            onDismissRequest = { showDebugWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = colorScheme.error,
                )
            },
            title = {
                Text(
                    "Debug Build Detected",
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Text(
                    "File logging is disabled in debug builds to prevent excessive log file growth. Use release builds for persistent logging.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = { showDebugWarningDialog = false }) {
                    Text("OK")
                }
            },
        )
    }

    // Delete confirmation dialog - Matches Flutter DeleteConfirmationDialog
    showDeleteDialog?.let { file ->
        val fileSize =
            try {
                file.length()
            } catch (e: SecurityException) {
                logWarn("[SettingsScreen] Cannot read file size for ${file.name}: ${e.message}")
                0L
            }
        val fileSizeStr = formatBytes(fileSize)

        Dialog(onDismissRequest = { showDeleteDialog = null }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Circular icon with error background - Matches Flutter ResponsiveDialog
                    Box(
                        modifier =
                            Modifier
                                .size(64.dp)
                                .background(
                                    color = colorScheme.error.copy(alpha = 0.15f),
                                    shape = CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Title
                    Text(
                        text = "Delete Log File?",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // File information box - Matches Flutter buildContentBox
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainer,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "File to delete:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "1 file",
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Total size:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = fileSizeStr,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // File name box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Warning box - Matches Flutter buildWarningBox
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.errorContainer.copy(alpha = 0.4f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Permanent Deletion",
                                    style =
                                        MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    color = colorScheme.onErrorContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "This file will be permanently deleted from your device. This action cannot be undone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onErrorContainer,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Action buttons - Matches Flutter layout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick = { showDeleteDialog = null },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                fileLogManager.deleteLogFile(file)
                                logFiles = fileLogManager.getLogFiles()
                                showDeleteDialog = null
                            },
                            modifier = Modifier.weight(2f),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colorScheme.error,
                                    contentColor = colorScheme.onError,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete File")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Log Level Selector Dialog - 2-column layout
 * Matches Flutter _LogLevelSelectorDialog exactly with:
 * - Icon in title row
 * - Two hardcoded columns with specific preset order
 * - Left: Silent, Minimal, Normal, Performance
 * - Right: RX Messages, Video Only, Audio Only, Debug
 */
@Composable
private fun LogLevelSelectorDialog(
    currentLevel: LogPreset,
    onDismiss: () -> Unit,
    onSelectLevel: (LogPreset) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    // Define preset order matching Flutter exactly
    val leftColumn =
        listOf(
            LogPreset.SILENT,
            LogPreset.MINIMAL,
            LogPreset.NORMAL,
            LogPreset.PERFORMANCE,
        )
    val rightColumn =
        listOf(
            LogPreset.RX_MESSAGES,
            LogPreset.VIDEO_ONLY,
            LogPreset.AUDIO_ONLY,
            LogPreset.DEBUG,
        )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(28.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                // Title row with icon - Matches Flutter exactly
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Select Log Level",
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Two columns with specific preset order
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Left column
                    Column(modifier = Modifier.weight(1f)) {
                        leftColumn.forEach { preset ->
                            LogPresetChip(
                                preset = preset,
                                isSelected = preset == currentLevel,
                                onClick = { onSelectLevel(preset) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    // Right column
                    Column(modifier = Modifier.weight(1f)) {
                        rightColumn.forEach { preset ->
                            LogPresetChip(
                                preset = preset,
                                isSelected = preset == currentLevel,
                                onClick = { onSelectLevel(preset) },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cancel button aligned right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Log Preset Chip for selector dialog
 * Matches Flutter _buildLogLevelOption exactly
 */
@Composable
private fun LogPresetChip(
    preset: LogPreset,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) preset.color.copy(alpha = 0.15f) else colorScheme.surfaceContainerHighest,
        border =
            androidx.compose.foundation.BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) preset.color else colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Circular selection indicator - matches Flutter
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .then(
                            if (isSelected) {
                                Modifier.background(preset.color, shape = androidx.compose.foundation.shape.CircleShape)
                            } else {
                                Modifier.background(Color.Transparent, shape = androidx.compose.foundation.shape.CircleShape)
                            },
                        ).border(
                            width = 2.dp,
                            color = preset.color,
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = colorScheme.surface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Level info
            Column(
                modifier = Modifier.weight(1f),
            ) {
                // Title is ALWAYS the preset color - matches Flutter line 904
                Text(
                    text = preset.displayName,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    color = preset.color,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Material 3 Logging Control Card container
 */
@Composable
private fun LoggingControlCard(
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

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}

/**
 * Material 3 Log file item with export button
 * Matches Flutter _buildLogFileItem
 *
 * @param file The log file to display
 * @param dateFormat Format for displaying last modified date
 * @param isExportEnabled Whether export button is enabled (false during active export)
 * @param onDelete Callback when delete button is clicked
 * @param onExport Callback when export button is clicked
 */
@Composable
private fun LogFileItem(
    file: File,
    dateFormat: SimpleDateFormat,
    isExportEnabled: Boolean = true,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File icon
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatFileSize(file.length())} • ${dateFormat.format(Date(file.lastModified()))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            // Export button - Matches Flutter save_alt icon
            // Disabled during active export to prevent race conditions
            IconButton(
                onClick = onExport,
                enabled = isExportEnabled,
            ) {
                Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = "Export",
                    tint = if (isExportEnabled) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp),
                )
            }

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * File Logging Status Row - for embedded status display
 * Matches Flutter _buildStatusRow exactly
 */
@Composable
private fun FileLoggingStatusRow(
    label: String,
    value: String,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

// ==================== UTILITY FUNCTIONS ====================

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return "${String.format(Locale.US, "%.1f", kb)} KB"
}
