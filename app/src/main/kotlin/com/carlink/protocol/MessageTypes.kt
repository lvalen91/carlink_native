package com.carlink.protocol

// CPC200-CCPA Protocol Message Types and Constants
//
// This file defines all protocol enums, constants, and data classes for
// communicating with Carlinkit wireless CarPlay/Android Auto adapters.
//
// Ported from: lib/common.dart

/** Magic number for protocol message headers. */
const val PROTOCOL_MAGIC: Int = 0x55aa55aa

/** Header size in bytes */
const val HEADER_SIZE: Int = 16

/**
 * Command mappings for CPC200-CCPA protocol.
 * Used for sending control commands to the adapter.
 */
enum class CommandMapping(
    val id: Int,
) {
    INVALID(0),
    START_RECORD_AUDIO(1),
    STOP_RECORD_AUDIO(2),
    REQUEST_HOST_UI(3),
    SIRI(5),
    MIC(7),
    BOX_MIC(15),
    ENABLE_NIGHT_MODE(16),
    DISABLE_NIGHT_MODE(17),
    WIFI_24G(24),
    WIFI_5G(25),
    LEFT(100),
    RIGHT(101),
    FRAME(12),
    AUDIO_TRANSFER_ON(22),
    AUDIO_TRANSFER_OFF(23),
    SELECT_DOWN(104),
    SELECT_UP(105),
    BACK(106),
    DOWN(114),
    HOME(200),
    PLAY(201),
    PAUSE(202),
    NEXT(204),
    PREV(205),
    REQUEST_VIDEO_FOCUS(500),
    RELEASE_VIDEO_FOCUS(501),
    WIFI_ENABLE(1000),
    AUTO_CONNECT_ENABLE(1001),
    WIFI_CONNECT(1002),
    SCANNING_DEVICE(1003),
    DEVICE_FOUND(1004),
    DEVICE_NOT_FOUND(1005),
    CONNECT_DEVICE_FAILED(1006),
    BT_CONNECTED(1007),
    BT_DISCONNECTED(1008),
    WIFI_CONNECTED(1009),
    PROJECTION_DISCONNECTED(1010),
    BT_PAIR_START(1011),
    WIFI_PAIR(1012),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): CommandMapping = idMap[id] ?: INVALID
    }
}

/**
 * Message type identifiers for CPC200-CCPA protocol.
 */
enum class MessageType(
    val id: Int,
) {
    OPEN(0x01),
    PLUGGED(0x02),
    PHASE(0x03),
    UNPLUGGED(0x04),
    TOUCH(0x05),
    VIDEO_DATA(0x06),
    AUDIO_DATA(0x07),
    COMMAND(0x08),
    LOGO_TYPE(0x09),
    DISCONNECT_PHONE(0x0F),
    CLOSE_ADAPTR(0x15),
    BLUETOOTH_ADDRESS(0x0A),
    BLUETOOTH_PIN(0x0C),
    BLUETOOTH_DEVICE_NAME(0x0D),
    WIFI_DEVICE_NAME(0x0E),
    BLUETOOTH_PAIRED_LIST(0x12),
    MANUFACTURER_INFO(0x14),
    MULTI_TOUCH(0x17),
    HI_CAR_LINK(0x18),
    BOX_SETTINGS(0x19),
    NETWORK_MAC_ADDRESS(0x23),
    NETWORK_MAC_ADDRESS_ALT(0x24),
    MEDIA_DATA(0x2A),
    SEND_FILE(0x99),
    HEARTBEAT(0xAA),
    SOFTWARE_VERSION(0xCC),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MessageType = idMap[id] ?: UNKNOWN
    }
}

/**
 * Audio command identifiers for stream control.
 */
enum class AudioCommand(
    val id: Int,
) {
    AUDIO_OUTPUT_START(1),
    AUDIO_OUTPUT_STOP(2),
    AUDIO_INPUT_CONFIG(3),
    AUDIO_PHONECALL_START(4),
    AUDIO_PHONECALL_STOP(5),
    AUDIO_NAVI_START(6),
    AUDIO_NAVI_STOP(7),
    AUDIO_SIRI_START(8),
    AUDIO_SIRI_STOP(9),
    AUDIO_MEDIA_START(10),
    AUDIO_MEDIA_STOP(11),
    AUDIO_ALERT_START(12),
    AUDIO_ALERT_STOP(13),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): AudioCommand = idMap[id] ?: UNKNOWN
    }
}

/**
 * File addresses for adapter configuration files.
 */
enum class FileAddress(
    val path: String,
) {
    DPI("/tmp/screen_dpi"),
    NIGHT_MODE("/tmp/night_mode"),
    BOX_NAME("/etc/box_name"),
    AIRPLAY_CONFIG("/etc/airplay.conf"),
    ANDROID_WORK_MODE("/etc/android_work_mode"),
    OEM_ICON("/etc/oem_icon.png"),
    ICON_120("/etc/icon_120x120.png"),
    ICON_180("/etc/icon_180x180.png"),
    ICON_256("/etc/icon_256x256.png"),
    UNKNOWN(""),
    ;

    companion object {
        private val pathMap = entries.associateBy { it.path }

        fun fromPath(path: String): FileAddress = pathMap[path] ?: UNKNOWN
    }
}

/**
 * Phone type identifiers for connected devices.
 */
enum class PhoneType(
    val id: Int,
) {
    ANDROID_MIRROR(1),
    CARPLAY(3),
    IPHONE_MIRROR(4),
    ANDROID_AUTO(5),
    HI_CAR(6),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): PhoneType = idMap[id] ?: UNKNOWN
    }
}

/**
 * Media type identifiers for media metadata messages.
 */
enum class MediaType(
    val id: Int,
) {
    DATA(1),
    ALBUM_COVER(3),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MediaType = idMap[id] ?: UNKNOWN
    }
}

/**
 * Touch action types for single-touch input.
 */
enum class TouchAction(
    val id: Int,
) {
    DOWN(14),
    MOVE(15),
    UP(16),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): TouchAction = idMap[id] ?: UNKNOWN
    }
}

/**
 * Multi-touch action types.
 */
enum class MultiTouchAction(
    val id: Int,
) {
    DOWN(1),
    MOVE(2),
    UP(0),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MultiTouchAction = idMap[id] ?: UNKNOWN
    }
}

/**
 * Audio format configuration based on decode type.
 */
data class AudioFormat(
    val frequency: Int,
    val channels: Int,
    val bitrate: Int,
)

/**
 * Decode type to audio format mapping.
 */
object AudioFormats {
    private val formats =
        mapOf(
            1 to AudioFormat(44100, 2, 16),
            2 to AudioFormat(44100, 2, 16),
            3 to AudioFormat(8000, 1, 16),
            4 to AudioFormat(48000, 2, 16),
            5 to AudioFormat(16000, 1, 16),
            6 to AudioFormat(24000, 1, 16),
            7 to AudioFormat(16000, 2, 16),
        )

    fun fromDecodeType(decodeType: Int): AudioFormat? = formats[decodeType]
}

/**
 * Known Carlinkit USB device identifiers.
 */
object KnownDevices {
    val DEVICES =
        listOf(
            Pair(0x1314, 0x1520),
            Pair(0x1314, 0x1521),
            Pair(0x08E4, 0x01C0), // Alternate
        )

    fun isKnownDevice(
        vendorId: Int,
        productId: Int,
    ): Boolean = DEVICES.any { it.first == vendorId && it.second == productId }
}

/**
 * Adapter configuration for protocol initialization.
 *
 * Note on nullable configuration options:
 * - null = not configured by user, command will NOT be sent to adapter
 * - non-null = user explicitly configured, command WILL be sent to adapter
 *
 * This allows the adapter to retain its current settings for unconfigured options,
 * since the adapter firmware persists most settings through power cycles.
 */
data class AdapterConfig(
    var width: Int = 1920,
    var height: Int = 720,
    var fps: Int = 60,
    var dpi: Int = 160,
    val format: Int = 5,
    val iBoxVersion: Int = 2,
    val packetMax: Int = 49152,
    val phoneWorkMode: Int = 2,
    /** Night mode: false=light theme, true=dark theme for CarPlay display */
    val nightMode: Boolean = false,
    val boxName: String = "carlink",
    val mediaDelay: Int = 300,
    /** Audio transfer mode: true=bluetooth, false=adapter (USB audio, default) */
    val audioTransferMode: Boolean = false,
    /** Sample rate for media audio: 44100 or 48000 Hz. Controls mediaSound in BoxSettings. */
    val sampleRate: Int = 48000,
    val wifiType: String = "5ghz",
    val micType: String = "os",
    /** Call quality: 0=normal, 1=clear, 2=HD. Sent in BoxSettings. */
    val callQuality: Int = 2,
    val oemIconVisible: Boolean = true,
    val androidWorkMode: Boolean = false,
    val icon120Data: ByteArray? = null,
    val icon180Data: ByteArray? = null,
    val icon256Data: ByteArray? = null,
) {
    companion object {
        val DEFAULT = AdapterConfig()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AdapterConfig
        return width == other.width &&
            height == other.height &&
            fps == other.fps &&
            dpi == other.dpi &&
            format == other.format &&
            boxName == other.boxName &&
            icon120Data.contentEquals(other.icon120Data) &&
            icon180Data.contentEquals(other.icon180Data) &&
            icon256Data.contentEquals(other.icon256Data)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + fps
        result = 31 * result + dpi
        result = 31 * result + boxName.hashCode()
        result = 31 * result + (icon120Data?.contentHashCode() ?: 0)
        result = 31 * result + (icon180Data?.contentHashCode() ?: 0)
        result = 31 * result + (icon256Data?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Message header for CPC200-CCPA protocol.
 */
data class MessageHeader(
    val length: Int,
    val type: MessageType,
) {
    override fun toString(): String = "MessageHeader(length=$length, type=${type.name})"
}

/**
 * Exception thrown when message header parsing fails.
 */
class HeaderParseException(
    message: String,
) : Exception(message)
