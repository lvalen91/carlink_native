package com.carlink.platform

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.carlink.BuildConfig

/**
 * PlatformDetector - Detects hardware platform characteristics for configuration selection.
 *
 * PURPOSE:
 * Identifies Intel x86/x86_64 platforms and GM AAOS devices at runtime to enable
 * platform-specific optimizations. No root access required.
 *
 * DETECTION METHODS:
 * - Build.SUPPORTED_ABIS: Primary CPU architecture detection (x86, x86_64, arm64-v8a, etc.)
 * - Build.MANUFACTURER/PRODUCT/DEVICE: GM AAOS device identification
 * - MediaCodecList: Intel OMX codec detection
 * - AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE: Native audio sample rate
 *
 * USAGE:
 * Call detect(context) once during plugin initialization. The returned PlatformInfo
 * is used by AudioConfig to select appropriate settings.
 *
 * Reference: https://developer.android.com/ndk/guides/abis
 */
object PlatformDetector {
    private const val TAG = "CARLINK_PLATFORM"

    /**
     * Platform information data class.
     *
     * @property isIntel True if CPU architecture is x86 or x86_64
     * @property isGmAaos True if device is GM AAOS (Harman_Samsung manufacturer or gminfo product)
     * @property cpuArch Primary CPU ABI (e.g., "arm64-v8a", "x86_64")
     * @property hasIntelCodec True if an Intel video codec is available
     * @property hardwareH264DecoderName The detected hardware H.264 decoder name (any vendor)
     * @property nativeSampleRate Device's native audio output sample rate in Hz
     * @property manufacturer Device manufacturer string
     * @property product Device product string
     * @property device Device name string
     * @property isBroxton True if device is Intel Broxton/Apollo Lake platform (gminfo37)
     * @property displayWidth Native display width in pixels (0 if unknown)
     * @property displayHeight Native display height in pixels (0 if unknown)
     */
    data class PlatformInfo(
        val isIntel: Boolean,
        val isGmAaos: Boolean,
        val cpuArch: String,
        val hasIntelCodec: Boolean,
        val hardwareH264DecoderName: String? = null,
        val nativeSampleRate: Int,
        val manufacturer: String,
        val product: String,
        val device: String,
        val isBroxton: Boolean = false,
        val displayWidth: Int = 0,
        val displayHeight: Int = 0,
    ) {
        /**
         * Returns true if device is the GM gminfo37 (2400x960 display).
         * This enables specific optimizations for this hardware configuration.
         */
        fun isGmInfo37(): Boolean = device.equals("gminfo37", ignoreCase = true)

        /**
         * Returns true if Intel-specific MediaCodec fixes should be applied.
         * Requires BOTH Intel architecture AND Intel codec presence.
         * ARM-based GM AAOS devices will return false.
         */
        fun requiresIntelMediaCodecFixes(): Boolean = isIntel && hasIntelCodec

        /**
         * Returns true if GM AAOS audio optimizations should be applied.
         * Requires BOTH Intel architecture AND GM AAOS device.
         * ARM-based GM AAOS devices will return false.
         */
        fun requiresGmAaosAudioFixes(): Boolean = isIntel && isGmAaos

        override fun toString(): String =
            "PlatformInfo(arch=$cpuArch, intel=$isIntel, gm=$isGmAaos, " +
                "hwDecoder=${hardwareH264DecoderName ?: "software"}, " +
                "nativeRate=${nativeSampleRate}Hz, mfr=$manufacturer, product=$product, device=$device)"
    }

    /**
     * Detect platform characteristics.
     *
     * @param context Android context for accessing system services
     * @return PlatformInfo with all detected characteristics
     */
    fun detect(context: Context): PlatformInfo {
        val cpuArch = detectCpuArchitecture()
        val isIntel = cpuArch == "x86_64" || cpuArch == "x86"

        val manufacturer = Build.MANUFACTURER ?: ""
        val product = Build.PRODUCT ?: ""
        val device = Build.DEVICE ?: ""
        val board = Build.BOARD ?: ""
        val hardware = Build.HARDWARE ?: ""

        val isGmAaos = detectGmAaos(manufacturer, product, device)
        val (_, hardwareH264DecoderName) = detectHardwareH264Decoder()
        val hasIntelCodec = hardwareH264DecoderName?.contains("Intel", ignoreCase = true) == true
        val nativeSampleRate = detectNativeSampleRate(context)

        // Detect Intel Broxton/Apollo Lake platform (used in gminfo37)
        // Broxton uses Intel Atom x7 (Apollo Lake) with HD Graphics 505
        val isBroxton =
            board.contains("broxton", ignoreCase = true) ||
                hardware.contains("broxton", ignoreCase = true) ||
                product.contains("broxton", ignoreCase = true)

        // Detect display resolution
        val (displayWidth, displayHeight) = detectDisplayResolution(context)

        val info =
            PlatformInfo(
                isIntel = isIntel,
                isGmAaos = isGmAaos,
                cpuArch = cpuArch,
                hasIntelCodec = hasIntelCodec,
                hardwareH264DecoderName = hardwareH264DecoderName,
                nativeSampleRate = nativeSampleRate,
                manufacturer = manufacturer,
                product = product,
                device = device,
                isBroxton = isBroxton,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "[PLATFORM] Detected: $info")
            Log.i(TAG, "[PLATFORM] Hardware H.264 decoder: ${hardwareH264DecoderName ?: "none (software fallback)"}")
            Log.i(TAG, "[PLATFORM] Intel-specific fixes: ${info.requiresIntelMediaCodecFixes()}")
            Log.i(TAG, "[PLATFORM] GM AAOS audio fixes: ${info.requiresGmAaosAudioFixes()}")
            Log.i(TAG, "[PLATFORM] Broxton platform: $isBroxton, Display: ${displayWidth}x$displayHeight")
        }

        return info
    }

    /**
     * Detect display resolution from WindowManager.
     *
     * @param context Android context
     * @return Pair of (width, height) in pixels
     */
    private fun detectDisplayResolution(context: Context): Pair<Int, Int> =
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                // minSdk 32 >= API 30 (R), so currentWindowMetrics is always available
                val bounds = windowManager.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                Pair(0, 0)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect display resolution: ${e.message}")
            Pair(0, 0)
        }

    /**
     * Detect primary CPU architecture from Build.SUPPORTED_ABIS.
     *
     * Reference: https://developer.android.com/ndk/guides/abis
     */
    private fun detectCpuArchitecture(): String =
        try {
            Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect CPU architecture: ${e.message}")
            "unknown"
        }

    /**
     * Detect if device is GM AAOS based on manufacturer, product, and device strings.
     *
     * Known GM AAOS identifiers:
     * - Manufacturer: "Harman_Samsung"
     * - Product: contains "gminfo" (e.g., "full_gminfo37_gb")
     * - Device: "gminfo37", etc.
     */
    private fun detectGmAaos(
        manufacturer: String,
        product: String,
        device: String,
    ): Boolean =
        manufacturer.equals("Harman_Samsung", ignoreCase = true) ||
            product.contains("gminfo", ignoreCase = true) ||
            device.startsWith("gminfo", ignoreCase = true)

    /**
     * Detect the best available hardware H.264 decoder.
     *
     * @return Pair of (isHardwareDecoder, codecName)
     */
    private fun detectHardwareH264Decoder(): Pair<Boolean, String?> =
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val hwDecoder =
                codecList.codecInfos.firstOrNull { info ->
                    !info.isEncoder &&
                        info.isHardwareAccelerated &&
                        info.supportedTypes.any { type ->
                            type.equals("video/avc", ignoreCase = true)
                        }
                }
            if (hwDecoder != null) {
                if (BuildConfig.DEBUG) Log.i(TAG, "[PLATFORM] Found hardware H.264 decoder: ${hwDecoder.name}")
                Pair(true, hwDecoder.name)
            } else {
                if (BuildConfig.DEBUG) Log.i(TAG, "[PLATFORM] No hardware H.264 decoder found")
                Pair(false, null)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect hardware codec: ${e.message}")
            Pair(false, null)
        }

    /**
     * Detect native audio output sample rate.
     *
     * Uses AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE to get the optimal output rate.
     * Falls back to 48000 Hz (common automotive rate) if detection fails.
     *
     * Reference: https://developer.android.com/reference/android/media/AudioManager#PROPERTY_OUTPUT_SAMPLE_RATE
     */
    private fun detectNativeSampleRate(context: Context): Int =
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?: DEFAULT_SAMPLE_RATE
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect native sample rate: ${e.message}")
            DEFAULT_SAMPLE_RATE
        }

    private const val DEFAULT_SAMPLE_RATE = 48000
}
