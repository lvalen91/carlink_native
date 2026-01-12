package com.carlink.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.CarlinkManager
import com.carlink.logging.logInfo
import com.carlink.ui.theme.AutomotiveDimens
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Capture Playback Settings Screen
 *
 * Allows users to:
 * - Enable/disable playback mode (which also stops adapter search)
 * - Select capture files (.json + .bin) via SAF or from internal storage
 * - Start/stop playback
 *
 * When playback starts, navigates to MainScreen to display the video.
 */
@Composable
fun CapturePlaybackContent(
    carlinkManager: CarlinkManager,
    capturePlaybackManager: com.carlink.capture.CapturePlaybackManager,
    onStartPlayback: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    val preference = remember { CapturePlaybackPreference.getInstance(context) }

    val playbackEnabled by preference.playbackEnabledFlow.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val jsonUri by preference.captureJsonUriFlow.collectAsStateWithLifecycle(
        initialValue = null,
    )
    val binUri by preference.captureBinUriFlow.collectAsStateWithLifecycle(
        initialValue = null,
    )

    // Check if adapter is connected
    val isAdapterConnected = carlinkManager.state != CarlinkManager.State.DISCONNECTED

    // Reset playback to disabled on app start - playback must be explicitly enabled each session
    LaunchedEffect(Unit) {
        preference.setPlaybackEnabled(false)
    }

    // File picker for JSON
    var pendingJsonUri by remember { mutableStateOf<Uri?>(null) }

    val jsonFilePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                logInfo("[CAPTURE_PLAYBACK] JSON file selected: $uri", tag = "UI")

                // Take persistable permission
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    logInfo("[CAPTURE_PLAYBACK] Could not take persistable permission: ${e.message}", tag = "UI")
                }

                pendingJsonUri = uri

                // Try to find matching .bin file automatically
                val jsonUriString = uri.toString()
                if (jsonUriString.endsWith(".json")) {
                    val binUriString = jsonUriString.replace(".json", ".bin")
                    val binUri = Uri.parse(binUriString)

                    // Check if bin file exists and is accessible
                    try {
                        context.contentResolver.openInputStream(binUri)?.use {
                            // File exists, save both URIs
                            scope.launch {
                                preference.setCaptureFiles(uri, binUri)
                                Toast.makeText(
                                    context,
                                    "Capture files loaded",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            return@rememberLauncherForActivityResult
                        }
                    } catch (e: Exception) {
                        // Bin file not found at expected location
                        logInfo("[CAPTURE_PLAYBACK] Auto-detection failed: ${e.message}", tag = "UI")
                    }
                }

                // Save JSON only, user needs to select BIN manually
                scope.launch {
                    preference.setCaptureFiles(uri, null)
                }
                Toast.makeText(
                    context,
                    "JSON loaded. Please also select the .bin file.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    // File picker for BIN (fallback if auto-detection fails)
    val binFilePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                logInfo("[CAPTURE_PLAYBACK] BIN file selected: $uri", tag = "UI")

                // Take persistable permission
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: Exception) {
                    logInfo("[CAPTURE_PLAYBACK] Could not take persistable permission: ${e.message}", tag = "UI")
                }

                scope.launch {
                    preference.setCaptureFiles(jsonUri, uri)
                    Toast.makeText(context, "Capture files loaded", Toast.LENGTH_SHORT).show()
                }
            }
        }

    val hasValidFiles = jsonUri != null && binUri != null
    // Adapter state no longer blocks playback - toggle handles disconnection
    val canStartPlayback = playbackEnabled && hasValidFiles

    // Note: This composable is embedded in LogsTabContent's scrollable column,
    // so we don't add our own scroll or fillMaxSize wrapper here.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Warning card when adapter is connected
        if (isAdapterConnected) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = colorScheme.errorContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Adapter Connected",
                                style =
                                    MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                color = colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "Disconnect the adapter to use capture playback",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            // Playback Mode Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Playback Mode",
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enable Capture Playback",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "When enabled, stops adapter search and allows replay of captured sessions",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = playbackEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    preference.setPlaybackEnabled(enabled)
                                    if (enabled) {
                                        // Stop adapter when enabling playback mode
                                        logInfo("[CAPTURE_PLAYBACK] Stopping adapter for playback mode", tag = "UI")
                                        carlinkManager.stop()
                                    } else {
                                        // Resume adapter mode when disabling playback
                                        logInfo("[CAPTURE_PLAYBACK] Resuming adapter mode", tag = "UI")
                                        carlinkManager.resumeAdapterMode()
                                    }
                                }
                            },
                            // Always enabled - turning on playback will stop the adapter
                            enabled = true,
                        )
                    }
                }
            }

            // File Selection Card (SAF picker)
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "External Storage (SAF)",
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Select capture files from external storage (if accessible)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // JSON File
                    FileSelectionRow(
                        label = "Metadata (.json)",
                        uri = jsonUri,
                        onSelect = {
                            jsonFilePicker.launch(arrayOf("application/json", "*/*"))
                        },
                        enabled = playbackEnabled && !isAdapterConnected,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // BIN File
                    FileSelectionRow(
                        label = "Binary Data (.bin)",
                        uri = binUri,
                        onSelect = {
                            binFilePicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        enabled = playbackEnabled && !isAdapterConnected,
                    )

                    if (jsonUri != null && binUri == null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Please select the matching .bin file",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.error,
                        )
                    }

                    // Clear files button
                    if (jsonUri != null || binUri != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    preference.clearCaptureFiles()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = playbackEnabled && !isAdapterConnected,
                        ) {
                            Text("Clear Selection")
                        }
                    }
                }
            }

            // Playback Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Playback Controls",
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Start Playback Button
                    Button(
                        onClick = {
                            logInfo("[CAPTURE_PLAYBACK] Starting playback", tag = "UI")
                            scope.launch {
                                // Load capture files into the playback manager
                                val loaded = capturePlaybackManager.loadCapture(
                                    jsonUri = jsonUri!!,
                                    binUri = binUri!!,
                                )
                                if (loaded) {
                                    // Navigate to main screen - playback will start when surface is ready
                                    onStartPlayback()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Failed to load capture files",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(AutomotiveDimens.ButtonMinHeight),
                        enabled = canStartPlayback,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Playback",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    if (!canStartPlayback) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text =
                                when {
                                    !playbackEnabled -> "Enable playback mode above"
                                    !hasValidFiles -> "Select or load capture files above"
                                    else -> ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
    }
}

@Composable
private fun FileSelectionRow(
    label: String,
    uri: Uri?,
    onSelect: () -> Unit,
    enabled: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colorScheme.surfaceContainerHighest,
        border =
            BorderStroke(
                1.dp,
                if (uri != null) colorScheme.primary.copy(alpha = 0.5f) else colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = if (uri != null) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                )
                if (uri != null) {
                    Text(
                        text = getFileName(uri),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onSelect,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(if (uri != null) "Change" else "Select")
            }
        }
    }
}

private fun getFileName(uri: Uri): String {
    val path = uri.lastPathSegment ?: uri.toString()
    // Extract just the filename from the path
    return path.substringAfterLast("/").substringAfterLast(":")
}

/**
 * Format duration in milliseconds to human-readable string.
 */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
