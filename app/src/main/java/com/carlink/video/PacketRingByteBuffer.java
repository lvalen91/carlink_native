package com.carlink.video;

/**
 * Thread-safe ring buffer for video/audio packet streaming.
 *
 * Packet format: [4B length][4B skip offset][data]
 * Limits: 1MB min, 64MB max, 32MB emergency reset threshold.
 */
import java.io.IOException;
import java.nio.ByteBuffer;

import com.carlink.util.LogCallback;
import com.carlink.util.VideoDebugLogger;

public class PacketRingByteBuffer {
    public interface DirectWriteCallback {
         void write(byte[] bytes, int offset);
    }

    /** Callback invoked when emergency reset occurs, signaling all buffered data was lost. */
    public interface EmergencyResetCallback {
        void onEmergencyReset();
    }

    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024;
    private static final int MIN_BUFFER_SIZE = 1024 * 1024;
    private static final int EMERGENCY_RESET_THRESHOLD = 32 * 1024 * 1024;

    private byte[] buffer;
    private int readPosition = 0;
    private int writePosition = 0;

    private int lastWritePositionBeforeEnd = 0;

    private int packetCount = 0;
    private int resizeAttemptCount = 0;

    private LogCallback logCallback;
    private EmergencyResetCallback emergencyResetCallback;

    public PacketRingByteBuffer(int initialSize) {
        int safeSize = Math.max(MIN_BUFFER_SIZE, Math.min(initialSize, MAX_BUFFER_SIZE));
        buffer = new byte[safeSize];
        if (safeSize != initialSize) {
            log("Buffer adjusted: " + initialSize + " -> " + safeSize);
        }
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    /** Set callback to be notified when emergency reset occurs (all data lost). */
    public void setEmergencyResetCallback(EmergencyResetCallback callback) {
        this.emergencyResetCallback = callback;
    }

    public boolean isEmpty() {
        return packetCount == 0;
    }

    public int availablePacketsToRead() {
        return packetCount;
    }

    private void reorganizeAndResizeIfNeeded() {

        int available = 0;
        if (writePosition > readPosition) {
            available = readPosition + buffer.length - writePosition;
        }
        else {
            available = readPosition - writePosition;
        }

        int newLength = buffer.length;
        if (available < buffer.length / 2) {
            int proposedSize = newLength * 2;
            resizeAttemptCount++;

            if (proposedSize > MAX_BUFFER_SIZE) {
                if (buffer.length >= EMERGENCY_RESET_THRESHOLD) {
                    log("EMERGENCY: Buffer at " + (buffer.length / (1024*1024)) + "MB, performing emergency reset");
                    VideoDebugLogger.logRingEmergencyReset(buffer.length);
                    performEmergencyReset();
                    return;
                } else {
                    newLength = MAX_BUFFER_SIZE;
                    log("RESIZE capped at maximum: " + (newLength / (1024*1024)) + "MB");
                }
            } else {
                newLength = proposedSize;
                log("RESIZE to: " + (newLength / (1024*1024)) + "MB, attempt: " + resizeAttemptCount +
                    ", read:" + readPosition + ", write:" + writePosition + ", count:" + availablePacketsToRead());
                VideoDebugLogger.logRingResize(buffer.length, newLength, readPosition, writePosition);
            }
        }

        byte[] newBuffer = new byte[newLength];

        if (writePosition < readPosition) {
            int dataAtEndLength = lastWritePositionBeforeEnd - readPosition;

            if (dataAtEndLength < 0 || dataAtEndLength > buffer.length || readPosition < 0 || readPosition + dataAtEndLength > buffer.length) {
                log("CRITICAL: Invalid end copy parameters");
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            if (writePosition < 0 || writePosition > buffer.length || dataAtEndLength + writePosition > newBuffer.length) {
                log("CRITICAL: Invalid start copy parameters - writePosition: " + writePosition + ", dataAtEndLength: " + dataAtEndLength + ", newBufferLength: " + newBuffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            // copy end
            System.arraycopy(buffer, readPosition, newBuffer, 0, dataAtEndLength);

            // copy from start
            System.arraycopy(buffer, 0, newBuffer, dataAtEndLength, writePosition);

            // update positions
            readPosition = 0;
            writePosition += dataAtEndLength;
        }
        else {
            int copyLength = writePosition - readPosition;
            
            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (copyLength < 0 || readPosition < 0 || readPosition + copyLength > buffer.length || copyLength > newBuffer.length) {
                log("CRITICAL: Invalid linear copy parameters - readPosition: " + readPosition + ", writePosition: " + writePosition + ", copyLength: " + copyLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            System.arraycopy(buffer, readPosition, newBuffer, 0, copyLength);

            writePosition -= readPosition;
            readPosition = 0;
        }

        log("RESIZE done, read:"+readPosition+", write:"+writePosition+", length:"+buffer.length+", count:"+availablePacketsToRead());

        buffer = newBuffer;
    }

    private int availableSpaceAtHead() {
        if (writePosition < readPosition) {
            return readPosition - writePosition;
        }

        return buffer.length - writePosition;
    }
    private int availableSpaceAtStart() {
        if (writePosition < readPosition) {
            return 0;
        }

        return readPosition;
    }

    // Maximum resize attempts before forcing emergency reset to prevent infinite loop
    private static final int MAX_RESIZE_ATTEMPTS = 5;

    public void directWriteToBuffer(int length, int skipBytesCount, DirectWriteCallback callback) {
        synchronized (this) {
            // Validate input parameters to prevent corruption
            if (length < 0 || skipBytesCount < 0 || skipBytesCount > length) {
                log("CRITICAL: Invalid write parameters - length: " + length + ", skipBytesCount: " + skipBytesCount);
                return; // Abort write operation
            }

            // Prevent excessively large packets that could cause memory issues
            if (length > buffer.length / 2) {
                log("WARNING: Large packet size " + length + " bytes, buffer size: " + buffer.length);
                // Still allow but warn about potential issues
            }

            boolean hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
            boolean hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
            boolean hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;

            int resizeLoopCount = 0;
            while (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                resizeLoopCount++;

                // Prevent infinite loop - if we've tried too many times, force emergency reset
                if (resizeLoopCount > MAX_RESIZE_ATTEMPTS) {
                    log("CRITICAL: Resize loop exceeded " + MAX_RESIZE_ATTEMPTS + " attempts, forcing emergency reset");
                    performEmergencyReset();
                    // After reset, recalculate space availability
                    hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
                    hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
                    hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;

                    // If still no space after emergency reset, packet is too large - drop it
                    if (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                        log("CRITICAL: Packet too large (" + length + " bytes) even after emergency reset - dropping");
                        return;
                    }
                    break;
                }

                reorganizeAndResizeIfNeeded();

                hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
                hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
                hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;
            }

            // 1. write packet length
            writeInt(writePosition, length);
            writePosition += 4;

            // 2. write skip bytes count
            writeInt(writePosition, skipBytesCount);
            writePosition += 4;

            // 3. write data
            if (!hasSpaceAtHead && hasSpaceAtStart) {
                // mark
                lastWritePositionBeforeEnd = writePosition;

                // reset position
                writePosition = 0;
            }

            callback.write(buffer, writePosition);

            // 4. update position
            writePosition += length;

            // 5. update count
            packetCount ++;

            // Debug logging (throttled inside VideoDebugLogger)
            VideoDebugLogger.logRingWrite(length, skipBytesCount, packetCount);
        }
    }


    public void writePacket(byte[] source, int srcOffset, int length) {
        directWriteToBuffer(length, 0, (buf, off) -> System.arraycopy(source, srcOffset, buf, off, length));
    }

    ByteBuffer readPacket() {
        synchronized (this) {
            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0); // Return empty buffer
            }

            // reset position if on the end
            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            // Additional bounds checking
            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0); // Return empty buffer
            }

            // Safe copy prevents race condition with ring buffer writes
            byte[] packetData = new byte[actualLength];
            System.arraycopy(buffer, startPos, packetData, 0, actualLength);
            ByteBuffer result = ByteBuffer.wrap(packetData);

            readPosition += length;
            packetCount--;

            // Debug logging (throttled inside VideoDebugLogger)
            VideoDebugLogger.logRingRead(length, actualLength, packetCount);

            return result;
        }
    }

    /** Read packet directly into target buffer. Returns bytes written or 0 if empty/error. */
    int readPacketInto(ByteBuffer target) {
        synchronized (this) {
            if (packetCount == 0) {
                return 0;
            }

            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return 0;
            }

            // Reset position if on the end
            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            // Calculate actual data bounds
            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return 0;
            }

            // Check target buffer has sufficient space
            if (target.remaining() < actualLength) {
                log("CRITICAL: Target buffer too small - remaining: " + target.remaining() + ", needed: " + actualLength);
                // Don't consume packet if we can't write it
                readPosition -= 8; // Rewind header reads
                return 0;
            }

            // Direct copy from ring buffer to target ByteBuffer (single copy, no intermediate allocation)
            target.put(buffer, startPos, actualLength);

            readPosition += length;
            packetCount--;

            // Debug logging (throttled inside VideoDebugLogger)
            VideoDebugLogger.logRingRead(length, actualLength, packetCount);

            return actualLength;
        }
    }

    private void writeInt(int offset, int value) {
        // Bounds checking to prevent ArrayIndexOutOfBoundsException
        if (offset < 0 || offset + 3 >= buffer.length) {
            log("CRITICAL: writeInt bounds exceeded - offset: " + offset + ", bufferLength: " + buffer.length);
            VideoDebugLogger.logRingBoundsError("writeInt", offset, 4, buffer.length);
            return; // Abort write operation
        }

        buffer[offset]   = (byte) ((value & 0xFF000000) >> 24);
        buffer[offset+1] = (byte) ((value & 0x00FF0000) >> 16);
        buffer[offset+2] = (byte) ((value & 0x0000FF00) >> 8);
        buffer[offset+3] = (byte)  (value & 0x000000FF);
    }

    private int readInt(int offset) {
        // Bounds checking to prevent ArrayIndexOutOfBoundsException
        if (offset < 0 || offset + 3 >= buffer.length) {
            log("CRITICAL: readInt bounds exceeded - offset: " + offset + ", bufferLength: " + buffer.length);
            VideoDebugLogger.logRingBoundsError("readInt", offset, 4, buffer.length);
            return 0; // Return safe default value
        }

        return  ((buffer[offset]   << 24) & 0xFF000000) |
                ((buffer[offset+1] << 16) & 0x00FF0000) |
                ((buffer[offset+2] << 8)  & 0x0000FF00) |
                ((buffer[offset+3])       & 0x000000FF);
    }

    public void reset() {
        packetCount = 0;
        writePosition = 0;
        readPosition = 0;
    }

    private void performEmergencyReset() {
        log("EMERGENCY RESET: " + (buffer.length / (1024*1024)) + "MB -> " + (MIN_BUFFER_SIZE / (1024*1024)) + "MB");
        buffer = new byte[MIN_BUFFER_SIZE];
        readPosition = 0;
        writePosition = 0;
        lastWritePositionBeforeEnd = 0;
        packetCount = 0;
        resizeAttemptCount = 0;

        // Notify listener that all data was lost - typically used to request keyframe
        if (emergencyResetCallback != null) {
            try {
                emergencyResetCallback.onEmergencyReset();
            } catch (Exception e) {
                log("Emergency reset callback failed: " + e.getMessage());
            }
        }
    }
}
