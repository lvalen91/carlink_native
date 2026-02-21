package com.carlink.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.carlink.logging.Logger
import com.carlink.logging.logDebug
import com.carlink.protocol.KnownDevices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.carlink.USB_PERMISSION"
private const val MAX_PAYLOAD_SIZE = 2 * 1024 * 1024 // 2MB — reject corrupted headers

/**
 * USB Device Wrapper for Carlinkit Adapter Communication
 *
 * Provides a high-level interface for USB device operations including:
 * - Device discovery and permission handling
 * - Connection lifecycle management
 * - Bulk transfer operations for data exchange
 * - Interface claiming and endpoint configuration
 */
class UsbDeviceWrapper(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val logCallback: (String) -> Unit,
) {
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val _isOpened = AtomicBoolean(false)
    private val _isReadingLoopActive = AtomicBoolean(false)
    @Volatile private var readLoopThread: Thread? = null

    val isOpened: Boolean get() = _isOpened.get()
    val isReadingLoopActive: Boolean get() = _isReadingLoopActive.get()

    val vendorId: Int get() = device.vendorId
    val productId: Int get() = device.productId
    val deviceName: String get() = device.deviceName

    // Performance tracking — atomic because write() is called from multiple threads
    // (heartbeat timer, mic capture timer, frame interval coroutine, UI thread)
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val sendCount = AtomicInteger(0)
    private val receiveCount = AtomicInteger(0)
    private val sendErrors = AtomicInteger(0)
    private val receiveErrors = AtomicInteger(0)

    /**
     * Check if we have permission to access the USB device.
     */
    fun hasPermission(): Boolean = usbManager.hasPermission(device)

    /**
     * Request USB permission from the user.
     * This will show a system dialog asking the user to grant permission.
     *
     * @param timeoutMs Timeout in milliseconds to wait for user response
     * @return true if permission was granted, false if denied or timeout
     */
    suspend fun requestPermission(timeoutMs: Long = 30_000L): Boolean {
        if (usbManager.hasPermission(device)) {
            log("Permission already granted for ${device.deviceName}")
            return true
        }

        log("Requesting USB permission for ${device.deviceName}...")

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            ctx: Context,
                            intent: Intent,
                        ) {
                            if (ACTION_USB_PERMISSION == intent.action) {
                                try {
                                    context.unregisterReceiver(this)
                                } catch (_: IllegalArgumentException) {
                                    // Already unregistered
                                }

                                val granted =
                                    intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false,
                                    )
                                log("USB permission ${if (granted) "granted" else "denied"}")

                                if (continuation.isActive) {
                                    continuation.resume(granted)
                                }
                            }
                        }
                    }

                // Register receiver using ContextCompat for API compatibility
                // RECEIVER_NOT_EXPORTED ensures only this app can send permission broadcasts
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(ACTION_USB_PERMISSION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                // Create pending intent with explicit Intent (package set) for Android 12+ security
                // FLAG_MUTABLE is required because UsbManager adds extras to the intent
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                usbManager.requestPermission(device, pendingIntent)

                // Cleanup on cancellation
                continuation.invokeOnCancellation {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: IllegalArgumentException) {
                        // Already unregistered
                    }
                }
            }
        } ?: run {
            log("USB permission request timed out")
            false
        }
    }

    /**
     * Open the USB device and claim the interface.
     *
     * @return true if device was opened successfully
     */
    fun open(): Boolean {
        if (_isOpened.get()) {
            log("Device already opened")
            return true
        }

        // Check permission
        if (!usbManager.hasPermission(device)) {
            log("No permission for device ${device.deviceName}")
            return false
        }

        // Open connection
        connection = usbManager.openDevice(device)
        if (connection == null) {
            log("Failed to open device connection")
            return false
        }

        // Find and claim the bulk transfer interface
        if (!claimBulkInterface()) {
            connection?.close()
            connection = null
            return false
        }

        _isOpened.set(true)
        log("Device opened: VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)}")
        return true
    }

    /**
     * Request permission if needed and open the USB device.
     * This is a convenience suspend function that combines requestPermission() and open().
     *
     * @return true if device was opened successfully
     */
    suspend fun openWithPermission(): Boolean {
        if (!hasPermission()) {
            if (!requestPermission()) {
                return false
            }
        }
        return open()
    }

    /**
     * Close the USB device and release resources.
     */
    fun close() {
        stopReadingLoop()

        claimedInterface?.let { iface ->
            try {
                connection?.releaseInterface(iface)
            } catch (e: Exception) {
                log("Error releasing interface: ${e.message}")
            }
        }
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null

        connection?.close()
        connection = null
        _isOpened.set(false)

        log(
            "Device closed (sent: ${sendCount.get()}/${bytesSent.get()} bytes, " +
                "received: ${receiveCount.get()}/${bytesReceived.get()} bytes)"
        )
    }

    /**
     * Reset the USB device.
     *
     * @return true if reset was successful
     */
    fun reset(): Boolean {
        connection ?: return false

        return try {
            // Note: controlTransfer with USB_DEVICE_RESET is not directly available
            // Using close/reopen pattern for reset
            close()
            Thread.sleep(500)
            open()
        } catch (e: Exception) {
            log("Device reset failed: ${e.message}")
            false
        }
    }

    /**
     * Write data to the USB device.
     *
     * @param data Data to send
     * @param timeout Timeout in milliseconds
     * @return Number of bytes actually sent, or -1 on error
     */
    fun write(
        data: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot write: device not open")
                return -1
            }

        val endpoint =
            outEndpoint ?: run {
                log("Cannot write: no OUT endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, data, data.size, timeout)
            if (result >= 0) {
                bytesSent.addAndGet(result.toLong())
                sendCount.incrementAndGet()
            } else {
                sendErrors.incrementAndGet()
            }
            result
        } catch (e: Exception) {
            sendErrors.incrementAndGet()
            log("Write error: ${e.message}")
            -1
        }
    }

    /**
     * Read data from the USB device.
     *
     * @param buffer Buffer to receive data
     * @param timeout Timeout in milliseconds
     * @return Number of bytes read, or -1 on error/timeout
     */
    fun read(
        buffer: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot read: device not open")
                return -1
            }

        val endpoint =
            inEndpoint ?: run {
                log("Cannot read: no IN endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, buffer, buffer.size, timeout)
            if (result >= 0) {
                bytesReceived.addAndGet(result.toLong())
                receiveCount.incrementAndGet()
            } else if (result != -1) {
                // -1 is timeout, not an error
                receiveErrors.incrementAndGet()
            }
            result
        } catch (e: Exception) {
            receiveErrors.incrementAndGet()
            log("Read error: ${e.message}")
            -1
        }
    }

    /**
     * Callback interface for direct video data processing.
     * [DIRECT_HANDOFF]: Data is already read into a buffer by the read loop.
     * Processor receives the buffer directly — no callback, no copy.
     */
    interface VideoDataProcessor {
        /**
         * Process video data directly. Data is valid only for duration of this call.
         *
         * @param data Buffer containing video payload (including 20-byte video header)
         * @param dataLength Actual bytes read into data
         * @param sourcePtsMs Source presentation timestamp in milliseconds from video header
         */
        fun processVideoDirect(data: ByteArray, dataLength: Int, sourcePtsMs: Int)
    }

    /**
     * Callback interface for reading loop events.
     */
    interface ReadingLoopCallback {
        fun onMessage(
            type: Int,
            data: ByteArray?,
            dataLength: Int,
        )

        fun onError(error: String)
    }

    /**
     * Start the continuous reading loop.
     *
     * @param callback Callback for received messages
     * @param timeout Read timeout in milliseconds
     * @param videoProcessor Optional processor for direct video data handling (bypasses message parsing)
     */
    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
    ) {
        if (_isReadingLoopActive.getAndSet(true)) {
            log("Reading loop already active")
            return
        }

        Thread {
            // Elevate thread priority: USB read feeds BOTH audio and video pipelines
            // Must be >= video codec priority to prevent audio underruns from data starvation
            // Using -10 (between URGENT_DISPLAY=-8 and AUDIO=-16) to match MediaCodec_loop priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)

            log("Reading loop started")
            val headerBuffer = ByteArray(16)

            // Pre-allocate video buffer to avoid per-frame allocation (reduces GC pressure at 60fps)
            // Initial size 256KB covers most frames; grows if needed (rare for 1080p H.264)
            var videoBuffer = ByteArray(256 * 1024)

            // Pre-allocate audio buffer to avoid per-packet allocation (~17 packets/sec)
            // Initial size 16KB covers typical 11532-byte audio payloads; grows if needed
            var audioBuffer = ByteArray(16 * 1024)

            // Pre-allocate chunk buffer for non-video message reads (audio, commands, etc.)
            val chunkBuffer = ByteArray(16384)

            // Consecutive-timeout detection: if we were receiving data and then get
            // MAX_CONSECUTIVE_TIMEOUTS in a row, the adapter has gone silent (disconnected
            // or stalled). Report as error so auto-reconnect can kick in.
            var hasReceivedData = false
            var consecutiveTimeouts = 0
            val maxConsecutiveTimeouts = 2 // 2 × 30s timeout = 60s of silence

            try {
                while (_isReadingLoopActive.get() && _isOpened.get()) {
                    // Read header
                    val headerResult = read(headerBuffer, timeout)
                    if (headerResult != 16) {
                        if (_isReadingLoopActive.get()) {
                            if (headerResult == -1) {
                                // Timeout — check if adapter has gone silent
                                if (hasReceivedData) {
                                    consecutiveTimeouts++
                                    if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
                                        log("Adapter silent: $consecutiveTimeouts consecutive timeouts " +
                                            "(${consecutiveTimeouts * timeout / 1000}s) after data was flowing")
                                        callback.onError("USB read timeout — adapter not responding")
                                        break
                                    }
                                }
                                continue
                            }
                            log("Incomplete header read: $headerResult bytes")
                        }
                        continue
                    }

                    // Successful header read — reset timeout counter
                    consecutiveTimeouts = 0
                    hasReceivedData = true

                    // Parse header
                    val header =
                        try {
                            com.carlink.protocol.MessageParser
                                .parseHeader(headerBuffer)
                        } catch (e: com.carlink.protocol.HeaderParseException) {
                            log("Header parse error: ${e.message}")
                            continue
                        }

                    // Reject corrupted headers with implausible payload sizes
                    if (header.length > MAX_PAYLOAD_SIZE) {
                        log("Corrupted header: length=${header.length} exceeds max — skipping")
                        continue
                    }

                    // Handle VIDEO_DATA and NAVI_VIDEO_DATA with direct handoff to codec
                    if ((header.type == com.carlink.protocol.MessageType.VIDEO_DATA ||
                            header.type == com.carlink.protocol.MessageType.NAVI_VIDEO_DATA) &&
                        header.length > 0 && videoProcessor != null
                    ) {
                        val conn = connection
                        val endpoint = inEndpoint
                        if (conn != null && endpoint != null) {
                            try {
                                // Reuse pre-allocated buffer; grow only if needed (rare)
                                if (videoBuffer.size < header.length) {
                                    videoBuffer = ByteArray(maxOf(header.length, videoBuffer.size * 2))
                                }
                                var totalRead = 0
                                var readAttempts = 0
                                var lastChunkResult = 0

                                while (totalRead < header.length && _isReadingLoopActive.get()) {
                                    val remaining = header.length - totalRead
                                    val chunkSize = minOf(remaining, 16384)
                                    readAttempts++
                                    val chunkRead = conn.bulkTransfer(
                                        endpoint,
                                        videoBuffer,
                                        totalRead,
                                        chunkSize,
                                        timeout,
                                    )
                                    lastChunkResult = chunkRead
                                    if (chunkRead > 0) {
                                        totalRead += chunkRead
                                        bytesReceived.addAndGet(chunkRead.toLong())
                                        receiveCount.incrementAndGet()
                                    } else if (chunkRead <= 0) {
                                        logDebug(
                                            "[VIDEO_READ] Read failed: attempts=$readAttempts, " +
                                                "got=$totalRead/${header.length}, lastResult=$chunkRead",
                                            tag = Logger.Tags.VIDEO_USB
                                        )
                                        break
                                    }
                                }

                                // Extract source PTS from video header (offset 12) if we have enough data
                                val sourcePts = if (totalRead >= 16) {
                                    extractPtsFromHeader(videoBuffer)
                                } else {
                                    logDebug(
                                        "[VIDEO_READ] Incomplete read for PTS: got=$totalRead bytes, need>=16",
                                        tag = Logger.Tags.VIDEO_USB
                                    )
                                    0
                                }

                                if (totalRead == header.length) {
                                    videoProcessor.processVideoDirect(videoBuffer, totalRead, sourcePts)
                                } else {
                                    logDebug(
                                        "[VIDEO_READ] Partial frame dropped: got=$totalRead/${header.length} " +
                                            "attempts=$readAttempts, lastResult=$lastChunkResult",
                                        tag = Logger.Tags.VIDEO_USB
                                    )
                                }

                                // Notify callback that video data was received
                                callback.onMessage(header.type.id, null, 0)
                            } catch (e: Exception) {
                                log("Video processing error (non-fatal): ${e.message}")
                                receiveErrors.incrementAndGet()
                            }
                        }
                        continue
                    }

                    // Read payload for non-video messages (audio, commands, media metadata, etc.)
                    // For AUDIO_DATA: reuse pre-allocated audioBuffer to avoid per-packet allocation
                    val isAudio = header.type == com.carlink.protocol.MessageType.AUDIO_DATA
                    var dataLength = 0
                    val payload: ByteArray? =
                        if (header.length > 0) {
                            val payloadBuffer = if (isAudio) {
                                // Grow audioBuffer if needed (doubling strategy)
                                if (header.length > audioBuffer.size) {
                                    audioBuffer = ByteArray(maxOf(header.length, audioBuffer.size * 2))
                                }
                                audioBuffer
                            } else {
                                ByteArray(header.length)
                            }
                            var totalRead = 0
                            while (totalRead < header.length && _isReadingLoopActive.get()) {
                                val chunkRead = read(chunkBuffer, timeout)
                                if (chunkRead > 0) {
                                    val bytesToCopy = minOf(chunkRead, header.length - totalRead)
                                    System.arraycopy(chunkBuffer, 0, payloadBuffer, totalRead, bytesToCopy)
                                    totalRead += bytesToCopy
                                } else if (chunkRead == -1) {
                                    // Timeout
                                    break
                                }
                            }
                            if (totalRead == header.length) {
                                dataLength = totalRead
                                payloadBuffer
                            } else {
                                null
                            }
                        } else {
                            null
                        }

                    // Deliver message to callback
                    try {
                        callback.onMessage(header.type.id, payload, dataLength)
                    } catch (e: Exception) {
                        log("Message callback error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (_isReadingLoopActive.get()) {
                    log("Reading loop error: ${e.message}")
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                _isReadingLoopActive.set(false)
                log("Reading loop stopped")
            }
        }.apply {
            name = "USB-ReadLoop"
            isDaemon = true
            start()
        }.also { readLoopThread = it }
    }

    /**
     * Stop the reading loop.
     */
    fun stopReadingLoop() {
        if (!_isReadingLoopActive.getAndSet(false)) return
        try {
            readLoopThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readLoopThread = null
    }

    // ==================== Private Methods ====================

    private fun claimBulkInterface(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)

            // Look for bulk endpoints
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        inEp = endpoint
                    } else {
                        outEp = endpoint
                    }
                }
            }

            // If we found both endpoints, claim this interface
            if (inEp != null && outEp != null) {
                val claimed = connection?.claimInterface(iface, true) ?: false
                if (claimed) {
                    claimedInterface = iface
                    inEndpoint = inEp
                    outEndpoint = outEp
                    log("Claimed interface $i: IN=${inEp.address.toString(16)} OUT=${outEp.address.toString(16)}")
                    return true
                }
            }
        }

        log("No suitable bulk interface found")
        return false
    }

    private fun log(message: String) {
        logCallback("[USB] $message")
    }

    companion object {
        /**
         * Find all connected Carlinkit devices.
         */
        fun findDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                KnownDevices.isKnownDevice(device.vendorId, device.productId)
            }

        /**
         * Create a wrapper for the first available Carlinkit device.
         */
        fun findFirst(
            context: Context,
            usbManager: UsbManager,
            logCallback: (String) -> Unit,
        ): UsbDeviceWrapper? {
            val device = findDevices(usbManager).firstOrNull() ?: return null
            return UsbDeviceWrapper(context, usbManager, device, logCallback)
        }

        /**
         * Extract source PTS from video header buffer.
         * PTS is at offset 12 in the 20-byte video header, little-endian int.
         */
        fun extractPtsFromHeader(buffer: ByteArray, offset: Int = 0): Int {
            if (buffer.size < offset + 16) return 0
            return (buffer[offset + 12].toInt() and 0xFF) or
                ((buffer[offset + 13].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 14].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 15].toInt() and 0xFF) shl 24)
        }
    }
}
