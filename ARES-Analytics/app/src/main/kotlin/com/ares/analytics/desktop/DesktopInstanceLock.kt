package com.ares.analytics.desktop

import com.ares.analytics.service.AppDataPaths

/**
 * Single-instance file lock over `~/.ares-analytics/app.lock`. The operating-system lock
 * state — not the file's existence — decides whether a second instance may start, and the
 * OS releases it automatically if the process dies.
 */
internal class DesktopInstanceLock private constructor(
    private val randomAccessFile: java.io.RandomAccessFile,
    private val lock: java.nio.channels.FileLock,
) : AutoCloseable {
    val isHeld: Boolean get() = lock.isValid

    override fun close() {
        runCatching {
            lock.release()
            randomAccessFile.close()
        }.onFailure(Throwable::printStackTrace)
    }

    companion object {
        /** Returns null when another instance holds the lock; the caller must exit quietly. */
        fun tryAcquire(): DesktopInstanceLock? {
            val lockDir = AppDataPaths.rootDirectory()
            lockDir.mkdirs()
            val lockFile = java.io.File(lockDir, "app.lock")
            val randomAccessFile = java.io.RandomAccessFile(lockFile, "rw")
            val lock = try {
                randomAccessFile.channel.tryLock()
            } catch (e: Exception) {
                null
            }
            if (lock == null) {
                runCatching(randomAccessFile::close)
                return null
            }
            return DesktopInstanceLock(randomAccessFile, lock)
        }
    }
}

/**
 * Installs the process-wide uncaught-exception policy: every crash is logged to
 * `~/.ares-analytics/logs/crash-<timestamp>.log`, and a crash on the AWT event thread
 * terminates the process because a windowless JVM must not keep the instance lock alive.
 */
internal object DesktopCrashHandler {
    fun install(onFatalDesktopUiFailure: () -> Nothing) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val fatalDesktopUiFailure = thread.name.startsWith("AWT-EventQueue")
            try {
                val logDir = AppDataPaths.file("logs")
                logDir.mkdirs()
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
                val crashFile = java.io.File(logDir, "crash-$timestamp.log")
                java.io.PrintWriter(java.io.FileWriter(crashFile)).use { writer ->
                    writer.println("Thread: ${thread.name}")
                    writer.println("Timestamp: ${java.time.Instant.now()}")
                    writer.println("Exception: ${throwable.message}")
                    throwable.printStackTrace(writer)
                }
                System.err.println("CRITICAL FAULT: Uncaught exception in thread '${thread.name}'. Log: ${crashFile.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (fatalDesktopUiFailure) {
                    System.err.println(
                        "[ARES-Analytics] Fatal desktop UI failure left no usable window; " +
                            "terminating so the single-instance lock cannot become orphaned."
                    )
                    onFatalDesktopUiFailure()
                }
            }
        }
    }
}
