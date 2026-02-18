package com.carlink.protocol

import com.carlink.usb.UsbDeviceWrapper
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * CPC200-CCPA Adapter Protocol Driver
 *
 * Manages the protocol-level communication with the Carlinkit adapter:
 * - Initialization sequence
 * - Heartbeat keepalive
 * - Message sending and receiving
 * - Performance tracking
 */
class AdapterDriver(
    private val usbDevice: UsbDeviceWrapper,
    private val messageHandler: (Message) -> Unit,
    private val errorHandler: (String) -> Unit,
    private val logCallback: (String) -> Unit,
    private val readTimeout: Int = 30000,
    private val writeTimeout: Int = 1000,
    private val videoProcessor: UsbDeviceWrapper.VideoDataProcessor? = null,
) {
    private var heartbeatTimer: Timer? = null
    private var wifiConnectTimer: Timer? = null
    private val heartbeatInterval = 2000L // 2 seconds

    private val isRunning = AtomicBoolean(false)

    // Performance tracking — atomic because send() is called from multiple
    // threads (heartbeat timer, mic capture timer, main thread) and the
    // reading loop callback runs on the USB read thread.
    private val messagesSent = AtomicInteger(0)
    private val messagesReceived = AtomicInteger(0)
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val sendErrors = AtomicInteger(0)
    private val receiveErrors = AtomicInteger(0)
    private val heartbeatsSent = AtomicInteger(0)
    private val sessionStart = AtomicLong(0)
    private val lastHeartbeat = AtomicLong(0)
    private var initMessagesCount = 0

    /**
     * Start the adapter communication with smart initialization.
     *
     * @param config Adapter configuration
     * @param initMode Initialization mode: "FULL", "MINIMAL_PLUS_CHANGES", or "MINIMAL_ONLY"
     * @param pendingChanges Set of config keys that have changed since last init
     */
    fun start(
        config: AdapterConfig = AdapterConfig.DEFAULT,
        initMode: String = "FULL",
        pendingChanges: Set<String> = emptySet(),
    ) {
        if (isRunning.getAndSet(true)) {
            log("Adapter already running")
            return
        }

        sessionStart.set(System.currentTimeMillis())
        log("Starting adapter connection sequence")

        if (!usbDevice.isOpened) {
            log("USB device not opened")
            errorHandler("USB device not opened")
            isRunning.set(false)
            return
        }

        // Start heartbeat FIRST for firmware stabilization
        startHeartbeat()
        log("Heartbeat started before initialization (firmware stabilization)")

        // Send initialization sequence based on mode
        val initMessages = MessageSerializer.generateInitSequence(config, initMode, pendingChanges)
        initMessagesCount = initMessages.size
        log("Sending $initMessagesCount initialization messages (mode=$initMode, changes=$pendingChanges)")

        for ((index, message) in initMessages.withIndex()) {
            log("Init message ${index + 1}/$initMessagesCount")
            if (!send(message)) {
                log("Failed to send init message ${index + 1}")
            }
            // Delay between messages to allow adapter firmware to process each one
            Thread.sleep(120)
        }

        log("Initialization sequence completed")

        // Schedule wifiConnect with timeout (matches pi-carplay behavior)
        wifiConnectTimer = Timer().apply {
            schedule(
                object : TimerTask() {
                    override fun run() {
                        if (isRunning.get()) {
                            log("Sending wifiConnect command (timeout-based)")
                            send(MessageSerializer.serializeCommand(CommandMapping.WIFI_CONNECT))
                        }
                    }
                },
                600,
            )
        }

        // Start reading loop
        log("Starting message reading loop")
        startReadingLoop()
    }

    /**
     * Stop the adapter communication.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        log("Stopping adapter connection")

        wifiConnectTimer?.cancel()
        wifiConnectTimer = null
        stopHeartbeat()
        usbDevice.stopReadingLoop()

        logPerformanceStats()
        resetStats()

        log("Adapter stopped")
    }

    /**
     * Send raw data to the adapter.
     *
     * @param data Serialized message data
     * @return true if send was successful
     */
    fun send(data: ByteArray): Boolean {
        if (!isRunning.get()) {
            return false
        }

        return try {
            val result = usbDevice.write(data, writeTimeout)
            if (result == data.size) {
                messagesSent.incrementAndGet()
                bytesSent.addAndGet(data.size.toLong())
                true
            } else {
                sendErrors.incrementAndGet()
                log("Send incomplete: $result/${data.size} bytes")
                false
            }
        } catch (e: Exception) {
            sendErrors.incrementAndGet()
            log("Send error: ${e.message}")
            errorHandler(e.message ?: "Send error")
            false
        }
    }

    /**
     * Send a command to the adapter.
     */
    fun sendCommand(command: CommandMapping): Boolean {
        log("[SEND] Command ${command.name}")
        return send(MessageSerializer.serializeCommand(command))
    }

    /**
     * Send a multi-touch event.
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>): Boolean = send(MessageSerializer.serializeMultiTouch(touches))

    /**
     * Send microphone audio data.
     */
    fun sendAudio(
        data: ByteArray,
        decodeType: Int = 5,
        audioType: Int = 3,
    ): Boolean = send(MessageSerializer.serializeAudio(data, decodeType, audioType))

    /**
     * Send GNSS/NMEA data to the adapter for forwarding to the phone.
     */
    fun sendGnssData(nmeaSentences: String): Boolean = send(MessageSerializer.serializeGnssData(nmeaSentences))

    /**
     * Get performance statistics.
     */
    fun getPerformanceStats(): Map<String, Any> {
        val sessionDuration =
            if (sessionStart.get() > 0) {
                (System.currentTimeMillis() - sessionStart.get()) / 1000
            } else {
                0L
            }
        val lastHb = lastHeartbeat.get()
        val lastHeartbeatAge =
            if (lastHb > 0) {
                (System.currentTimeMillis() - lastHb) / 1000
            } else {
                0L
            }

        val sent = bytesSent.get()
        val received = bytesReceived.get()

        return mapOf(
            "sessionDurationSeconds" to sessionDuration,
            "initMessagesCount" to initMessagesCount,
            "messagesSent" to messagesSent.get(),
            "messagesReceived" to messagesReceived.get(),
            "bytesSent" to sent,
            "bytesReceived" to received,
            "sendErrors" to sendErrors.get(),
            "receiveErrors" to receiveErrors.get(),
            "heartbeatsSent" to heartbeatsSent.get(),
            "lastHeartbeatSecondsAgo" to lastHeartbeatAge,
            "sendThroughputKBps" to if (sessionDuration > 0) sent / sessionDuration / 1024.0 else 0.0,
            "receiveThroughputKBps" to if (sessionDuration > 0) received / sessionDuration / 1024.0 else 0.0,
            "usbStats" to usbDevice.getPerformanceStats(),
        )
    }

    // ==================== Private Methods ====================

    private fun startHeartbeat() {
        stopHeartbeat()

        heartbeatTimer =
            Timer("HeartbeatTimer", true).apply {
                scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run() {
                            if (isRunning.get()) {
                                lastHeartbeat.set(System.currentTimeMillis())
                                heartbeatsSent.incrementAndGet()
                                if (!send(MessageSerializer.serializeHeartbeat())) {
                                    log("Heartbeat send failed (count: $heartbeatsSent)")
                                }
                            }
                        }
                    },
                    heartbeatInterval,
                    heartbeatInterval,
                )
            }

        log("Heartbeat started (every ${heartbeatInterval}ms)")
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun startReadingLoop() {
        usbDevice.startReadingLoop(
            object : UsbDeviceWrapper.ReadingLoopCallback {
                override fun onMessage(
                    type: Int,
                    data: ByteArray?,
                    dataLength: Int,
                ) {
                    messagesReceived.incrementAndGet()
                    bytesReceived.addAndGet((dataLength + HEADER_SIZE).toLong())

                    // For VIDEO_DATA with direct processing, data is null (processed directly by videoProcessor)
                    // Just signal the message handler that video is streaming
                    if (type == MessageType.VIDEO_DATA.id && videoProcessor != null && (data == null || dataLength == 0)) {
                        // Video data was processed directly by videoProcessor - just signal streaming
                        try {
                            messageHandler(VideoStreamingSignal)
                        } catch (e: Exception) {
                            receiveErrors.incrementAndGet()
                            log("Message handler error: ${e.message}")
                        }
                        return
                    }

                    val header = MessageHeader(dataLength, MessageType.fromId(type))
                    val message = MessageParser.parseMessage(header, data)

                    // Log received message (except high-frequency types)
                    if (type != MessageType.VIDEO_DATA.id && type != MessageType.AUDIO_DATA.id) {
                        log("[RECV] $message")
                    }

                    try {
                        messageHandler(message)
                    } catch (e: Exception) {
                        receiveErrors.incrementAndGet()
                        log("Message handler error: ${e.message}")
                    }
                }

                override fun onError(error: String) {
                    receiveErrors.incrementAndGet()
                    log("Reading loop error: $error")
                    errorHandler(error)
                }
            },
            readTimeout,
            videoProcessor,
        )
    }

    private fun logPerformanceStats() {
        val sessionDuration =
            if (sessionStart.get() > 0) {
                (System.currentTimeMillis() - sessionStart.get()) / 1000
            } else {
                0L
            }
        val sent = bytesSent.get()
        val received = bytesReceived.get()
        val sendThroughput =
            if (sessionDuration > 0) {
                String.format(Locale.US, "%.1f", sent / sessionDuration / 1024.0)
            } else {
                "0.0"
            }
        val receiveThroughput =
            if (sessionDuration > 0) {
                String.format(Locale.US, "%.1f", received / sessionDuration / 1024.0)
            } else {
                "0.0"
            }

        log("Adapter Performance Summary:")
        log("  Session: ${sessionDuration}s | Init: $initMessagesCount msgs | Heartbeats: ${heartbeatsSent.get()}")
        log("  TX: ${messagesSent.get()} msgs / ${sent / 1024}KB / ${sendThroughput}KB/s")
        log("  RX: ${messagesReceived.get()} msgs / ${received / 1024}KB / ${receiveThroughput}KB/s")
        log("  Errors: TX=${sendErrors.get()} RX=${receiveErrors.get()}")
    }

    private fun resetStats() {
        messagesSent.set(0)
        messagesReceived.set(0)
        bytesSent.set(0)
        bytesReceived.set(0)
        sendErrors.set(0)
        receiveErrors.set(0)
        heartbeatsSent.set(0)
        sessionStart.set(0)
        lastHeartbeat.set(0)
        initMessagesCount = 0
    }

    private fun log(message: String) {
        logCallback("[ADAPTR] $message")
    }
}
