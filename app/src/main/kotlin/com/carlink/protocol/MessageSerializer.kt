package com.carlink.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * CPC200-CCPA Protocol Message Serializer
 *
 * Serializes message objects into binary format for transmission to the Carlinkit adapter.
 * Handles header generation, payload encoding, and type-specific serialization.
 *
 * Ported from: lib/driver/sendable.dart
 */
object MessageSerializer {
    /**
     * Create a protocol header for the given message type and payload length.
     */
    fun createHeader(
        type: MessageType,
        payloadLength: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PROTOCOL_MAGIC)
        buffer.putInt(payloadLength)
        buffer.putInt(type.id)
        buffer.putInt(type.id.inv())
        return buffer.array()
    }

    /**
     * Serialize a complete message with header and payload.
     */
    private fun serializeWithPayload(
        type: MessageType,
        payload: ByteArray,
    ): ByteArray {
        val header = createHeader(type, payload.size)
        return header + payload
    }

    /**
     * Serialize a header-only message (no payload).
     */
    private fun serializeHeaderOnly(type: MessageType): ByteArray = createHeader(type, 0)

    // ==================== Command Messages ====================

    /**
     * Serialize a command message.
     */
    fun serializeCommand(command: CommandMapping): ByteArray {
        val payload =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command.id)
                .array()
        return serializeWithPayload(MessageType.COMMAND, payload)
    }

    // ==================== Touch Messages ====================

    /**
     * Serialize a single touch event.
     *
     * @param action Touch action type
     * @param x Normalized X coordinate (0.0 to 1.0)
     * @param y Normalized Y coordinate (0.0 to 1.0)
     */
    fun serializeTouch(
        action: TouchAction,
        x: Float,
        y: Float,
    ): ByteArray {
        val finalX = (10000 * x).toInt().coerceIn(0, 10000)
        val finalY = (10000 * y).toInt().coerceIn(0, 10000)

        val payload =
            ByteBuffer
                .allocate(16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(action.id)
                .putInt(finalX)
                .putInt(finalY)
                .putInt(0) // flags
                .array()

        return serializeWithPayload(MessageType.TOUCH, payload)
    }

    /**
     * Touch point data for multi-touch events.
     */
    data class TouchPoint(
        val x: Float,
        val y: Float,
        val action: MultiTouchAction,
        val id: Int,
    )

    /**
     * Serialize a multi-touch event.
     *
     * @param touches List of touch points with normalized coordinates
     */
    fun serializeMultiTouch(touches: List<TouchPoint>): ByteArray {
        val payload = ByteBuffer.allocate(touches.size * 16).order(ByteOrder.LITTLE_ENDIAN)

        for (touch in touches) {
            payload.putFloat(touch.x)
            payload.putFloat(touch.y)
            payload.putInt(touch.action.id)
            payload.putInt(touch.id)
        }

        return serializeWithPayload(MessageType.MULTI_TOUCH, payload.array())
    }

    // ==================== Audio Messages ====================

    /**
     * Serialize a microphone audio message.
     *
     * @param data Raw PCM audio data
     * @param decodeType Audio format (default: 5 = 16kHz mono)
     * @param audioType Stream type (default: 3 = Siri/voice input)
     * @param volume Volume level (default: 0.0 per protocol)
     */
    fun serializeAudio(
        data: ByteArray,
        decodeType: Int = 5,
        audioType: Int = 3,
        volume: Float = 0.0f,
    ): ByteArray {
        val payload =
            ByteBuffer
                .allocate(12 + data.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(decodeType)
                .putFloat(volume)
                .putInt(audioType)
                .put(data)
                .array()

        return serializeWithPayload(MessageType.AUDIO_DATA, payload)
    }

    // ==================== File Messages ====================

    /**
     * Serialize a file send message.
     */
    fun serializeFile(
        fileName: String,
        content: ByteArray,
    ): ByteArray {
        val fileNameBytes = (fileName + "\u0000").toByteArray(StandardCharsets.US_ASCII)

        val payload =
            ByteBuffer
                .allocate(4 + fileNameBytes.size + 4 + content.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(fileNameBytes.size)
                .put(fileNameBytes)
                .putInt(content.size)
                .put(content)
                .array()

        return serializeWithPayload(MessageType.SEND_FILE, payload)
    }

    /**
     * Serialize a number to a file.
     */
    fun serializeNumber(
        number: Int,
        file: FileAddress,
    ): ByteArray {
        val content =
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(number)
                .array()
        return serializeFile(file.path, content)
    }

    /**
     * Serialize a boolean to a file.
     */
    fun serializeBoolean(
        value: Boolean,
        file: FileAddress,
    ): ByteArray = serializeNumber(if (value) 1 else 0, file)

    /**
     * Serialize a string to a file.
     */
    fun serializeString(
        value: String,
        file: FileAddress,
    ): ByteArray = serializeFile(file.path, value.toByteArray(StandardCharsets.US_ASCII))

    // ==================== Protocol Messages ====================

    /**
     * Serialize a heartbeat message.
     */
    fun serializeHeartbeat(): ByteArray = serializeHeaderOnly(MessageType.HEARTBEAT)

    /**
     * Serialize an open message with adapter configuration.
     */
    fun serializeOpen(config: AdapterConfig): ByteArray {
        val payload =
            ByteBuffer
                .allocate(28)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(config.width)
                .putInt(config.height)
                .putInt(config.fps)
                .putInt(config.format)
                .putInt(config.packetMax)
                .putInt(config.iBoxVersion)
                .putInt(config.phoneWorkMode)
                .array()

        return serializeWithPayload(MessageType.OPEN, payload)
    }

    /**
     * Serialize box settings message with JSON configuration.
     */
    fun serializeBoxSettings(
        config: AdapterConfig,
        syncTime: Long? = null,
    ): ByteArray {
        val actualSyncTime = syncTime ?: (System.currentTimeMillis() / 1000)

        // Android Auto Resolution Selection Algorithm
        // AA supports only 3 resolutions: 800x480, 1280x720, 1920x1080
        val (aaWidth, aaHeight) =
            when {
                config.width >= 1920 && config.height >= 1080 -> Pair(1920, 1080)
                config.width >= 1280 && config.height >= 720 -> Pair(1280, 720)
                else -> Pair(800, 480)
            }

        val json =
            JSONObject().apply {
                put("mediaDelay", config.mediaDelay)
                put("syncTime", actualSyncTime)
                put("androidAutoSizeW", aaWidth)
                put("androidAutoSizeH", aaHeight)
                put("mediaSound", if (config.sampleRate == 44100) 0 else 1) // 0=44.1kHz, 1=48kHz
                put("callQuality", config.callQuality) // 0=normal, 1=clear, 2=HD
                put("WiFiChannel", 161) // 5GHz channel 161 (optimal low interference)
                put("wifiChannel", 161) // Both keys for compatibility
                put("wifiName", "carlink")
                put("btName", "carlink")
                put("boxName", "carlink")
                put("OemName", "carlink")
            }

        val payload = json.toString().toByteArray(StandardCharsets.US_ASCII)
        return serializeWithPayload(MessageType.BOX_SETTINGS, payload)
    }

    /**
     * Generate AirPlay configuration string.
     * Note: 'name' must be "AutoBox" (hardcoded) for CarPlay UI to display correctly.
     * The custom display name goes in 'oemIconLabel' only.
     */
    fun generateAirplayConfig(config: AdapterConfig): String =
        """oemIconVisible = ${if (config.oemIconVisible) "1" else "0"}
name = AutoBox
model = Magic-Car-Link-1.00
oemIconPath = /etc/oem_icon.png
oemIconLabel = ${config.boxName}
"""

    // ==================== Initialization Sequence ====================

    /**
     * Configuration keys matching AdapterConfigPreference.ConfigKey.
     * Used to identify which settings to send for delta configuration.
     */
    object ConfigKey {
        const val AUDIO_SOURCE = "audio_source"
        const val SAMPLE_RATE = "sample_rate"
        const val MIC_SOURCE = "mic_source"
        const val WIFI_BAND = "wifi_band"
        const val CALL_QUALITY = "call_quality"
    }

    /**
     * Generate initialization messages based on init mode and pending changes.
     *
     * @param config Adapter configuration with all current values
     * @param initMode The initialization mode (FULL, MINIMAL_PLUS_CHANGES, MINIMAL_ONLY)
     * @param pendingChanges Set of config keys that have changed since last init
     * @return List of serialized messages to send to the adapter
     */
    fun generateInitSequence(
        config: AdapterConfig,
        initMode: String,
        pendingChanges: Set<String> = emptySet(),
    ): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()

        // === MINIMAL CONFIG: Always sent (every session) ===
        // - DPI: stored in /tmp/ which is cleared on adapter power cycle
        // - Open: display dimensions may change between sessions
        // - WiFi Enable: required to activate wireless mode
        messages.add(serializeNumber(config.dpi, FileAddress.DPI))
        messages.add(serializeOpen(config))
        messages.add(serializeCommand(CommandMapping.WIFI_ENABLE))

        when (initMode) {
            "MINIMAL_ONLY" -> {
                // Just minimal - adapter retains all other settings
                return messages
            }

            "MINIMAL_PLUS_CHANGES" -> {
                // Add only the changed settings
                addChangedSettings(messages, config, pendingChanges)
                return messages
            }

            else -> {
                // FULL - add all settings
                addFullSettings(messages, config)
                return messages
            }
        }
    }

    /**
     * Add messages for only the changed settings.
     */
    private fun addChangedSettings(
        messages: MutableList<ByteArray>,
        config: AdapterConfig,
        pendingChanges: Set<String>,
    ) {
        for (key in pendingChanges) {
            when (key) {
                ConfigKey.AUDIO_SOURCE -> {
                    val command =
                        if (config.audioTransferMode) {
                            CommandMapping.AUDIO_TRANSFER_ON
                        } else {
                            CommandMapping.AUDIO_TRANSFER_OFF
                        }
                    messages.add(serializeCommand(command))
                }

                ConfigKey.SAMPLE_RATE -> {
                    // Sample rate is part of BoxSettings, need to send full BoxSettings
                    messages.add(serializeBoxSettings(config))
                }

                ConfigKey.MIC_SOURCE -> {
                    val command =
                        if (config.micType == "box") {
                            CommandMapping.BOX_MIC
                        } else {
                            CommandMapping.MIC
                        }
                    messages.add(serializeCommand(command))
                }

                ConfigKey.WIFI_BAND -> {
                    val command =
                        if (config.wifiType == "5ghz") {
                            CommandMapping.WIFI_5G
                        } else {
                            CommandMapping.WIFI_24G
                        }
                    messages.add(serializeCommand(command))
                }

                ConfigKey.CALL_QUALITY -> {
                    // Call quality is part of BoxSettings, need to send full BoxSettings
                    messages.add(serializeBoxSettings(config))
                }
            }
        }
    }

    /**
     * Add all settings for full initialization.
     */
    private fun addFullSettings(
        messages: MutableList<ByteArray>,
        config: AdapterConfig,
    ) {
        // Box name
        messages.add(serializeString(config.boxName, FileAddress.BOX_NAME))

        // AirPlay configuration
        messages.add(serializeString(generateAirplayConfig(config), FileAddress.AIRPLAY_CONFIG))

        // Upload icons if provided
        config.icon120Data?.let { messages.add(serializeFile(FileAddress.ICON_120.path, it)) }
        config.icon180Data?.let { messages.add(serializeFile(FileAddress.ICON_180.path, it)) }
        config.icon256Data?.let { messages.add(serializeFile(FileAddress.ICON_256.path, it)) }

        // WiFi band selection
        val wifiCommand = if (config.wifiType == "5ghz") CommandMapping.WIFI_5G else CommandMapping.WIFI_24G
        messages.add(serializeCommand(wifiCommand))

        // Box settings JSON (includes sample rate, call quality)
        messages.add(serializeBoxSettings(config))

        // Microphone source
        val micCommand = if (config.micType == "box") CommandMapping.BOX_MIC else CommandMapping.MIC
        messages.add(serializeCommand(micCommand))

        // Audio transfer mode
        val audioTransferCommand = if (config.audioTransferMode) CommandMapping.AUDIO_TRANSFER_ON else CommandMapping.AUDIO_TRANSFER_OFF
        messages.add(serializeCommand(audioTransferCommand))

        // Android work mode (if enabled)
        if (config.androidWorkMode) {
            messages.add(serializeBoolean(true, FileAddress.ANDROID_WORK_MODE))
        }
    }

    /**
     * Generate the initialization message sequence.
     *
     * @param config Adapter configuration
     * @param useMinimalConfig When true, only sends essential messages (DPI, Open, WiFi Enable).
     *                         Use for subsequent sessions where adapter already has persisted config.
     *                         When false, sends full configuration including files and settings.
     * @deprecated Use generateInitSequence(config, initMode, pendingChanges) instead
     */
    fun generateInitSequence(
        config: AdapterConfig,
        useMinimalConfig: Boolean = false,
    ): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()

        // === MINIMAL CONFIG: Only essential messages ===
        // These are required every session:
        // - DPI: stored in /tmp/ which is cleared on adapter power cycle
        // - Open: display dimensions may change between sessions
        // - WiFi Enable: required to activate wireless mode

        // DPI configuration (always needed - /tmp/ is volatile)
        messages.add(serializeNumber(config.dpi, FileAddress.DPI))

        // Open command with resolution/format (always needed)
        messages.add(serializeOpen(config))

        // Enable WiFi (always needed to activate wireless mode)
        messages.add(serializeCommand(CommandMapping.WIFI_ENABLE))

        if (useMinimalConfig) {
            // Minimal config: adapter retains all other settings from /etc/riddle.conf
            return messages
        }

        // === FULL CONFIG: All settings (first install or reset) ===

        // Box name
        messages.add(serializeString(config.boxName, FileAddress.BOX_NAME))

        // AirPlay configuration
        messages.add(serializeString(generateAirplayConfig(config), FileAddress.AIRPLAY_CONFIG))

        // Upload icons if provided
        config.icon120Data?.let { iconData ->
            messages.add(serializeFile(FileAddress.ICON_120.path, iconData))
        }
        config.icon180Data?.let { iconData ->
            messages.add(serializeFile(FileAddress.ICON_180.path, iconData))
        }
        config.icon256Data?.let { iconData ->
            messages.add(serializeFile(FileAddress.ICON_256.path, iconData))
        }

        // WiFi band selection
        val wifiCommand =
            if (config.wifiType == "5ghz") {
                CommandMapping.WIFI_5G
            } else {
                CommandMapping.WIFI_24G
            }
        messages.add(serializeCommand(wifiCommand))

        // Box settings JSON
        messages.add(serializeBoxSettings(config))

        // Microphone source
        val micCommand =
            if (config.micType == "box") {
                CommandMapping.BOX_MIC
            } else {
                CommandMapping.MIC
            }
        messages.add(serializeCommand(micCommand))

        // Audio transfer mode
        // true = bluetooth (AUDIO_TRANSFER_ON)
        // false = adapter/USB (AUDIO_TRANSFER_OFF) - default
        val fullInitAudioCommand =
            if (config.audioTransferMode) {
                CommandMapping.AUDIO_TRANSFER_ON
            } else {
                CommandMapping.AUDIO_TRANSFER_OFF
            }
        messages.add(serializeCommand(fullInitAudioCommand))

        // Android work mode (if enabled)
        if (config.androidWorkMode) {
            messages.add(serializeBoolean(true, FileAddress.ANDROID_WORK_MODE))
        }

        return messages
    }
}
