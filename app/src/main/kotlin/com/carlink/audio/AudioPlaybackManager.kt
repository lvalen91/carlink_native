package com.carlink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.carlink.BuildConfig
import com.carlink.util.LogCallback

private const val TAG = "CARLINK_AUDIO"

/**
 * Audio format configuration matching CPC200-CCPA protocol decode types
 */
data class AudioFormatConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int = 16,
) {
    val channelConfig: Int
        get() =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

    val encoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT
}

/**
 * Predefined audio formats from CPC200-CCPA protocol
 */
object AudioFormats {
    val FORMAT_1 = AudioFormatConfig(44100, 2) // Music stereo
    val FORMAT_2 = AudioFormatConfig(44100, 2) // Music stereo (duplicate)
    val FORMAT_3 = AudioFormatConfig(8000, 1) // Phone calls
    val FORMAT_4 = AudioFormatConfig(48000, 2) // High-quality
    val FORMAT_5 = AudioFormatConfig(16000, 1) // Siri/voice
    val FORMAT_6 = AudioFormatConfig(24000, 1) // Enhanced voice
    val FORMAT_7 = AudioFormatConfig(16000, 2) // Stereo voice

    fun fromDecodeType(decodeType: Int): AudioFormatConfig =
        when (decodeType) {
            1 -> FORMAT_1
            2 -> FORMAT_2
            3 -> FORMAT_3
            4 -> FORMAT_4
            5 -> FORMAT_5
            6 -> FORMAT_6
            7 -> FORMAT_7
            else -> FORMAT_4 // Default to high-quality
        }
}

/**
 * Native AudioTrack-based audio playback for CPC200-CCPA audio streams.
 *
 * Handles real-time PCM audio playback from CarPlay/Android Auto projection via the CPC200-CCPA
 * adapter. Manages AudioTrack lifecycle, format switching, and buffer management for low-latency
 * automotive audio playback.
 */
class AudioPlaybackManager(
    private val logCallback: LogCallback,
) {
    private var audioTrack: AudioTrack? = null
    private var currentFormat: AudioFormatConfig? = null
    private var isPlaying = false

    private var totalBytesPlayed: Long = 0
    private var totalFramesPlayed: Long = 0
    private var underrunCount: Int = 0
    private var formatSwitchCount: Int = 0
    private var startTime: Long = 0

    private var minBufferSize: Int = 0
    private var actualBufferSize: Int = 0
    private var currentVolume: Float = 1.0f

    private val lock = Any()

    fun initialize(format: AudioFormatConfig = AudioFormats.FORMAT_4): Boolean {
        synchronized(lock) {
            try {
                if (currentFormat != null && currentFormat != format) {
                    log("[AUDIO] Format change detected: ${currentFormat?.sampleRate}Hz -> ${format.sampleRate}Hz")
                    release()
                    formatSwitchCount++
                }

                if (audioTrack != null && currentFormat == format) {
                    log("[AUDIO] Already initialized with same format")
                    return true
                }

                minBufferSize =
                    AudioTrack.getMinBufferSize(
                        format.sampleRate,
                        format.channelConfig,
                        format.encoding,
                    )

                if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                    log("[AUDIO] ERROR: Invalid buffer size for format: ${format.sampleRate}Hz ${format.channelCount}ch")
                    return false
                }

                actualBufferSize = minBufferSize * 2

                val audioAttributes =
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()

                val audioFormat =
                    AudioFormat
                        .Builder()
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(format.channelConfig)
                        .setEncoding(format.encoding)
                        .build()

                audioTrack =
                    AudioTrack
                        .Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(actualBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()

                currentFormat = format
                startTime = System.currentTimeMillis()
                audioTrack?.setVolume(currentVolume)

                log(
                    "[AUDIO] Initialized: ${format.sampleRate}Hz ${format.channelCount}ch buffer=${actualBufferSize}B (min=${minBufferSize}B)",
                )
                return true
            } catch (e: IllegalArgumentException) {
                log("[AUDIO] ERROR: Invalid audio parameters: ${e.message}")
                return false
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: AudioTrack state error: ${e.message}")
                return false
            } catch (e: UnsupportedOperationException) {
                log("[AUDIO] ERROR: Unsupported audio operation: ${e.message}")
                return false
            }
        }
    }

    fun start(): Boolean {
        synchronized(lock) {
            val track =
                audioTrack ?: run {
                    log("[AUDIO] Cannot start: AudioTrack not initialized")
                    return false
                }

            if (isPlaying) {
                return true
            }

            try {
                track.play()
                isPlaying = true
                log("[AUDIO] Playback started")
                return true
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: Cannot start playback: ${e.message}")
                return false
            }
        }
    }

    /** Write PCM audio data. Returns bytes written or negative error code. */
    fun write(
        data: ByteArray,
        decodeType: Int = 4,
        volume: Float = 1.0f,
    ): Int {
        synchronized(lock) {
            val targetFormat = AudioFormats.fromDecodeType(decodeType)
            if (currentFormat != targetFormat) {
                log("[AUDIO] Format switch required: type $decodeType -> ${targetFormat.sampleRate}Hz ${targetFormat.channelCount}ch")
                if (!initialize(targetFormat)) return -1
            }

            val track =
                audioTrack ?: run {
                    if (!initialize(targetFormat)) return -1
                    audioTrack!!
                }

            if (!isPlaying) start()

            if (volume != currentVolume) {
                currentVolume = volume.coerceIn(0.0f, 1.0f)
                track.setVolume(currentVolume)
            }

            try {
                val bytesWritten = track.write(data, 0, data.size)

                if (bytesWritten > 0) {
                    totalBytesPlayed += bytesWritten
                    totalFramesPlayed += bytesWritten / (currentFormat?.channelCount ?: 2) / 2
                } else if (bytesWritten == AudioTrack.ERROR_DEAD_OBJECT) {
                    log("[AUDIO] ERROR: AudioTrack dead, reinitializing")
                    release()
                    initialize(targetFormat)
                    start()
                    return track.write(data, 0, data.size)
                }

                val underruns = track.underrunCount
                if (underruns > underrunCount) {
                    val newUnderruns = underruns - underrunCount
                    underrunCount = underruns
                    Log.w(TAG, "[AUDIO] Buffer underrun detected: $newUnderruns new (total: $underruns)")
                }

                return bytesWritten
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: Write failed: ${e.message}")
                return -1
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            try {
                if (isPlaying) {
                    audioTrack?.pause()
                    isPlaying = false
                    log("[AUDIO] Playback paused")
                }
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: Cannot pause: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                audioTrack?.let { track ->
                    if (isPlaying) {
                        track.stop()
                        isPlaying = false
                    }
                    track.flush()
                    log("[AUDIO] Playback stopped and flushed")
                }
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: Cannot stop: ${e.message}")
            }
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                audioTrack?.let { track ->
                    if (isPlaying) {
                        track.stop()
                        isPlaying = false
                    }
                    track.release()
                    log("[AUDIO] AudioTrack released")
                }
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: Release error: ${e.message}")
            } finally {
                audioTrack = null
                currentFormat = null
            }
        }
    }

    fun setVolume(volume: Float) {
        synchronized(lock) {
            currentVolume = volume.coerceIn(0.0f, 1.0f)
            try {
                audioTrack?.setVolume(currentVolume)
            } catch (e: IllegalStateException) {
                log("[AUDIO] ERROR: Cannot set volume: ${e.message}")
            }
        }
    }

    fun isPlaying(): Boolean {
        synchronized(lock) {
            return isPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
        }
    }

    fun getStats(): Map<String, Any> {
        synchronized(lock) {
            val durationMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0
            val durationSec = durationMs / 1000.0

            return mapOf(
                "isPlaying" to isPlaying,
                "currentFormat" to (
                    currentFormat?.let {
                        "${it.sampleRate}Hz ${it.channelCount}ch"
                    } ?: "none"
                ),
                "totalBytesPlayed" to totalBytesPlayed,
                "totalFramesPlayed" to totalFramesPlayed,
                "underrunCount" to underrunCount,
                "formatSwitchCount" to formatSwitchCount,
                "durationSeconds" to durationSec,
                "throughputKBps" to if (durationSec > 0) totalBytesPlayed / 1024.0 / durationSec else 0.0,
                "bufferSize" to actualBufferSize,
                "minBufferSize" to minBufferSize,
                "volume" to currentVolume,
            )
        }
    }

    fun performEmergencyCleanup() {
        synchronized(lock) {
            log("[AUDIO] Emergency cleanup")
            try {
                audioTrack?.let { track ->
                    track.flush()
                    track.stop()
                    track.release()
                }
            } catch (e: Exception) {
                log("[AUDIO] ERROR during emergency cleanup: ${e.message}")
            } finally {
                audioTrack = null
                currentFormat = null
                isPlaying = false
            }
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
        logCallback.log(message)
    }
}
