package com.carlink.capture

import android.content.Context
import android.net.Uri
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream

/**
 * Capture Replay Source - Reads and replays USB capture files.
 *
 * Parses the JSON metadata file and reads corresponding binary data,
 * replaying packets with original timing to simulate adapter connection.
 *
 * File format:
 * - .json: Packet metadata (sequence, direction, type, timestamp, offset, length)
 * - .bin: Raw packet data (read at offsets specified in JSON)
 *
 * Only IN direction packets are replayed (data from adapter to app).
 * OUT packets (app to adapter) are skipped during replay.
 */
class CaptureReplaySource(
    private val context: Context,
) {
    companion object {
        private const val TAG = "REPLAY"

        // Message types we care about for replay
        private const val TYPE_VIDEO_DATA = 6
        private const val TYPE_AUDIO_DATA = 7
        private const val TYPE_PLUGGED = 5
        private const val TYPE_COMMAND = 8
        private const val TYPE_MEDIA_DATA = 9
    }

    /**
     * Packet metadata from JSON file.
     */
    data class PacketInfo(
        val seq: Int,
        val direction: String,
        val type: Int,
        val typeName: String,
        val timestampMs: Long,
        val offset: Long,
        val length: Int,
    )

    /**
     * Session metadata from JSON file.
     */
    data class SessionInfo(
        val id: String,
        val started: String,
        val ended: String,
        val durationMs: Long,
    )

    /**
     * Callback interface for replay events.
     */
    interface ReplayCallback {
        fun onSessionStart(session: SessionInfo)
        fun onPacket(
            type: Int,
            typeName: String,
            data: ByteArray,
        )

        fun onProgress(
            currentMs: Long,
            totalMs: Long,
        )

        fun onComplete()
        fun onError(message: String)
    }

    private var replayJob: Job? = null
    private var isPlaying = false
    private var callback: ReplayCallback? = null

    private var packets: List<PacketInfo> = emptyList()
    private var sessionInfo: SessionInfo? = null
    private var binInputStream: InputStream? = null
    private var effectiveDurationMs: Long = 0L

    /**
     * Load capture files and prepare for replay.
     *
     * @param jsonUri URI of the .json metadata file
     * @param binUri URI of the .bin data file
     * @return True if files loaded successfully
     */
    suspend fun load(
        jsonUri: Uri,
        binUri: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                logInfo("[$TAG] Loading capture files", tag = TAG)
                logInfo("[$TAG] JSON: $jsonUri", tag = TAG)
                logInfo("[$TAG] BIN: $binUri", tag = TAG)

                // Parse JSON metadata
                val jsonContent =
                    context.contentResolver.openInputStream(jsonUri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: run {
                        logError("[$TAG] Failed to read JSON file", tag = TAG)
                        return@withContext false
                    }

                val json = JSONObject(jsonContent)

                // Parse session info
                val sessionObj = json.getJSONObject("session")
                sessionInfo =
                    SessionInfo(
                        id = sessionObj.getString("id"),
                        started = sessionObj.getString("started"),
                        ended = sessionObj.getString("ended"),
                        durationMs = sessionObj.getLong("durationMs"),
                    )

                // Parse packets
                val packetsArray = json.getJSONArray("packets")
                val packetList = mutableListOf<PacketInfo>()

                for (i in 0 until packetsArray.length()) {
                    val pkt = packetsArray.getJSONObject(i)
                    packetList.add(
                        PacketInfo(
                            seq = pkt.getInt("seq"),
                            direction = pkt.getString("dir"),
                            type = pkt.getInt("type"),
                            typeName = pkt.getString("typeName"),
                            timestampMs = pkt.getLong("timestampMs"),
                            offset = pkt.getLong("offset"),
                            length = pkt.getInt("length"),
                        ),
                    )
                }

                packets = packetList

                // Open binary file for reading
                binInputStream?.close()
                binInputStream = context.contentResolver.openInputStream(binUri)

                // Calculate effective duration (first IN packet to last IN packet)
                val inPackets = packets.filter { it.direction == "IN" }.sortedBy { it.timestampMs }
                effectiveDurationMs = if (inPackets.size >= 2) {
                    inPackets.last().timestampMs - inPackets.first().timestampMs
                } else {
                    sessionInfo?.durationMs ?: 0L
                }

                logInfo(
                    "[$TAG] Loaded ${packets.size} packets (${inPackets.size} IN), " +
                        "effective duration: ${effectiveDurationMs}ms (session: ${sessionInfo?.durationMs}ms)",
                    tag = TAG,
                )

                true
            } catch (e: Exception) {
                logError("[$TAG] Failed to load capture files: ${e.message}", tag = TAG)
                false
            }
        }

    /**
     * Start replay playback.
     *
     * @param callback Callback for replay events
     */
    fun start(callback: ReplayCallback) {
        if (isPlaying) {
            logInfo("[$TAG] Already playing, ignoring start", tag = TAG)
            return
        }

        val session = sessionInfo
        if (session == null || packets.isEmpty()) {
            callback.onError("No capture loaded")
            return
        }

        this.callback = callback
        isPlaying = true

        logInfo("[$TAG] Starting replay: ${session.id}", tag = TAG)
        callback.onSessionStart(session)

        replayJob =
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    replayPackets(callback)
                } catch (e: Exception) {
                    logError("[$TAG] Replay error: ${e.message}", tag = TAG)
                    withContext(Dispatchers.Main) {
                        callback.onError(e.message ?: "Unknown error")
                    }
                } finally {
                    isPlaying = false
                }
            }
    }

    /**
     * Stop replay playback.
     */
    fun stop() {
        logInfo("[$TAG] Stopping replay", tag = TAG)
        replayJob?.cancel()
        replayJob = null
        isPlaying = false
    }

    /**
     * Release resources.
     */
    fun release() {
        stop()
        binInputStream?.close()
        binInputStream = null
        packets = emptyList()
        sessionInfo = null
        callback = null
        effectiveDurationMs = 0L
    }

    /**
     * Check if replay is currently playing.
     */
    fun isPlaying(): Boolean = isPlaying

    /**
     * Get effective playback duration (first IN packet to last IN packet).
     * This excludes setup time before first packet.
     */
    fun getDurationMs(): Long = effectiveDurationMs

    private suspend fun replayPackets(callback: ReplayCallback) {
        val session = sessionInfo ?: return
        val binStream = binInputStream ?: return

        // Filter to only IN packets (data from adapter)
        val inPackets =
            packets.filter { it.direction == "IN" }.sortedBy { it.timestampMs }

        if (inPackets.isEmpty()) {
            withContext(Dispatchers.Main) {
                callback.onError("No IN packets to replay")
            }
            return
        }

        val startTime = System.currentTimeMillis()
        val firstPacketTime = inPackets.first().timestampMs
        val lastPacketTime = inPackets.last().timestampMs
        val effectiveDuration = lastPacketTime - firstPacketTime
        var lastProgressUpdate = 0L

        // Track current position in bin file
        var currentOffset = 0L

        for (packet in inPackets) {
            if (!isPlaying) break

            // Calculate delay to maintain original timing
            val elapsedReplay = System.currentTimeMillis() - startTime
            val targetTime = packet.timestampMs - firstPacketTime
            val delayMs = targetTime - elapsedReplay

            if (delayMs > 0) {
                delay(delayMs)
            }

            // Read packet data from binary file
            val data = readPacketData(binStream, packet, currentOffset)
            currentOffset = packet.offset + packet.length

            if (data != null) {
                logDebug(
                    "[$TAG] Packet #${packet.seq} type=${packet.typeName} " +
                        "len=${packet.length} ts=${packet.timestampMs}ms",
                    tag = TAG,
                )

                withContext(Dispatchers.Main) {
                    callback.onPacket(packet.type, packet.typeName, data)
                }
            }

            // Update progress every 500ms
            val currentTime = packet.timestampMs - firstPacketTime
            if (currentTime - lastProgressUpdate >= 500) {
                lastProgressUpdate = currentTime
                withContext(Dispatchers.Main) {
                    callback.onProgress(currentTime, effectiveDuration)
                }
            }
        }

        // Final progress update to show 100%
        withContext(Dispatchers.Main) {
            callback.onProgress(effectiveDuration, effectiveDuration)
        }

        // Allow audio/video buffers to drain before signaling completion
        // Audio buffer is ~120ms, video codec may have a few frames queued
        delay(500)

        logInfo("[$TAG] Replay complete", tag = TAG)
        withContext(Dispatchers.Main) {
            callback.onComplete()
        }
    }

    private fun readPacketData(
        stream: InputStream,
        packet: PacketInfo,
        currentOffset: Long,
    ): ByteArray? {
        try {
            // Skip to packet offset if needed
            val skipBytes = packet.offset - currentOffset
            if (skipBytes > 0) {
                stream.skip(skipBytes)
            } else if (skipBytes < 0) {
                // Need to re-open stream and seek (can't seek backwards)
                logError("[$TAG] Cannot seek backwards in stream", tag = TAG)
                return null
            }

            // Read packet data
            val buffer = ByteArray(packet.length)
            var bytesRead = 0
            while (bytesRead < packet.length) {
                val read = stream.read(buffer, bytesRead, packet.length - bytesRead)
                if (read == -1) break
                bytesRead += read
            }

            if (bytesRead != packet.length) {
                logError("[$TAG] Short read: expected ${packet.length}, got $bytesRead", tag = TAG)
                return null
            }

            return buffer
        } catch (e: Exception) {
            logError("[$TAG] Error reading packet: ${e.message}", tag = TAG)
            return null
        }
    }
}
