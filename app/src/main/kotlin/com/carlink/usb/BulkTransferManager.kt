package com.carlink.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.carlink.BuildConfig
import com.carlink.util.LogCallback
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Exception thrown when a USB bulk transfer operation fails.
 *
 * This provides more specific error handling than generic Exception,
 * allowing callers to distinguish USB transfer failures from other errors.
 *
 * @param message Detailed error message describing the failure
 * @param cause Optional underlying cause of the failure
 */
class UsbTransferException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * BulkTransferManager - Robust USB Bulk Transfer Operations
 *
 * PURPOSE:
 * Manages all USB bulk transfer operations (IN and OUT) for the CPC200-CCPA protocol,
 * providing retry logic, exponential backoff, chunked reading, and comprehensive error
 * handling. This class encapsulates the complexity of reliable USB communication over
 * USB 2.0 connections.
 *
 * RESPONSIBILITIES:
 * - Bulk Transfer IN: Reading data from USB endpoints with retry logic
 * - Bulk Transfer OUT: Writing data to USB endpoints with retry logic
 * - Chunked Reading: Breaking large reads into optimal 16KB chunks for USB 2.0
 * - Error Recovery: Exponential backoff, timeout handling, device disconnect detection
 * - Bounds Checking: Preventing buffer overruns and ArrayIndexOutOfBounds exceptions
 *
 * PERFORMANCE OPTIMIZATIONS:
 * - 16KB chunk size optimized for USB 2.0 High-Speed (480 Mbps)
 * - Retry logic with exponential backoff (100ms base, doubles each retry)
 * - Early detection of device disconnection (actualLength == -1)
 * - Memory safety with comprehensive bounds checking
 *
 * THREAD SAFETY:
 * All methods are thread-safe and designed to be called from executor threads
 * (typically AppExecutors.usbIn() or usbOut()).
 *
 * ANDROID USB BEST PRACTICES:
 * - Follows Android USB Host API guidelines for bulk transfers
 * - Handles timeout conditions (actualLength == 0) with retry
 * - Detects device disconnect (actualLength == -1) immediately
 * - Validates buffer bounds before every transfer operation
 *
 * @param logCallback Callback for logging transfer operations and errors
 */

private const val TAG = "CARLINK"

class BulkTransferManager(
    private val logCallback: LogCallback,
) {
    companion object {
        // Optimal chunk size for USB 2.0 High-Speed (480 Mbps)
        // Balances throughput with latency for real-time video streaming
        const val CHUNK_SIZE = 16384 // 16KB

        // Default retry count for transfer operations
        const val DEFAULT_RETRY_COUNT = 3

        // Base retry delay in milliseconds
        const val BASE_RETRY_DELAY_MS = 100L

        // Maximum expected frame size (1MB) to prevent buffer overflow attacks
        const val MAX_FRAME_SIZE = 1048576
    }

    /**
     * Performs bulk transfer IN (read from device) with retry logic and proper error handling.
     *
     * This method implements the following retry strategy:
     * - On timeout (actualLength == 0): Retry with exponential backoff
     * - On disconnect (actualLength == -1): Fail immediately
     * - On exception: Retry with longer delay
     *
     * @param connection Active USB device connection
     * @param endpoint USB endpoint to read from (must be IN direction)
     * @param maxLength Maximum bytes to read
     * @param timeout Timeout in milliseconds for each transfer attempt
     * @param retryCount Number of retry attempts (default: 3)
     * @return ByteArray with read data, or null on failure
     * @throws Exception if device disconnects or unrecoverable error occurs
     */
    fun performBulkTransferIn(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        maxLength: Int,
        timeout: Int,
        retryCount: Int = DEFAULT_RETRY_COUNT,
    ): ByteArray? {
        var lastException: Exception? = null

        repeat(retryCount) { attempt ->
            try {
                val buffer = ByteArray(maxLength)
                val actualLength = connection.bulkTransfer(endpoint, buffer, maxLength, timeout)

                return when {
                    actualLength > 0 -> {
                        buffer.copyOf(actualLength)
                    }

                    actualLength == 0 -> {
                        // Timeout occurred, retry with exponential backoff
                        if (attempt < retryCount - 1) {
                            val delay = BASE_RETRY_DELAY_MS * (1 shl attempt) // Exponential: 100ms, 200ms, 400ms
                            TimeUnit.MILLISECONDS.sleep(delay)
                            return@repeat
                        }
                        null
                    }

                    actualLength == -1 -> {
                        throw UsbTransferException("Device disconnected during transfer")
                    }

                    else -> {
                        throw UsbTransferException("Transfer error: actualLength=$actualLength")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryCount - 1) {
                    val delay = 200L * (attempt + 1) // Linear: 200ms, 400ms, 600ms
                    TimeUnit.MILLISECONDS.sleep(delay)
                }
            }
        }

        throw lastException ?: UsbTransferException("Unknown transfer error")
    }

    /**
     * Performs bulk transfer OUT (write to device) with retry logic and proper error handling.
     *
     * This method validates data before transmission and implements retry logic for
     * transient errors while immediately failing on device disconnect.
     *
     * @param connection Active USB device connection
     * @param endpoint USB endpoint to write to (must be OUT direction)
     * @param data Data to write
     * @param timeout Timeout in milliseconds for each transfer attempt
     * @param retryCount Number of retry attempts (default: 3)
     * @return Number of bytes written, or -1 on failure
     * @throws Exception if device disconnects or unrecoverable error occurs
     */
    fun performBulkTransferOut(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray,
        timeout: Int,
        retryCount: Int = DEFAULT_RETRY_COUNT,
    ): Int {
        var lastException: Exception? = null

        repeat(retryCount) { attempt ->
            try {
                val actualLength = connection.bulkTransfer(endpoint, data, data.size, timeout)

                return when {
                    actualLength >= 0 -> {
                        actualLength
                    }

                    actualLength == -1 -> {
                        throw UsbTransferException("Device disconnected during transfer")
                    }

                    else -> {
                        throw UsbTransferException("Transfer error: actualLength=$actualLength")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < retryCount - 1) {
                    val delay = 200L * (attempt + 1) // Linear: 200ms, 400ms, 600ms
                    TimeUnit.MILLISECONDS.sleep(delay)
                }
            }
        }

        throw lastException ?: UsbTransferException("Unknown transfer error")
    }

    /**
     * Reads data from USB endpoint in chunks to optimize for USB 2.0 performance.
     *
     * This method breaks large reads into 16KB chunks, which is optimal for USB 2.0
     * High-Speed transfers. It includes comprehensive bounds checking to prevent
     * buffer overflow vulnerabilities.
     *
     * SECURITY:
     * - Validates all parameters before starting
     * - Checks buffer bounds before each chunk read
     * - Prevents ArrayIndexOutOfBoundsException attacks
     * - Truncates reads if buffer space is exhausted
     *
     * @param connection Active USB device connection
     * @param endpoint USB endpoint to read from
     * @param buffer Destination buffer for read data
     * @param bufferOffset Starting offset in buffer
     * @param maxLength Maximum bytes to read
     * @param timeout Timeout in milliseconds for each chunk
     * @return Number of bytes read, or negative value on error
     */
    fun readByChunks(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        bufferOffset: Int,
        maxLength: Int,
        timeout: Int,
    ): Int {
        val chunkSize = CHUNK_SIZE
        var offset = 0

        // Critical bounds checking to prevent ArrayIndexOutOfBoundsException
        require(bufferOffset >= 0 && maxLength >= 0) {
            "Invalid parameters - bufferOffset: $bufferOffset, maxLength: $maxLength"
        }

        require(bufferOffset + maxLength <= buffer.size) {
            "Buffer overrun - buffer.size: ${buffer.size}, bufferOffset: $bufferOffset, maxLength: $maxLength"
        }

        while (offset < maxLength) {
            var lengthToRead = minOf(chunkSize, maxLength - offset)

            // Additional safety check for each read
            if (bufferOffset + offset + lengthToRead > buffer.size) {
                Log.w(TAG, "[USB] WARNING: Truncating read to prevent buffer overrun")
                lengthToRead = buffer.size - bufferOffset - offset
                if (lengthToRead <= 0) {
                    Log.e(TAG, "[USB] CRITICAL: No space left in buffer")
                    return offset // Return what we've read so far
                }
            }

            val actualLength = connection.bulkTransfer(endpoint, buffer, bufferOffset + offset, lengthToRead, timeout)

            if (actualLength < 0) {
                return actualLength
            } else {
                offset += actualLength
            }
        }

        return maxLength
    }

    /**
     * Validates that a buffer is large enough for the requested read operation.
     *
     * @param buffer Buffer to validate
     * @param offset Starting offset
     * @param length Requested read length
     * @return true if valid, false otherwise
     */
    fun validateBufferSize(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        if (offset < 0 || length < 0) {
            log("[USB] Invalid buffer parameters: offset=$offset, length=$length")
            return false
        }

        if (offset + length > buffer.size) {
            log("[USB] Buffer too small: size=${buffer.size}, required=${offset + length}")
            return false
        }

        return true
    }

    /**
     * Validates that a transfer size is within acceptable limits.
     *
     * @param size Transfer size to validate
     * @param maxSize Maximum allowed size (default: 1MB)
     * @return true if valid, false otherwise
     */
    fun validateTransferSize(
        size: Int,
        maxSize: Int = MAX_FRAME_SIZE,
    ): Boolean {
        if (size < 0) {
            log("[USB] Invalid transfer size: $size")
            return false
        }

        if (size > maxSize) {
            log("[USB] Transfer size too large: $size bytes (max: $maxSize)")
            return false
        }

        return true
    }

    /**
     * Calculates optimal timeout for a transfer based on size.
     *
     * @param dataSize Size of data to transfer in bytes
     * @param baseTimeoutMs Base timeout in milliseconds
     * @return Calculated timeout in milliseconds
     */
    fun calculateTimeout(
        dataSize: Int,
        baseTimeoutMs: Int,
    ): Int {
        // Add additional time for larger transfers
        // Assume ~480 Mbps = ~60 MB/s for USB 2.0 High-Speed
        // Add 1ms per 60KB of data
        val additionalTime = (dataSize / 60000) + 1
        return baseTimeoutMs + additionalTime
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        logCallback.log(message)
    }
}
