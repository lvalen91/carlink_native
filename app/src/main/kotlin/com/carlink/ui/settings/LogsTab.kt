package com.carlink.ui.settings

import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carlink.logging.FileExportService
import com.carlink.logging.FileLogManager
import com.carlink.logging.LogPreset
import com.carlink.logging.LoggingPreferences
import com.carlink.logging.apply
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Content for the Logs tab in SettingsScreen.
 * Provides controls for file logging, log level selection, and log file management.
 */
@Composable
internal fun LogsTabContent(
    context: android.content.Context,
    fileLogManager: FileLogManager?,
) {
    val scope = rememberCoroutineScope()
    var logFiles by remember { mutableStateOf(emptyList<File>()) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showDebugWarningDialog by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme

    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            val fileToExport = pendingExportFile
            if (uri != null && fileToExport != null) {
                scope.launch {
                    val result = FileExportService.writeFileToUri(context, uri, fileToExport)
                    result
                        .onSuccess { bytesWritten ->
                            Toast.makeText(context, "Exported: ${fileToExport.name}", Toast.LENGTH_SHORT).show()
                        }.onFailure { error ->
                            logError("[FILE_EXPORT] Export failed: ${error.message}", tag = "FILE_LOG")
                            Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_SHORT).show()
                        }
                    pendingExportFile = null
                    isExporting = false
                }
            } else {
                logInfo("[FILE_EXPORT] User cancelled document picker", tag = "FILE_LOG")
                pendingExportFile = null
                isExporting = false
            }
        }

    val loggingPreferences = remember { LoggingPreferences.getInstance(context) }
    val isLoggingEnabled by loggingPreferences.loggingEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val currentLogLevel by loggingPreferences.logLevelFlow.collectAsStateWithLifecycle(initialValue = LogPreset.SILENT)

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
            LoggingControlCard(
                title = "File Logging",
                icon = Icons.AutoMirrored.Filled.Article,
            ) {
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
                                text = "Status",
                                style =
                                    MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                color = colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FileLoggingStatusRow(
                                label = "Logging:",
                                value = if (isLoggingEnabled) "Enabled" else "Disabled",
                            )
                            FileLoggingStatusRow(
                                label = "Log files:",
                                value = logFiles.size.toString(),
                            )
                            FileLoggingStatusRow(
                                label = "Total size:",
                                value = formatBytes(totalSize),
                            )
                            if (isLoggingEnabled && currentFileSize > 0) {
                                FileLoggingStatusRow(
                                    label = "Current file:",
                                    value = formatBytes(currentFileSize),
                                )
                            }
                        }
                    }
                }
            }

            // Log Files Card
            if (logFiles.isNotEmpty()) {
                LoggingControlCard(
                    title = "Log Files",
                    icon = Icons.Default.Folder,
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        logFiles.forEach { file ->
                            LogFileItem(
                                file = file,
                                dateFormat = dateFormat,
                                isExportEnabled = !isExporting,
                                onDelete = { showDeleteDialog = file },
                                onExport = {
                                    // Check file still exists before export
                                    if (!file.exists()) {
                                        Toast.makeText(context, "File no longer exists", Toast.LENGTH_SHORT).show()
                                        logFiles = fileLogManager.getLogFiles()
                                        return@LogFileItem
                                    }

                                    pendingExportFile = file
                                    isExporting = true

                                    // Flush pending writes off main thread, then launch picker
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            fileLogManager.flush()
                                        }
                                        try {
                                            createDocumentLauncher.launch(file.name)
                                        } catch (e: Exception) {
                                            logError(
                                                "[FILE_EXPORT] Failed to launch document picker: ${e.message}",
                                                tag = "FILE_LOG",
                                            )
                                            Toast.makeText(context, "Cannot open file picker", Toast.LENGTH_SHORT).show()
                                            pendingExportFile = null
                                            isExporting = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Log Level Selector Dialog
    if (showLogLevelDialog) {
        LogLevelSelectorDialog(
            currentLevel = currentLogLevel,
            onDismiss = { showLogLevelDialog = false },
            onSelectLevel = { preset ->
                scope.launch {
                    loggingPreferences.setLogLevel(preset)
                    preset.apply()
                    showLogLevelDialog = false
                }
            },
        )
    }

    // Debug Build Warning Dialog
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
            title = { Text("Debug Build Warning") },
            text = {
                Text(
                    "This is a debug build. File logging will capture additional debug information " +
                        "which may include sensitive data. Only enable this for debugging purposes.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            loggingPreferences.setLoggingEnabled(true)
                            fileLogManager.enable()
                            logFiles = fileLogManager.getLogFiles()
                            showDebugWarningDialog = false
                        }
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colorScheme.error,
                        ),
                ) {
                    Text("Enable Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDebugWarningDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Delete Confirmation Dialog
    showDeleteDialog?.let { file ->
        val fileSizeStr = formatFileSize(file.length())

        Dialog(onDismissRequest = { showDeleteDialog = null }) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            tint = colorScheme.error,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Delete Log File",
                            style =
                                MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = colorScheme.surfaceContainerHighest,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Files to delete:",
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
                                text = "This file will be permanently deleted from your device. " +
                                    "This action cannot be undone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onErrorContainer,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

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

@Composable
private fun LogLevelSelectorDialog(
    currentLevel: LogPreset,
    onDismiss: () -> Unit,
    onSelectLevel: (LogPreset) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    val leftColumn =
        listOf(
            LogPreset.SILENT,
            LogPreset.MINIMAL,
            LogPreset.NORMAL,
            LogPreset.DEBUG,
            LogPreset.PERFORMANCE,
        )
    val rightColumn =
        listOf(
            LogPreset.RX_MESSAGES,
            LogPreset.VIDEO_ONLY,
            LogPreset.AUDIO_ONLY,
            LogPreset.PIPELINE_DEBUG,
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
            BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) preset.color else colorScheme.outline,
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .then(
                            if (isSelected) {
                                Modifier.background(preset.color, shape = CircleShape)
                            } else {
                                Modifier.background(Color.Transparent, shape = CircleShape)
                            },
                        ).border(
                            width = 2.dp,
                            color = preset.color,
                            shape = CircleShape,
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

            Column(modifier = Modifier.weight(1f)) {
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
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

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
