package com.carlink.logging

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent log storage with 5MB rotation, 7-day retention, and async writes. */
@Suppress("StaticFieldLeak")
class FileLogManager(
    context: Context,
    private val sessionPrefix: String = "carlink",
    private val appVersion: String = "1.0.0",
) : Logger.LogListener {
    private val appContext: Context = context.applicationContext

    companion object {
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5MB
        private const val RETENTION_DAYS = 7
        private const val LOGS_DIR = "logs"
        private const val FLUSH_INTERVAL_MS = 1000L

        @Volatile
        private var instance: FileLogManager? = null

        fun getInstance(context: Context): FileLogManager =
            instance ?: synchronized(this) {
                instance ?: FileLogManager(context.applicationContext).also { instance = it }
            }
    }

    private val logsDir: File
    private var currentLogFile: File? = null
    private var currentWriter: OutputStreamWriter? = null
    private val sessionId: String

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val isEnabled = AtomicBoolean(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    init {
        logsDir =
            File(appContext.filesDir, LOGS_DIR).apply {
                if (!exists()) mkdirs()
            }

        sessionId = "${sessionPrefix}_${fileDateFormat.format(Date())}"

        executor.scheduleAtFixedRate(
            { flushQueue() },
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun enable() {
        if (isEnabled.getAndSet(true)) {
            return
        }

        createNewLogFile()
        Logger.addListener(this)
        writeHeader()
        cleanOldLogs()

        logInfo("File logging enabled: ${currentLogFile?.name}", tag = Logger.Tags.FILE_LOG)
    }

    fun disable() {
        if (!isEnabled.getAndSet(false)) {
            return
        }

        Logger.removeListener(this)
        flushQueue()
        closeWriter()

        logInfo("File logging disabled", tag = Logger.Tags.FILE_LOG)
    }

    fun release() {
        disable()
        executor.shutdownNow()
    }

    fun getLogFiles(): List<File> =
        logsDir
            .listFiles { file ->
                file.isFile && file.name.endsWith(".log")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun getCurrentLogFile(): File? = currentLogFile

    fun deleteLogFile(file: File): Boolean =
        try {
            if (file == currentLogFile) {
                closeWriter()
                createNewLogFile()
            }
            file.delete()
        } catch (e: Exception) {
            logError("Failed to delete log file: ${e.message}", tag = Logger.Tags.FILE_LOG)
            false
        }

    fun deleteAllLogs() {
        closeWriter()
        getLogFiles().forEach { it.delete() }
        createNewLogFile()
    }

    fun getTotalLogSize(): Long = getLogFiles().sumOf { it.length() }

    fun getCurrentLogFileSize(): Long = currentLogFile?.length() ?: 0L

    fun isEnabled(): Boolean = isEnabled.get()

    override fun onLog(
        level: Logger.Level,
        tag: String?,
        message: String,
        timestamp: Long,
    ) {
        if (!isEnabled.get()) return

        val formattedTime = dateFormat.format(Date(timestamp))
        val levelStr = level.name.first()
        val tagStr = tag?.let { "[$it] " } ?: ""
        val line = "$formattedTime $levelStr $tagStr$message\n"

        logQueue.offer(line)
    }

    private fun createNewLogFile() {
        closeWriter()

        val fileName = "${sessionId}_${System.currentTimeMillis()}.log"
        currentLogFile = File(logsDir, fileName)

        try {
            currentWriter =
                OutputStreamWriter(
                    FileOutputStream(currentLogFile, true),
                    Charsets.UTF_8,
                )
        } catch (e: Exception) {
            logError("Failed to create log file: ${e.message}", tag = Logger.Tags.FILE_LOG)
        }
    }

    private fun writeHeader() {
        val header =
            buildString {
                appendLine("=".repeat(60))
                appendLine("Carlink Native Log Session")
                appendLine("Session ID: $sessionId")
                appendLine("App Version: $appVersion")
                appendLine("Started: ${dateFormat.format(Date())}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("=".repeat(60))
                appendLine()
            }

        try {
            currentWriter?.write(header)
            currentWriter?.flush()
        } catch (e: Exception) {
            logError("Failed to write log header: ${e.message}", tag = Logger.Tags.FILE_LOG)
        }
    }

    private fun flushQueue() {
        if (!isEnabled.get() || logQueue.isEmpty()) return

        val writer = currentWriter ?: return

        try {
            val batch = StringBuilder()
            var count = 0

            while (count < 100) {
                val line = logQueue.poll() ?: break
                batch.append(line)
                count++
            }

            if (batch.isNotEmpty()) {
                writer.write(batch.toString())
                writer.flush()

                currentLogFile?.let { file ->
                    if (file.length() > MAX_FILE_SIZE) {
                        createNewLogFile()
                        writeHeader()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CARLINK", "Failed to flush log queue: ${e.message}")
        }
    }

    private fun closeWriter() {
        try {
            currentWriter?.flush()
            currentWriter?.close()
        } catch (e: Exception) {
            android.util.Log.w("CARLINK", "Writer close error: ${e.message}")
        }
        currentWriter = null
    }

    private fun cleanOldLogs() {
        val cutoffTime = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)

        getLogFiles()
            .filter { it.lastModified() < cutoffTime }
            .forEach { file ->
                try {
                    file.delete()
                    logInfo("Deleted old log file: ${file.name}", tag = Logger.Tags.FILE_LOG)
                } catch (e: Exception) {
                    logError("Failed to delete old log: ${e.message}", tag = Logger.Tags.FILE_LOG)
                }
            }
    }
}
