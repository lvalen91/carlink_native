package com.carlink.audio

/**
 * Lock-free ring buffer for audio jitter compensation.
 *
 * PURPOSE:
 * Absorbs irregular packet arrival from USB by maintaining a buffer reserve.
 * USB capture analysis shows P99 jitter of ~7ms with max 30ms, so buffer
 * provides significant safety margin for consistent playback.
 *
 * THREAD SAFETY:
 * Designed for single-writer (USB thread), single-reader (audio playback thread).
 * Uses volatile variables for lock-free synchronization.
 *
 * SAMPLE ALIGNMENT:
 * When discarding oldest data during overflow, bytes are aligned to PCM frame
 * boundaries (channels * 2 bytes for 16-bit audio) to prevent audio corruption.
 * See: https://developer.android.com/reference/android/media/AudioFormat
 *
 * BUFFER SIZING:
 * - Media stream: 200-300ms recommended (absorbs adapter jitter)
 * - Navigation stream: 100-150ms recommended (lower latency for prompts)
 *
 * @param capacityMs Buffer capacity in milliseconds
 * @param sampleRate Audio sample rate (e.g., 44100, 48000)
 * @param channels Number of audio channels (1=mono, 2=stereo)
 */
class AudioRingBuffer(
    private val capacityMs: Int,
    private val sampleRate: Int,
    private val channels: Int,
) {
    // PCM frame size: 2 bytes per sample (16-bit) * number of channels
    // Stereo 16-bit = 4 bytes per frame, Mono 16-bit = 2 bytes per frame
    private val bytesPerFrame = channels * 2
    private val bytesPerMs = (sampleRate * channels * 2) / 1000
    private val capacity = capacityMs * bytesPerMs
    private val buffer = ByteArray(capacity)

    @Volatile private var writePos = 0 // Only modified by writer thread

    @Volatile private var readPos = 0 // Only modified by reader thread

    @Volatile var totalBytesWritten: Long = 0
        private set

    @Volatile var totalBytesRead: Long = 0
        private set

    @Volatile var overflowCount: Int = 0
        private set

    @Volatile var underflowCount: Int = 0
        private set

    @Volatile var discardedBytes: Long = 0
        private set

    /**
     * Write data to ring buffer (non-blocking, overwrite-oldest when full).
     * @return Bytes written (always equals length - oldest data discarded if full)
     */
    fun write(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Int {
        var available = availableForWrite()

        // Overwrite-oldest: discard oldest data when full (Session 4 overflow=568 fix)
        // Align discard to PCM frame boundary to prevent audio corruption
        // (channel phase shift, clicking/popping artifacts)
        if (available < length) {
            val minDiscard = length - available
            // Round up to nearest frame boundary for proper sample alignment
            val toDiscard = ((minDiscard + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame
            readPos = (readPos + toDiscard) % capacity
            discardedBytes += toDiscard
            overflowCount++
            available = availableForWrite()
        }

        val toWrite = minOf(length, available)
        val localWritePos = writePos

        val firstChunk = minOf(toWrite, capacity - localWritePos)
        System.arraycopy(data, offset, buffer, localWritePos, firstChunk)

        if (toWrite > firstChunk) {
            val secondChunk = toWrite - firstChunk
            System.arraycopy(data, offset + firstChunk, buffer, 0, secondChunk)
        }

        writePos = (localWritePos + toWrite) % capacity
        totalBytesWritten += toWrite

        return toWrite
    }

    /**
     * Read data from ring buffer (non-blocking).
     * @return Bytes read (may be less than length if buffer empty)
     */
    fun read(
        out: ByteArray,
        offset: Int = 0,
        length: Int = out.size - offset,
    ): Int {
        val available = availableForRead()
        if (available == 0) {
            underflowCount++
            return 0
        }

        val toRead = minOf(length, available)
        val localReadPos = readPos

        val firstChunk = minOf(toRead, capacity - localReadPos)
        System.arraycopy(buffer, localReadPos, out, offset, firstChunk)

        if (toRead > firstChunk) {
            val secondChunk = toRead - firstChunk
            System.arraycopy(buffer, 0, out, offset + firstChunk, secondChunk)
        }

        readPos = (localReadPos + toRead) % capacity
        totalBytesRead += toRead
        return toRead
    }

    fun availableForRead(): Int {
        val w = writePos
        val r = readPos
        return if (w >= r) w - r else capacity - r + w
    }

    fun availableForWrite(): Int = capacity - availableForRead() - 1 // -1 distinguishes full from empty

    fun fillLevel(): Float = availableForRead().toFloat() / capacity

    fun fillLevelMs(): Int = if (bytesPerMs > 0) availableForRead() / bytesPerMs else 0

    fun hasEnoughData(thresholdMs: Int = 50): Boolean = fillLevelMs() >= thresholdMs

    /** Clear buffer. Only call when both threads are stopped. */
    fun clear() {
        writePos = 0
        readPos = 0
    }

    fun getStats(): Map<String, Any> =
        mapOf(
            "capacityMs" to capacityMs,
            "capacityBytes" to capacity,
            "fillLevelMs" to fillLevelMs(),
            "fillPercent" to (fillLevel() * 100).toInt(),
            "totalBytesWritten" to totalBytesWritten,
            "totalBytesRead" to totalBytesRead,
            "overflowCount" to overflowCount,
            "underflowCount" to underflowCount,
            "discardedBytes" to discardedBytes,
            "sampleRate" to sampleRate,
            "channels" to channels,
        )

    companion object {
        fun forMedia(
            sampleRate: Int = 44100,
            channels: Int = 2,
        ) = AudioRingBuffer(capacityMs = 250, sampleRate = sampleRate, channels = channels)

        fun forNavigation(
            sampleRate: Int = 44100,
            channels: Int = 2,
        ) = AudioRingBuffer(capacityMs = 120, sampleRate = sampleRate, channels = channels)
    }
}
