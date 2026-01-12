package com.carlink.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.carlink.BuildConfig
import com.carlink.util.AudioDebugLogger
import com.carlink.util.LogCallback
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CARLINK_MIC"

/**
 * Microphone format configuration matching CPC200-CCPA protocol voice formats.
 */
data class MicFormatConfig(
    val sampleRate: Int,
    val channelCount: Int,
) {
    val channelConfig: Int
        get() =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }

    val encoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT

    val bytesPerSample: Int
        get() = channelCount * 2 // 16-bit = 2 bytes per channel
}

/**
 * Predefined microphone formats from CPC200-CCPA protocol.
 */
object MicFormats {
    val PHONE_CALL = MicFormatConfig(8000, 1) // decodeType=3: Phone calls
    val SIRI_VOICE = MicFormatConfig(16000, 1) // decodeType=5: Siri/voice assistant
    val ENHANCED = MicFormatConfig(24000, 1) // decodeType=6: Enhanced voice
    val STEREO_VOICE = MicFormatConfig(16000, 2) // decodeType=7: Stereo voice

    fun fromDecodeType(decodeType: Int): MicFormatConfig =
        when (decodeType) {
            3 -> PHONE_CALL
            5 -> SIRI_VOICE
            6 -> ENHANCED
            7 -> STEREO_VOICE
            else -> SIRI_VOICE // Default to 16kHz mono
        }
}

/**
 * MicrophoneCaptureManager - Handles microphone capture for CPC200-CCPA voice input.
 *
 * PURPOSE:
 * Captures microphone audio for Siri/voice assistant and phone calls, sending PCM data
 * to the CPC200-CCPA adapter via USB. Uses a ring buffer architecture matching the
 * audio output pipeline for consistent, stutter-free capture.
 *
 * ARCHITECTURE:
 * ```
 * MicCaptureThread (THREAD_PRIORITY_URGENT_AUDIO)
 *     │
 *     ├── AudioRecord.read() [blocks on hardware]
 *     │
 *     └── micBuffer.write() [non-blocking]
 *            │
 *            ▼
 *      MicRingBuffer (500ms)
 *            │
 *            ▼
 *      readChunk() [non-blocking, called by USB send thread]
 * ```
 *
 * KEY FEATURES:
 * - Ring buffer absorbs AudioRecord timing jitter and USB send variations
 * - Dedicated high-priority capture thread
 * - Non-blocking reads for USB thread
 * - VOICE_COMMUNICATION audio source for OS-level echo cancellation/noise suppression
 *
 * THREAD SAFETY:
 * - Capture thread writes to ring buffer (single writer)
 * - USB thread reads from ring buffer (single reader)
 * - Start/stop/configure called from main thread
 */
class MicrophoneCaptureManager(
    private val context: Context,
    private val logCallback: LogCallback,
) {
    private var audioRecord: AudioRecord? = null
    private var micBuffer: AudioRingBuffer? = null
    private var currentFormat: MicFormatConfig? = null
    private var captureThread: MicCaptureThread? = null
    private val isRunning = AtomicBoolean(false)

    private var startTime: Long = 0
    private var totalBytesCapture: Long = 0
    private var overrunCount: Int = 0

    // 500ms buffer prevents overruns when main thread blocked (Session 6 fix)
    private val bufferCapacityMs = 500
    private val captureChunkMs = 20

    private val lock = Any()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /** Start capture. decodeType: 3=phone, 5=siri (default), 6=enhanced, 7=stereo. */
    fun start(decodeType: Int = 5): Boolean {
        synchronized(lock) {
            if (isRunning.get()) {
                log("[MIC] Already capturing")
                return true
            }

            if (!hasPermission()) {
                log("[MIC] ERROR: RECORD_AUDIO permission not granted")
                return false
            }

            val format = MicFormats.fromDecodeType(decodeType)

            try {
                val minBufferSize =
                    AudioRecord.getMinBufferSize(
                        format.sampleRate,
                        format.channelConfig,
                        format.encoding,
                    )

                if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    log("[MIC] ERROR: Invalid buffer size for ${format.sampleRate}Hz")
                    return false
                }

                val recordBufferSize = minBufferSize * 3

                // VOICE_COMMUNICATION enables OS echo cancellation/noise suppression
                audioRecord =
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        format.sampleRate,
                        format.channelConfig,
                        format.encoding,
                        recordBufferSize,
                    )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    log("[MIC] ERROR: AudioRecord failed to initialize")
                    audioRecord?.release()
                    audioRecord = null
                    return false
                }

                micBuffer =
                    AudioRingBuffer(
                        capacityMs = bufferCapacityMs,
                        sampleRate = format.sampleRate,
                        channels = format.channelCount,
                    )

                currentFormat = format
                startTime = System.currentTimeMillis()
                totalBytesCapture = 0
                overrunCount = 0

                audioRecord?.startRecording()
                isRunning.set(true)
                captureThread = MicCaptureThread(format).also { it.start() }

                log(
                    "[MIC] Capture started: ${format.sampleRate}Hz ${format.channelCount}ch " +
                        "buffer=${recordBufferSize}B",
                )
                AudioDebugLogger.logMicStart(format.sampleRate, format.channelCount, bufferCapacityMs)
                return true
            } catch (e: SecurityException) {
                log("[MIC] ERROR: Permission denied: ${e.message}")
                return false
            } catch (e: IllegalArgumentException) {
                log("[MIC] ERROR: Invalid parameters: ${e.message}")
                return false
            } catch (e: IllegalStateException) {
                log("[MIC] ERROR: Invalid state: ${e.message}")
                return false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isRunning.get()) return

            log("[MIC] Stopping capture")
            isRunning.set(false)

            captureThread?.interrupt()
            try {
                captureThread?.join(1000)
            } catch (_: InterruptedException) {
            }
            captureThread = null

            try {
                audioRecord?.let { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                    record.release()
                }
            } catch (e: IllegalStateException) {
                log("[MIC] ERROR: Stop failed: ${e.message}")
            }
            audioRecord = null

            micBuffer?.clear()
            micBuffer = null

            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0
            AudioDebugLogger.logMicStop(durationMs, totalBytesCapture, overrunCount)
            currentFormat = null
            log("[MIC] Capture stopped")
        }
    }

    /** Read captured audio (non-blocking, USB send thread). Returns null if empty. */
    fun readChunk(maxBytes: Int = 1920): ByteArray? {
        val buffer = micBuffer ?: return null

        val available = buffer.availableForRead()
        if (available == 0) {
            return null
        }

        val toRead = minOf(available, maxBytes)
        val output = ByteArray(toRead)
        val bytesRead = buffer.read(output, 0, toRead)

        return if (bytesRead > 0) {
            AudioDebugLogger.logMicSend(bytesRead, buffer.fillLevelMs())
            output.copyOf(bytesRead)
        } else {
            null
        }
    }

    /** Get current decode type (3, 5, 6, or 7), or -1 if not capturing. */
    fun getCurrentDecodeType(): Int {
        val format = currentFormat ?: return -1
        return when {
            format.sampleRate == 8000 && format.channelCount == 1 -> 3
            format.sampleRate == 16000 && format.channelCount == 1 -> 5
            format.sampleRate == 24000 && format.channelCount == 1 -> 6
            format.sampleRate == 16000 && format.channelCount == 2 -> 7
            else -> 5
        }
    }

    fun isCapturing(): Boolean =
        isRunning.get() &&
            audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun availableBytes(): Int = micBuffer?.availableForRead() ?: 0

    fun bufferLevelMs(): Int = micBuffer?.fillLevelMs() ?: 0

    fun getStats(): Map<String, Any> {
        synchronized(lock) {
            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0

            return mapOf(
                "isCapturing" to isRunning.get(),
                "format" to (currentFormat?.let { "${it.sampleRate}Hz ${it.channelCount}ch" } ?: "none"),
                "decodeType" to getCurrentDecodeType(),
                "durationSeconds" to durationMs / 1000.0,
                "totalBytesCaptured" to totalBytesCapture,
                "bufferLevelMs" to bufferLevelMs(),
                "bufferCapacityMs" to bufferCapacityMs,
                "overrunCount" to overrunCount,
                "bufferStats" to (micBuffer?.getStats() ?: emptyMap()),
            )
        }
    }

    fun release() {
        stop()
        log("[MIC] MicrophoneCaptureManager released")
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        logCallback.log(message)
    }

    /** Capture thread (URGENT_AUDIO priority). Reads AudioRecord, writes to ring buffer. */
    private inner class MicCaptureThread(
        private val format: MicFormatConfig,
    ) : Thread("MicCapture") {
        private val chunkSize = (format.sampleRate * format.bytesPerSample * captureChunkMs) / 1000
        private val tempBuffer = ByteArray(chunkSize)

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            log("[MIC] Capture thread started with URGENT_AUDIO priority, chunk=${chunkSize}B")

            val record = audioRecord ?: return
            val buffer = micBuffer ?: return

            while (isRunning.get() && !isInterrupted) {
                try {
                    val bytesRead = record.read(tempBuffer, 0, chunkSize)

                    when {
                        bytesRead > 0 -> {
                            val bytesWritten = buffer.write(tempBuffer, 0, bytesRead)
                            totalBytesCapture += bytesWritten
                            AudioDebugLogger.logMicCapture(bytesRead, buffer.fillLevelMs())

                            if (bytesWritten < bytesRead) {
                                overrunCount++
                                AudioDebugLogger.logMicOverrun(bytesRead - bytesWritten, buffer.fillLevelMs())
                                Log.w(TAG, "[MIC] Buffer overrun: wrote $bytesWritten of $bytesRead bytes")
                            }
                        }

                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                            AudioDebugLogger.logMicError("INVALID_OPERATION", "AudioRecord returned ERROR_INVALID_OPERATION")
                            Log.e(TAG, "[MIC] ERROR: Invalid operation")
                            break
                        }

                        bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                            AudioDebugLogger.logMicError("BAD_VALUE", "AudioRecord returned ERROR_BAD_VALUE")
                            Log.e(TAG, "[MIC] ERROR: Bad value")
                            break
                        }

                        bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                            AudioDebugLogger.logMicError("DEAD_OBJECT", "AudioRecord returned ERROR_DEAD_OBJECT")
                            Log.e(TAG, "[MIC] ERROR: AudioRecord dead")
                            break
                        }

                        bytesRead == AudioRecord.ERROR -> {
                            AudioDebugLogger.logMicError("GENERIC", "AudioRecord returned ERROR")
                            Log.e(TAG, "[MIC] ERROR: Generic error")
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "[MIC] Capture thread error: ${e.message}")
                }
            }

            log("[MIC] Capture thread stopped, total captured: ${totalBytesCapture}B")
        }
    }
}
