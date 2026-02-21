package com.carlink.protocol

import com.carlink.audio.AudioFormats
import com.carlink.logging.logWarn
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * CPC200-CCPA Protocol Message Parser
 *
 * Parses binary messages received from the Carlinkit adapter into typed message objects.
 * Handles header validation, payload extraction, and type-specific parsing.
 */
object MessageParser {
    /**
     * Parse a 16-byte header from raw bytes.
     *
     * @param data Raw header bytes (must be exactly 16 bytes)
     * @return Parsed MessageHeader
     * @throws HeaderParseException if header is invalid
     */
    fun parseHeader(data: ByteArray): MessageHeader {
        if (data.size != HEADER_SIZE) {
            throw HeaderParseException("Invalid buffer size - Expecting $HEADER_SIZE, got ${data.size}")
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != PROTOCOL_MAGIC) {
            throw HeaderParseException("Invalid magic number, received 0x${magic.toString(16)}")
        }

        val length = buffer.int
        val typeInt = buffer.int
        val msgType = MessageType.fromId(typeInt)

        if (msgType != MessageType.UNKNOWN) {
            val typeCheck = buffer.int
            val expectedCheck = (typeInt.inv()) and 0xFFFFFFFF.toInt()
            if (typeCheck != expectedCheck) {
                throw HeaderParseException("Invalid type check, received 0x${typeCheck.toString(16)}")
            }
        }

        return MessageHeader(length, msgType, rawType = typeInt)
    }

    /**
     * Parse a complete message from header and payload.
     *
     * @param header Parsed message header
     * @param payload Message payload bytes (can be null for some message types)
     * @return Parsed Message object
     */
    fun parseMessage(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message =
        when (header.type) {
            MessageType.AUDIO_DATA -> parseAudioData(header, payload)
            MessageType.MEDIA_DATA -> parseMediaData(header, payload)
            MessageType.COMMAND -> parseCommand(header, payload)
            MessageType.PLUGGED -> parsePlugged(header, payload)
            MessageType.UNPLUGGED -> UnpluggedMessage(header)
            MessageType.BOX_SETTINGS -> parseBoxSettings(header, payload)
            MessageType.PEER_BLUETOOTH_ADDRESS -> parsePeerBluetoothAddress(header, payload)

            // Inbound diagnostic messages — parsed for logging and future use
            MessageType.PHASE -> parsePhase(header, payload)
            MessageType.SOFTWARE_VERSION -> parseStringPayload(header, payload, "SoftwareVersion")
            MessageType.BLUETOOTH_DEVICE_NAME -> parseStringPayload(header, payload, "BluetoothDeviceName")
            MessageType.WIFI_DEVICE_NAME -> parseStringPayload(header, payload, "WifiDeviceName")
            MessageType.BLUETOOTH_ADDRESS -> parseStringPayload(header, payload, "BluetoothAddress")
            MessageType.BLUETOOTH_PIN -> parseStringPayload(header, payload, "BluetoothPin")
            MessageType.BLUETOOTH_PAIRED_LIST -> parseStringPayload(header, payload, "BluetoothPairedList")
            MessageType.MANUFACTURER_INFO -> parseStringPayload(header, payload, "ManufacturerInfo")
            MessageType.STATUS_VALUE -> parseStatusValue(header, payload)
            MessageType.SESSION_TOKEN -> SessionTokenMessage(header, header.length)
            MessageType.NAVI_FOCUS_REQUEST -> NaviFocusMessage(header, isRequest = true)
            MessageType.NAVI_FOCUS_RELEASE -> NaviFocusMessage(header, isRequest = false)
            MessageType.PEER_BLUETOOTH_ADDRESS_ALT -> parsePeerBluetoothAddress(header, payload)
            MessageType.LOGO_TYPE -> parseIntPayload(header, payload, "LogoType")
            MessageType.HI_CAR_LINK -> parseStringPayload(header, payload, "HiCarLink")
            MessageType.UI_HIDE_PEER_INFO -> InfoMessage(header, "UiHidePeerInfo")
            MessageType.UI_BRING_TO_FOREGROUND -> InfoMessage(header, "UiBringToForeground")
            MessageType.REMOTE_CX_CY -> parseHexPayload(header, payload, "RemoteCxCy")
            MessageType.EXTENDED_MFG_INFO -> parseStringPayload(header, payload, "ExtendedMfgInfo")
            MessageType.REMOTE_DISPLAY -> parseHexPayload(header, payload, "RemoteDisplay")
            MessageType.OPEN -> parseOpenEcho(header, payload)

            // Additional adapter→host messages (firmware binary analysis)
            MessageType.CARPLAY_CONTROL -> parseHexPayload(header, payload, "CarPlayControl")
            MessageType.DASHBOARD_DATA -> parseHexPayload(header, payload, "DashboardData")
            MessageType.WIFI_STATUS_DATA -> parseHexPayload(header, payload, "WiFiStatusData")
            MessageType.DISK_INFO -> parseStringPayload(header, payload, "DiskInfo")
            MessageType.DEVICE_EXTENDED_INFO -> parseHexPayload(header, payload, "DeviceExtendedInfo")
            MessageType.FACTORY_SETTING -> InfoMessage(header, "FactorySetting")
            MessageType.ADAPTER_IDLE -> InfoMessage(header, "AdapterIdle")
            MessageType.HEARTBEAT_ECHO -> InfoMessage(header, "HeartbeatEcho")
            MessageType.ERROR_REPORT -> parseHexPayload(header, payload, "ErrorReport")
            MessageType.UPDATE_PROGRESS -> parseIntPayload(header, payload, "UpdateProgress")
            MessageType.DEBUG_TRACE -> parseHexPayload(header, payload, "DebugTrace")

            // Navigation video — handled via direct video path in UsbDeviceWrapper,
            // but if it arrives here (no videoProcessor), parse as info
            MessageType.NAVI_VIDEO_DATA -> InfoMessage(header, "NaviVideoData", "${header.length}B")

            else -> UnknownMessage(header)
        }

    private fun parseAudioData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 12) {
            return UnknownMessage(header)
        }

        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)

        val decodeType = buffer.int
        val volume = buffer.float
        val audioType = buffer.int

        val remainingBytes = header.length - 12

        return when {
            remainingBytes == 1 -> {
                val commandId = payload[12].toInt() and 0xFF
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = AudioCommand.fromId(commandId),
                    data = null,
                    volumeDuration = null,
                )
            }

            remainingBytes == 4 -> {
                buffer.position(12)
                val duration = buffer.float
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = null,
                    volumeDuration = duration,
                )
            }

            remainingBytes > 0 -> {
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = payload,
                    volumeDuration = null,
                    audioDataOffset = 12,
                    audioDataLength = remainingBytes,
                )
            }

            else -> {
                AudioDataMessage(
                    header = header,
                    decodeType = decodeType,
                    volume = volume,
                    audioType = audioType,
                    command = null,
                    data = null,
                    volumeDuration = null,
                )
            }
        }
    }

    private fun parseMediaData(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) {
            return MediaDataMessage(header, MediaType.UNKNOWN, emptyMap())
        }

        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        val typeInt = buffer.int
        val mediaType = MediaType.fromId(typeInt)

        val mediaPayload: Map<String, Any> =
            when (mediaType) {
                MediaType.ALBUM_COVER -> {
                    val imageData = ByteArray(header.length - 4)
                    System.arraycopy(payload, 4, imageData, 0, imageData.size)
                    mapOf("AlbumCover" to imageData)
                }

                MediaType.DATA, MediaType.NAVI_JSON -> {
                    if (header.length < 6) {
                        // Need at least: 4 (type int) + 1 (JSON byte) + 1 (trailing null)
                        emptyMap()
                    } else try {
                        val jsonBytes = ByteArray(header.length - 5) // Exclude type int and trailing null
                        System.arraycopy(payload, 4, jsonBytes, 0, jsonBytes.size)
                        val jsonString = String(jsonBytes, StandardCharsets.UTF_8).trim('\u0000')
                        val json = JSONObject(jsonString)
                        json.keys().asSequence().associateWith { json.get(it) }
                    } catch (e: JSONException) {
                        logWarn("[MessageParser] Failed to parse media metadata JSON: ${e.message}")
                        emptyMap()
                    }
                }

                else -> {
                    emptyMap()
                }
            }

        return MediaDataMessage(header, mediaType, mediaPayload)
    }

    private fun parseCommand(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) {
            return CommandMessage(header, CommandMapping.INVALID, rawId = -1)
        }
        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        val commandId = buffer.int
        return CommandMessage(header, CommandMapping.fromId(commandId), rawId = commandId)
    }

    private fun parsePlugged(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 4) {
            return PluggedMessage(header, PhoneType.UNKNOWN, null)
        }
        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        val phoneTypeId = buffer.int
        val phoneType = PhoneType.fromId(phoneTypeId)

        val wifi =
            if (header.length >= 8) {
                buffer.int
            } else {
                null
            }

        return PluggedMessage(header, phoneType, wifi)
    }

    private fun parseBoxSettings(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 2) {
            return UnknownMessage(header)
        }

        // Payload is null-terminated JSON string
        val jsonString = String(payload, 0, header.length, StandardCharsets.UTF_8).trim('\u0000')
        if (jsonString.isEmpty()) {
            return UnknownMessage(header)
        }

        return try {
            val json = JSONObject(jsonString)
            // Discriminate: second BoxSettings has MDModel (phone info)
            val isPhoneInfo = json.has("MDModel")
            BoxSettingsMessage(header, json, isPhoneInfo)
        } catch (e: JSONException) {
            logWarn("[MessageParser] Failed to parse BoxSettings JSON: ${e.message}")
            UnknownMessage(header)
        }
    }

    private fun parsePeerBluetoothAddress(
        header: MessageHeader,
        payload: ByteArray?,
    ): Message {
        if (payload == null || header.length < 17) {
            return UnknownMessage(header)
        }

        // Payload is 17 bytes ASCII MAC "XX:XX:XX:XX:XX:XX" (may be null-terminated)
        val macAddress = String(payload, 0, 17, StandardCharsets.UTF_8).trim('\u0000')
        return PeerBluetoothAddressMessage(header, macAddress)
    }

    // ==================== Diagnostic Parsers ====================

    private fun parsePhase(header: MessageHeader, payload: ByteArray?): Message {
        if (payload == null || header.length < 4) return PhaseMessage(header, -1)
        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        return PhaseMessage(header, buffer.int)
    }

    private fun parseStatusValue(header: MessageHeader, payload: ByteArray?): Message {
        if (payload == null || header.length < 4) return StatusValueMessage(header, -1)
        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        return StatusValueMessage(header, buffer.int)
    }

    private fun parseStringPayload(header: MessageHeader, payload: ByteArray?, label: String): Message {
        if (payload == null || header.length < 1) return InfoMessage(header, label, "")
        val str = String(payload, 0, header.length, StandardCharsets.UTF_8).trim('\u0000')
        return InfoMessage(header, label, str)
    }

    private fun parseIntPayload(header: MessageHeader, payload: ByteArray?, label: String): Message {
        if (payload == null || header.length < 4) return InfoMessage(header, label, "${header.length}B")
        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        return InfoMessage(header, label, "${buffer.int}")
    }

    private fun parseHexPayload(header: MessageHeader, payload: ByteArray?, label: String): Message {
        if (payload == null || header.length < 1) return InfoMessage(header, label, "0B")
        val hex = payload.take(header.length.coerceAtMost(32))
            .joinToString(" ") { "%02X".format(it) }
        val suffix = if (header.length > 32) "... (${header.length}B)" else ""
        return InfoMessage(header, label, "$hex$suffix")
    }

    private fun parseOpenEcho(header: MessageHeader, payload: ByteArray?): Message {
        if (payload == null || header.length < 8) return InfoMessage(header, "OpenEcho", "${header.length}B")
        val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
        val w = buffer.int
        val h = buffer.int
        return InfoMessage(header, "OpenEcho", "${w}x${h}")
    }

}

// ==================== Message Classes ====================

/**
 * Base class for all protocol messages.
 */
sealed class Message(
    val header: MessageHeader,
) {
    override fun toString(): String = "Message(type=${header.type})"
}

/**
 * Unknown or unhandled message type.
 */
class UnknownMessage(
    header: MessageHeader,
) : Message(header) {
    override fun toString(): String = "UnknownMessage(type=0x${header.rawType.toString(16)}, ${header.length}B)"
}

/**
 * Command message from adapter.
 */
class CommandMessage(
    header: MessageHeader,
    val command: CommandMapping,
    /** Raw command ID from the wire — preserved even when command maps to INVALID */
    val rawId: Int = command.id,
) : Message(header) {
    override fun toString(): String =
        if (command == CommandMapping.INVALID) "Command(unknown id=$rawId / 0x${rawId.toString(16)})"
        else "Command(${command.name})"
}

/**
 * Device plugged notification.
 */
class PluggedMessage(
    header: MessageHeader,
    val phoneType: PhoneType,
    val wifi: Int?,
) : Message(header) {
    override fun toString(): String = "Plugged(phoneType=${phoneType.name}, wifi=$wifi)"
}

/**
 * Device unplugged notification.
 */
class UnpluggedMessage(
    header: MessageHeader,
) : Message(header) {
    override fun toString(): String = "Unplugged"
}

/**
 * Audio data message with PCM samples or command.
 */
class AudioDataMessage(
    header: MessageHeader,
    val decodeType: Int,
    val volume: Float,
    val audioType: Int,
    val command: AudioCommand?,
    val data: ByteArray?,
    val volumeDuration: Float?,
    val audioDataOffset: Int = 0,
    val audioDataLength: Int = data?.size ?: 0,
) : Message(header) {
    override fun toString(): String {
        val format = AudioFormats.fromDecodeType(decodeType)
        val formatInfo = "${format.sampleRate}Hz ${format.channelCount}ch"
        return when {
            command != null -> "AudioData(command=${command.name})"
            volumeDuration != null -> "AudioData(volumeDuration=$volumeDuration)"
            else -> "AudioData(format=$formatInfo, audioType=$audioType, bytes=$audioDataLength)"
        }
    }
}

/**
 * Media metadata message.
 */
class MediaDataMessage(
    header: MessageHeader,
    val type: MediaType,
    val payload: Map<String, Any>,
) : Message(header) {
    override fun toString(): String = "MediaData(type=${type.name})"
}

/**
 * Video streaming signal (synthetic, not from protocol).
 * Indicates that video data is being streamed directly to the renderer.
 * Used when video data bypasses message parsing for zero-copy performance.
 */
object VideoStreamingSignal : Message(MessageHeader(0, MessageType.VIDEO_DATA)) {
    override fun toString(): String = "VideoStreamingSignal"
}

/**
 * BoxSettings message from adapter (0x19).
 * Two forms: adapter info (has DevList) and phone info (has MDModel/btMacAddr).
 */
class BoxSettingsMessage(
    header: MessageHeader,
    val json: JSONObject,
    val isPhoneInfo: Boolean,
) : Message(header) {
    override fun toString(): String =
        if (isPhoneInfo) "BoxSettings(phone: ${json.optString("MDModel", "?")})"
        else "BoxSettings(adapter: ${json.optString("boxType", "?")})"
}

/**
 * Peer Bluetooth address from adapter (0x23).
 * Contains the connected phone's BT MAC address.
 */
class PeerBluetoothAddressMessage(
    header: MessageHeader,
    val macAddress: String,
) : Message(header) {
    override fun toString(): String = "PeerBluetoothAddress($macAddress)"
}

// ==================== Diagnostic Message Classes ====================
// These are parsed for logging/correlation and potential future use.
// The existing [RECV] log in AdapterDriver prints toString() for each.

/**
 * Connection phase update (0x03).
 * Phase 0=terminated, 7=connecting, 8=streaming, 13=negotiation_failed.
 * NOTE: Phase 0 is a session termination signal (alternative to UNPLUGGED).
 * NOTE: Phase 13 indicates AirPlay session negotiation failed (e.g., viewArea/safeArea constraint violation).
 */
class PhaseMessage(
    header: MessageHeader,
    val phase: Int,
) : Message(header) {
    val phaseName: String
        get() = when (phase) {
            0 -> "terminated"
            7 -> "connecting"
            8 -> "streaming"
            13 -> "negotiation_failed"
            else -> "unknown"
        }

    override fun toString(): String = "Phase($phase=$phaseName)"
}

/**
 * Status/config value from adapter (0xBB).
 */
class StatusValueMessage(
    header: MessageHeader,
    val value: Int,
) : Message(header) {
    override fun toString(): String = "StatusValue($value / 0x${value.toString(16)})"
}

/**
 * Encrypted session telemetry (0xA3).
 * AES-128-CBC encrypted JSON with phone/adapter info.
 * Key: SESSION_TOKEN_ENCRYPTION_KEY in MessageTypes.kt.
 */
class SessionTokenMessage(
    header: MessageHeader,
    val payloadSize: Int,
) : Message(header) {
    override fun toString(): String = "SessionToken(${payloadSize}B encrypted)"
}

/**
 * Navigation focus request/release (0x6E/0x6F).
 * Signals when CarPlay navigation video should start/stop.
 */
class NaviFocusMessage(
    header: MessageHeader,
    val isRequest: Boolean,
) : Message(header) {
    override fun toString(): String = if (isRequest) "NaviFocusRequest" else "NaviFocusRelease"
}

/**
 * Generic informational message for adapter diagnostics.
 * Used for inbound types with string/hex/int payloads that are logged but not actively handled.
 */
class InfoMessage(
    header: MessageHeader,
    val label: String,
    val value: String = "",
) : Message(header) {
    override fun toString(): String = if (value.isNotEmpty()) "$label($value)" else label
}
