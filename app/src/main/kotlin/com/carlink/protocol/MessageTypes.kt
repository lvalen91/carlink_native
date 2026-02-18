package com.carlink.protocol

// CPC200-CCPA Protocol Message Types and Constants
//
// This file defines all protocol enums, constants, and data classes for
// communicating with Carlinkit wireless CarPlay/Android Auto adapters.
//

/** Magic number for protocol message headers. */
const val PROTOCOL_MAGIC: Int = 0x55aa55aa

/** Header size in bytes */
const val HEADER_SIZE: Int = 16

/**
 * USB Communication Encryption Key for SESSION_TOKEN (0xA3) decryption.
 *
 * The SESSION_TOKEN message contains Base64-encoded, AES-128-CBC encrypted
 * telemetry data sent from the adapter during session establishment.
 *
 * Decryption details:
 * - Algorithm: AES-128-CBC
 * - Key: "W2EC1X1NbZ58TXtn" (16 bytes, ASCII)
 * - IV: First 16 bytes of Base64-decoded payload
 * - Ciphertext: Remaining bytes after IV
 * - Content: JSON with phone/box telemetry
 *
 * Decrypted JSON structure:
 * ```json
 * {
 *   "phone": {
 *     "model": "iPhone18,4",      // Device model
 *     "osVer": "23D5103d",        // OS build version
 *     "linkT": "CarPlay",         // Link type (CarPlay, AndroidAuto)
 *     "conSpd": 4,                // Connection speed indicator
 *     "conRate": 0.24,            // Historical success rate
 *     "conNum": 17,               // Total connection attempts
 *     "success": 4                // Successful connections
 *   },
 *   "box": {
 *     "uuid": "651ede98...",      // Adapter unique ID
 *     "model": "A15W",            // Adapter model
 *     "hw": "YMA0-WR2C-0003",     // Hardware revision
 *     "ver": "2025.10.15.1127",   // Firmware version
 *     "mfd": "20240119"           // Manufacturing date (YYYYMMDD)
 *   }
 * }
 * ```
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md
 */
const val SESSION_TOKEN_ENCRYPTION_KEY: String = "W2EC1X1NbZ58TXtn"

/**
 * Command mappings for CPC200-CCPA protocol.
 *
 * Commands are bidirectional - some are sent to adapter (H→A), some received from adapter (A→H),
 * and some are forwarded to/from the connected phone (H→A→P or P→A→H).
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/command_ids.md
 */
enum class CommandMapping(
    val id: Int,
) {
    INVALID(0),

    // === Basic Commands (1-31) ===

    // Microphone Recording (H→A→P)
    START_RECORD_AUDIO(1),  // StartRecordMic - Begin mic recording
    STOP_RECORD_AUDIO(2),   // StopRecordMic - Stop mic recording

    // UI Control (P→A→H)
    REQUEST_HOST_UI(3),     // RequestHostUI - Phone requests host show native UI
    HIDE(14),               // Hide - Phone requests hide/minimize projection

    // Bluetooth Control (H→A, also extended format A→H for BT MAC notification)
    DISABLE_BLUETOOTH(4),   // DisableBluetooth - Disable adapter Bluetooth

    // Siri/Voice Assistant (H→A→P)
    SIRI(5),                // SiriButtonDown - Siri button pressed (initiates Siri)
    SIRI_BUTTON_UP(6),      // SiriButtonUp - Siri button released

    // Microphone Source Selection (H→A)
    MIC(7),                 // UseCarMic - Use car's microphone
    USE_BOX_MIC(8),         // UseBoxMic - Use adapter's built-in microphone
    BOX_MIC(15),            // UseBoxI2SMic - Use adapter's I2S microphone
    USE_PHONE_MIC(21),      // UsePhoneMic - Use phone's microphone

    // Video Control (H→A)
    FRAME(12),              // RequestKeyFrame - Request video IDR frame
    REFRESH_FRAME(26),      // RefreshFrame - Force video frame refresh

    // Night Mode (H→A)
    ENABLE_NIGHT_MODE(16),  // StartNightMode - Enable dark theme
    DISABLE_NIGHT_MODE(17), // StopNightMode - Disable dark theme

    // GPS/GNSS Forwarding (H→A)
    START_GNSS_REPORT(18),  // StartGNSSReport - Start GPS data forwarding to phone
    STOP_GNSS_REPORT(19),   // StopGNSSReport - Stop GPS data forwarding

    // Audio Routing (H→A)
    AUDIO_TRANSFER_ON(22),  // UseBluetoothAudio - Route audio via Bluetooth
    AUDIO_TRANSFER_OFF(23), // UseBoxTransAudio - Route audio via adapter transmitter

    // WiFi Band Selection (H→A)
    WIFI_24G(24),           // Use24GWiFi - Switch to 2.4 GHz WiFi band
    WIFI_5G(25),            // Use5GWiFi - Switch to 5 GHz WiFi band

    // Standby Mode (H→A)
    START_STANDBY_MODE(28), // StartStandbyMode - Enter low-power standby
    STOP_STANDBY_MODE(29),  // StopStandbyMode - Exit standby mode

    // BLE Advertising (H→A)
    START_BLE_ADV(30),      // StartBleAdv - Start BLE advertising for wireless pairing
    STOP_BLE_ADV(31),       // StopBleAdv - Stop BLE advertising

    // === D-Pad Control Commands (100-106) - All H→A→P ===
    LEFT(100),              // CtrlButtonLeft - D-Pad left
    RIGHT(101),             // CtrlButtonRight - D-Pad right
    UP(102),                // CtrlButtonUp - D-Pad up
    DOWN_BUTTON(103),       // CtrlButtonDown - D-Pad down
    SELECT_DOWN(104),       // CtrlButtonEnter - Enter/Select pressed
    SELECT_UP(105),         // CtrlButtonRelease - Button release
    BACK(106),              // CtrlButtonBack - Back button

    // === Rotary Knob Commands (111-114) - All H→A→P ===
    KNOB_LEFT(111),         // CtrlKnobLeft - Knob counter-clockwise
    KNOB_RIGHT(112),        // CtrlKnobRight - Knob clockwise
    KNOB_UP(113),           // CtrlKnobUp - Knob tilt up
    DOWN(114),              // CtrlKnobDown - Knob tilt down (legacy name kept for compatibility)

    // === Media Control Commands (200-205) - All H→A→P ===
    HOME(200),              // MusicACHome - Home button
    PLAY(201),              // MusicPlay - Play
    PAUSE(202),             // MusicPause - Pause
    PLAY_PAUSE(203),        // MusicPlayOrPause - Toggle play/pause
    NEXT(204),              // MusicNext - Next track
    PREV(205),              // MusicPrev - Previous track

    // === Phone Call Commands (300-314) - All H→A→P ===
    PHONE_ANSWER(300),      // PhoneAnswer - Answer incoming call
    PHONE_HANG_UP(301),     // PhoneHungUp - End/reject call
    PHONE_KEY_0(302),       // PhoneKey0 - DTMF tone 0
    PHONE_KEY_1(303),       // PhoneKey1 - DTMF tone 1
    PHONE_KEY_2(304),       // PhoneKey2 - DTMF tone 2
    PHONE_KEY_3(305),       // PhoneKey3 - DTMF tone 3
    PHONE_KEY_4(306),       // PhoneKey4 - DTMF tone 4
    PHONE_KEY_5(307),       // PhoneKey5 - DTMF tone 5
    PHONE_KEY_6(308),       // PhoneKey6 - DTMF tone 6
    PHONE_KEY_7(309),       // PhoneKey7 - DTMF tone 7
    PHONE_KEY_8(310),       // PhoneKey8 - DTMF tone 8
    PHONE_KEY_9(311),       // PhoneKey9 - DTMF tone 9
    PHONE_KEY_STAR(312),    // PhoneKeyStar - DTMF tone *
    PHONE_KEY_POUND(313),   // PhoneKeyPound - DTMF tone #
    PHONE_HOOK_SWITCH(314), // CarPlay_PhoneHookSwitch - Hook switch toggle

    // === Android Auto Focus Commands (500-507) ===
    REQUEST_VIDEO_FOCUS(500),       // RequestVideoFocus (A→H) - Adapter requests host show video
    RELEASE_VIDEO_FOCUS(501),       // ReleaseVideoFocus (A→H) - Adapter releases video focus
    AA_UNKNOWN_502(502),            // Android Auto related (unverified)
    AA_UNKNOWN_503(503),            // Android Auto related (unverified)
    REQUEST_AUDIO_FOCUS_DUCK(504),  // RequestAudioFocusDuck (A→H) - Request audio ducking
    RELEASE_AUDIO_FOCUS(505),       // ReleaseAudioFocus (A→H) - Release audio focus
    REQUEST_NAVI_FOCUS(506),        // RequestNaviFocus (A→H) - Request navigation audio focus
    RELEASE_NAVI_FOCUS(507),        // ReleaseNaviFocus (A→H) - Release navigation focus

    // === Connection Status Commands (1000-1013) ===

    // Host → Adapter
    WIFI_ENABLE(1000),          // SupportWifi - Enable WiFi mode
    AUTO_CONNECT_ENABLE(1001),  // SupportAutoConnect - Enable auto-connect
    WIFI_CONNECT(1002),         // StartAutoConnect - Start auto-connect scan
    WIFI_PAIR(1012),            // WiFiPair - Enter WiFi pairing mode
    GET_BT_ONLINE_LIST(1013),   // GetBluetoothOnlineList - Request BT device list

    // Adapter → Host (Status Notifications)
    SCANNING_DEVICE(1003),      // ScaningDevices - Adapter scanning for devices
    DEVICE_FOUND(1004),         // DeviceFound - Device found during scan
    DEVICE_NOT_FOUND(1005),     // DeviceNotFound - No device found
    CONNECT_DEVICE_FAILED(1006), // DeviceConnectFailed - Connection attempt failed
    BT_CONNECTED(1007),         // DeviceBluetoothConnected - Bluetooth connected
    BT_DISCONNECTED(1008),      // DeviceBluetoothNotConnected - Bluetooth disconnected
    WIFI_CONNECTED(1009),       // DeviceWifiConnected - WiFi hotspot: phone connected
    WIFI_DISCONNECTED(1010),    // DeviceWifiNotConnected - WiFi hotspot: no phone (NOT session end!)
    BT_PAIR_START(1011),        // DeviceBluetoothPairStart - Bluetooth pairing started
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): CommandMapping = idMap[id] ?: INVALID
    }
}

/**
 * Message type identifiers for CPC200-CCPA protocol.
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md
 */
enum class MessageType(
    val id: Int,
) {
    // Session Management
    OPEN(0x01),
    PLUGGED(0x02),
    PHASE(0x03),
    UNPLUGGED(0x04),
    DISCONNECT_PHONE(0x0F),
    CLOSE_DONGLE(0x15),
    HEARTBEAT(0xAA),

    // Data Streams
    TOUCH(0x05),
    VIDEO_DATA(0x06),
    AUDIO_DATA(0x07),
    MULTI_TOUCH(0x17),
    NAVI_VIDEO_DATA(0x2C), // Navigation video (iOS 13+), same structure as VIDEO_DATA

    // Control Commands
    COMMAND(0x08),
    LOGO_TYPE(0x09),

    // Device Information
    BLUETOOTH_ADDRESS(0x0A),
    BLUETOOTH_PIN(0x0C),
    BLUETOOTH_DEVICE_NAME(0x0D),
    WIFI_DEVICE_NAME(0x0E),
    BLUETOOTH_PAIRED_LIST(0x12),
    MANUFACTURER_INFO(0x14),
    SOFTWARE_VERSION(0xCC),
    STATUS_VALUE(0xBB), // Status/config value

    // Configuration
    BOX_SETTINGS(0x19),
    SEND_FILE(0x99),

    // Peer Device Info
    HI_CAR_LINK(0x18),
    PEER_BLUETOOTH_ADDRESS(0x23),
    PEER_BLUETOOTH_ADDRESS_ALT(0x24),
    UI_HIDE_PEER_INFO(0x25),
    UI_BRING_TO_FOREGROUND(0x26),

    // Media Metadata
    MEDIA_DATA(0x2A),

    // Session Establishment
    SESSION_TOKEN(0xA3), // Encrypted session telemetry (AES-128-CBC)

    // Navigation Focus (iOS 13+)
    NAVI_FOCUS_REQUEST(0x6E), // Nav requesting focus (IN)
    NAVI_FOCUS_RELEASE(0x6F), // Nav released focus (IN)

    // Newly Identified (Firmware Binary Analysis - Jan 2026)
    REMOTE_CX_CY(0x1E), // Display resolution broadcast from adapter
    EXTENDED_MFG_INFO(0xA1), // Extended OEM/manufacturer data
    REMOTE_DISPLAY(0xF0), // Remote display parameters

    UNKNOWN(-1),
    ;

    // Legacy aliases for backwards compatibility
    @Deprecated("Use PEER_BLUETOOTH_ADDRESS", ReplaceWith("PEER_BLUETOOTH_ADDRESS"))
    val NETWORK_MAC_ADDRESS get() = PEER_BLUETOOTH_ADDRESS

    @Deprecated("Use PEER_BLUETOOTH_ADDRESS_ALT", ReplaceWith("PEER_BLUETOOTH_ADDRESS_ALT"))
    val NETWORK_MAC_ADDRESS_ALT get() = PEER_BLUETOOTH_ADDRESS_ALT

    @Deprecated("Use CLOSE_DONGLE", ReplaceWith("CLOSE_DONGLE"))
    val CLOSE_ADAPTR get() = CLOSE_DONGLE

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
    AUDIO_INCOMING_CALL_INIT(14),
    AUDIO_NAVI_COMPLETE(16),
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
    HAND_DRIVE_MODE("/tmp/hand_drive_mode"),
    CHARGE_MODE("/tmp/charge_mode"),
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
 *
 * Reference: documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md
 */
enum class PhoneType(
    val id: Int,
) {
    ANDROID_MIRROR(1),
    CARLIFE(2),
    CARPLAY(3),
    IPHONE_MIRROR(4),
    ANDROID_AUTO(5),
    HI_CAR(6),
    ICCOA(7),
    CARPLAY_WIRELESS(8),
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
    NAVI_JSON(200),
    UNKNOWN(-1),
    ;

    companion object {
        private val idMap = entries.associateBy { it.id }

        fun fromId(id: Int): MediaType = idMap[id] ?: UNKNOWN
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
    /** True if user explicitly selected a resolution (non-AUTO). When true, CarlinkManager
     *  will use these width/height values instead of actual surface dimensions. */
    val userSelectedResolution: Boolean = false,
    val format: Int = 5,
    val iBoxVersion: Int = 2,
    val packetMax: Int = 49152,
    val phoneWorkMode: Int = 2,
    /** Night mode: false=light theme, true=dark theme for CarPlay display */
    val nightMode: Boolean = false,
    val boxName: String = "carlink",
    val mediaDelay: Int = 1000,
    /** Audio transfer mode: true=bluetooth, false=adapter (USB audio, default) */
    val audioTransferMode: Boolean = false,
    /** Sample rate for media audio (48000 Hz). Controls mediaSound in BoxSettings. */
    val sampleRate: Int = 48000,
    val wifiType: String = "5ghz",
    val micType: String = "os",
    /** Call quality: 0=normal, 1=clear, 2=HD. Sent in BoxSettings. */
    val callQuality: Int = 2,
    val oemIconVisible: Boolean = true,
    val androidWorkMode: Boolean = true,
    /** Hand drive mode: 0 = LHD (dock left, US/EU default), 1 = RHD (dock right, UK/JP/AU) */
    val handDriveMode: Int = 0,
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
