package com.carlink.logging

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Stateless utility for file export operations using Android's Storage Access Framework (SAF).
 *
 * This object provides pure I/O functions for reading files and writing to URIs.
 * It is designed to work with Compose's `rememberLauncherForActivityResult` pattern
 * where the Composable handles lifecycle-aware launcher registration and this service
 * handles the actual I/O operations.
 *
 * Usage in Compose:
 * ```kotlin
 * val launcher = rememberLauncherForActivityResult(
 *     contract = ActivityResultContracts.CreateDocument("text/plain")
 * ) { uri ->
 *     uri?.let {
 *         scope.launch {
 *             val result = FileExportService.writeFileToUri(context, it, file)
 *             result.onSuccess { bytes -> showSuccess("Exported $bytes bytes") }
 *             result.onFailure { error -> showError(error.message) }
 *         }
 *     }
 * }
 * ```
 *
 * Design rationale:
 * - Stateless: No mutable state, no race conditions
 * - Suspend functions: All I/O runs on Dispatchers.IO per Android best practices
 * - Result type: Explicit error handling without exceptions propagating
 * - Separation of concerns: Compose handles lifecycle, this handles I/O
 *
 * @see <a href="https://developer.android.com/training/data-storage/shared/documents-files">SAF Documentation</a>
 */
object FileExportService {
    private const val TAG = Logger.Tags.FILE_LOG

    /**
     * Writes file contents to the specified URI.
     *
     * This function reads the entire file into memory and writes it to the destination URI
     * using Android's ContentResolver. Suitable for log files up to ~10MB.
     *
     * @param context Android context for ContentResolver access
     * @param uri Destination URI from SAF document picker
     * @param file Source file to export
     * @return Result containing bytes written on success, or exception on failure
     */
    suspend fun writeFileToUri(
        context: Context,
        uri: Uri,
        file: File,
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                logInfo("[FILE_EXPORT] Read ${bytes.size} bytes from ${file.name}", tag = TAG)

                writeBytesToUri(context, uri, bytes, file.name)
            } catch (e: IOException) {
                logError("[FILE_EXPORT] Failed to read file ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            } catch (e: SecurityException) {
                logError("[FILE_EXPORT] Permission denied reading ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            }
        }

    /**
     * Writes byte array to the specified URI.
     *
     * Uses ContentResolver.openOutputStream with proper resource management via Kotlin's use().
     * The output stream is flushed before closing to ensure all data is written.
     *
     * @param context Android context for ContentResolver access
     * @param uri Destination URI from SAF document picker
     * @param bytes Data to write
     * @param fileName Optional filename for logging (defaults to "data")
     * @return Result containing bytes written on success, or exception on failure
     */
    suspend fun writeBytesToUri(
        context: Context,
        uri: Uri,
        bytes: ByteArray,
        fileName: String = "data",
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream == null) {
                    val error = IOException("Failed to open output stream for URI: $uri")
                    logError("[FILE_EXPORT] ${error.message}", tag = TAG)
                    return@withContext Result.failure(error)
                }

                outputStream.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }

                logInfo("[FILE_EXPORT] Successfully wrote ${bytes.size} bytes ($fileName)", tag = TAG)
                Result.success(bytes.size)
            } catch (e: IOException) {
                logError("[FILE_EXPORT] Write failed for $fileName: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            } catch (e: SecurityException) {
                logError("[FILE_EXPORT] Permission denied writing $fileName: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            }
        }

    /**
     * Reads file contents into a byte array.
     *
     * Utility function for cases where file reading needs to be done separately
     * from writing (e.g., to show file size before export).
     *
     * @param file Source file to read
     * @return Result containing byte array on success, or exception on failure
     */
    suspend fun readFileBytes(file: File): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = file.readBytes()
                logInfo("[FILE_EXPORT] Read ${bytes.size} bytes from ${file.name}", tag = TAG)
                Result.success(bytes)
            } catch (e: IOException) {
                logError("[FILE_EXPORT] Failed to read ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            } catch (e: SecurityException) {
                logError("[FILE_EXPORT] Permission denied reading ${file.name}: ${e.message}", tag = TAG, throwable = e)
                Result.failure(e)
            }
        }
}
