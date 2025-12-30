package com.carlink.util;

/**
   * AppExecutors - Centralized Thread Management for Carlink Android Plugin
   *
   * PURPOSE:
   * Manages concurrent execution contexts for USB communication and H.264 video decoding
   * during Android Auto/CarPlay projection with the CPC200-CCPA adapter.
   *
   * THREAD POOLS:
   * - usbIn/usbOut: Single-threaded executors for serial USB I/O with the adapter
   * - mediaCodec1/mediaCodec2: Multi-threaded pools for H.264 decode pipeline (input/output)
   * - mediaCodec: General-purpose background executor for codec operations
   * - mainThread: UI thread executor for Flutter platform channel callbacks
   *
   * OPTIMIZATION:
   * MediaCodec thread pools are tuned for the GM Infotainment (IOK) hardware:
   * - Intel Atom x7-A3960 quad-core processor
   * - Dynamic scaling: 2 core threads, up to 4 max threads
   * - THREAD_PRIORITY_DISPLAY for low-latency video rendering
   * - 128-task queue sized for 6GB RAM environment
   *
   * LIFECYCLE:
   * Instance-based pattern with clear ownership. Each plugin instance owns its AppExecutors.
   * Call shutdown() when plugin detaches to prevent memory leaks. Instance is automatically
   * garbage collected after shutdown.
   *
   * THREAD SAFETY:
   * All executors are thread-safe. No shared state across plugin instances.
   */
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AppExecutors
{
    private static class BackgroundThreadExecutor implements Executor
    {
        private final Executor executor;

        public BackgroundThreadExecutor()
        {
            executor = Executors.newSingleThreadExecutor();
        }

        @Override
        public void execute(@NonNull Runnable command)
        {
            executor.execute(command);
        }

        // Following Android threading guidelines for proper ExecutorService cleanup
        public void shutdown() {
            if (executor instanceof java.util.concurrent.ExecutorService) {
                ((java.util.concurrent.ExecutorService) executor).shutdown();
            }
        }

        public void shutdownNow() {
            if (executor instanceof java.util.concurrent.ExecutorService) {
                ((java.util.concurrent.ExecutorService) executor).shutdownNow();
            }
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            if (executor instanceof java.util.concurrent.ExecutorService) {
                return ((java.util.concurrent.ExecutorService) executor).awaitTermination(timeout, unit);
            }
            return true; // Non-ExecutorService executors are considered terminated
        }
    }

    // Optimized thread pool for Intel Atom x7-A3960 quad-core
    private static class OptimizedMediaCodecExecutor implements Executor {
        private final ThreadPoolExecutor executor;
        private final int androidPriority;

        private OptimizedMediaCodecExecutor(String executorName, int androidPriority) {
            // Get available CPU cores for Intel Atom x7-A3960 (should be 4)
            int numberOfCores = Runtime.getRuntime().availableProcessors();

            // Create optimized thread pool based on Android best practices
            // Core pool: half cores, Max pool: all cores to utilize quad-core efficiently
            this.executor = new ThreadPoolExecutor(
                    Math.max(1, numberOfCores / 2), // corePoolSize - utilize half cores initially
                    numberOfCores, // maximumPoolSize - can scale to all cores under load
                    60L, // keepAliveTime - standard Android recommendation
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(128), // Larger queue for 6GB RAM system
                    r -> {
                        Thread t = new Thread(r, executorName);
                        // Don't set Thread priority here - it will be set in the execute method
                        return t;
                    }
            );
            this.executor.allowCoreThreadTimeOut(true); // Allow core threads to timeout for efficiency
            this.androidPriority = androidPriority;
        }

        @Override
        public void execute(@NonNull Runnable command) {
            // Wrap command to set Android thread priority properly
            executor.execute(() -> {
                Process.setThreadPriority(androidPriority);
                command.run();
            });
        }

        // Following Android threading guidelines for proper ThreadPoolExecutor cleanup
        public void shutdown() {
            executor.shutdown();
        }

        public void shutdownNow() {
            executor.shutdownNow();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return executor.awaitTermination(timeout, unit);
        }
    }

    private static class MainThreadExecutor implements Executor
    {
        private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable command)
        {
            mainThreadHandler.post(command);
        }

        // Following Android threading guidelines for Handler cleanup
        public void shutdown() {
            // Remove all pending messages and callbacks to prevent memory leaks
            mainThreadHandler.removeCallbacksAndMessages(null);
        }
    }

    private final BackgroundThreadExecutor usbIn;
    private final BackgroundThreadExecutor usbOut;
    private final OptimizedMediaCodecExecutor mediaCodec1;
    private final OptimizedMediaCodecExecutor mediaCodec2;
    private final BackgroundThreadExecutor mediaCodec;
    private final MainThreadExecutor mainThread;

    public AppExecutors()
    {
        usbIn = new BackgroundThreadExecutor();
        usbOut = new BackgroundThreadExecutor();
        mediaCodec = new BackgroundThreadExecutor();

        // MediaCodec executors optimized according to Android MediaCodec best practices
        // Output thread should have equal or higher priority than input for smooth playback
        mediaCodec1 = new OptimizedMediaCodecExecutor("MediaCodec-Input", android.os.Process.THREAD_PRIORITY_DISPLAY);
        mediaCodec2 = new OptimizedMediaCodecExecutor("MediaCodec-Output", android.os.Process.THREAD_PRIORITY_DISPLAY);

        mainThread = new MainThreadExecutor();
    }

    public Executor usbIn()
    {
        return usbIn;
    }

    public Executor usbOut()
    {
        return usbOut;
    }

    public Executor mediaCodec() {
        return mediaCodec;
    }

    public Executor mediaCodec1() {
        return mediaCodec1;
    }

    public Executor mediaCodec2() {
        return mediaCodec2;
    }

    public Executor mainThread()
    {
        return mainThread;
    }

    /**
     * Shutdown all executors following Android threading guidelines.
     * Should be called when the plugin is detached to prevent memory leaks.
     */
    public void shutdown() {
        try {
            // Shutdown background executors
            usbIn.shutdown();
            usbOut.shutdown();
            mediaCodec.shutdown();

            // Shutdown optimized MediaCodec executors
            mediaCodec1.shutdown();
            mediaCodec2.shutdown();

            // Clean up main thread handler
            mainThread.shutdown();
        } catch (Exception e) {
            // Log error but continue shutdown process
            android.util.Log.w("AppExecutors", "Error during shutdown: " + e.getMessage());
        }
    }

    /**
     * Immediate shutdown of all executors with task interruption.
     * Use only in emergency situations.
     */
    public void shutdownNow() {
        try {
            // Force shutdown background executors
            usbIn.shutdownNow();
            usbOut.shutdownNow();
            mediaCodec.shutdownNow();

            // Force shutdown optimized MediaCodec executors
            mediaCodec1.shutdownNow();
            mediaCodec2.shutdownNow();

            // Clean up main thread handler
            mainThread.shutdown();
        } catch (Exception e) {
            android.util.Log.w("AppExecutors", "Error during forced shutdown: " + e.getMessage());
        }
    }

    /**
     * Wait for all executors to terminate following Android threading guidelines.
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if all executors terminated within the timeout
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        try {
            long timeoutMillis = unit.toMillis(timeout);

            // Wait for each executor with proportional timeout
            long perExecutorTimeout = timeoutMillis / 5; // 5 executors total

            return usbIn.awaitTermination(perExecutorTimeout, TimeUnit.MILLISECONDS) &&
                   usbOut.awaitTermination(perExecutorTimeout, TimeUnit.MILLISECONDS) &&
                   mediaCodec.awaitTermination(perExecutorTimeout, TimeUnit.MILLISECONDS) &&
                   mediaCodec1.awaitTermination(perExecutorTimeout, TimeUnit.MILLISECONDS) &&
                   mediaCodec2.awaitTermination(perExecutorTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

}
