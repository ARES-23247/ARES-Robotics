package com.areslib.logging

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest

/**
 * Robot-local HTTP server for discovering, downloading, and deleting offline log files.
 *
 * The singleton binds port `5002` and exposes `GET /api/logs`, `GET /api/download?file=...`, and
 * `POST /api/delete?file=...`, plus a small browser dashboard at `/`. It serves only files beneath
 * [RobotLogEnvironment.logDirectory] or its `synced` child after canonical-path validation. Read
 * endpoints remain available on the robot LAN, while destructive deletion is disabled until a
 * shared token is explicitly configured through [configureDeleteToken], `ares.log.deleteToken`,
 * or `ARES_LOG_DELETE_TOKEN`. Files ending in `.active` are live writer-owned reservations and are
 * never listed, downloaded, or deleted, regardless of their modification time.
 *
 * Requests use a ten-request burst and ten requests per second per IP, with at most 256 tracked
 * clients. Idle clients expire after one minute. Four workers and sixteen queued connections bound
 * request resources before headers arrive. Authenticated deletion bodies are limited to 4096 bytes.
 * Discovery and download prefer the unsynced copy of a basename; deletion removes both completed
 * copies. Symbolic links and resolved active/outside aliases are excluded. Binding failure through
 * startServer is logged and leaves the singleton inactive rather than aborting robot startup.
 */
object LogManagerServer : NanoHTTPD(5002) {

    private val gson = Gson()
    private val logDir = RobotLogEnvironment.logDirectory
    private val syncedDir = File(logDir, "synced")
    // Canonical (symlink-resolved) base paths, resolved once at construction to avoid a
    // filesystem syscall on every request.
    private val logDirCanonical = logDir.canonicalFile.toPath()
    private val syncedDirCanonical = logDirCanonical.resolve("synced")
    @Volatile
    private var deleteToken: ByteArray? = configuredDeleteToken()

    init {
        // Ensure directories exist
        if (!logDir.exists()) logDir.mkdirs()
        if (!syncedDir.exists()) syncedDir.mkdirs()
    }
    
    /** Starts the local listener; bind failures are logged without aborting robot startup. */
    fun startServer() {
        try { start(SOCKET_READ_TIMEOUT, true) }
        catch (failure: Exception) {
            System.err.println("LogManagerServer: Failed to start on port 5002: ${failure.message}")
        }
    }

    private var requestWorkers: LogServerWorkers? = null
    private val rateLimiters = LogRequestLimiter()

    /** Serializes listener ownership and installs a bounded connection worker pool on each start. */
    @Synchronized
    override fun start(timeout: Int, daemon: Boolean) {
        if (isAlive) return
        // An accept-loop failure can leave client workers alive even though isAlive is false.
        requestWorkers?.closeAll()
        super.stop()
        requestWorkers = null
        val workers = LogServerWorkers(daemon = daemon)
        requestWorkers = workers
        setAsyncRunner(workers)
        try { super.start(timeout, daemon) }
        catch (failure: Throwable) {
            workers.closeAll()
            super.stop()
            requestWorkers = null
            throw failure
        }
    }

    /** Closes owned connections and permits a later start with fresh worker resources. */
    @Synchronized
    override fun stop() {
        try { super.stop() }
        finally {
            requestWorkers?.closeAll()
            requestWorkers = null
            rateLimiters.clear()
        }
    }

    /** Routes one request, applying rate limiting before endpoint validation. */
    override fun serve(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "unknown"
        if (!rateLimiters.tryConsume(ip)) {
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, MIME_PLAINTEXT, "429 Too Many Requests")
        }

        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri == "/" && method == Method.GET -> serveDashboard()
                uri == "/api/logs" && method == Method.GET -> serveApiLogs()
                uri == "/api/download" && method == Method.GET -> handleApiDownload(session)
                uri == "/api/delete" && method == Method.POST -> handleApiDelete(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
            }
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Log request failed")
        }.apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    private fun serveApiLogs(): Response {
        val allFiles = linkedMapOf<String, LogFileInfo>()
        val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        // Requests address a basename. Match both exact spelling and filesystem lookup aliases.
        for ((directory, synced) in listOf(logDir to false, syncedDir to true)) {
            directory.listFiles { file -> isCompletedLogFile(file) }?.forEach { file ->
                if (!allFiles.containsKey(file.name) &&
                    (!synced || !isCompletedLogFile(File(logDir, file.name)))) {
                    allFiles[file.name] = createLogFileInfo(file, synced, formatter)
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(allFiles.values.sortedByDescending { it.lastModifiedMs }))
    }

    private fun handleApiDownload(session: IHTTPSession): Response {
        val fileName = session.parameters["file"]?.singleOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Expected one file parameter")
        if (!isSafeCompletedLogRequest(fileName)) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Access denied")
        val candidates = arrayOf(File(logDir, fileName), File(syncedDir, fileName))
        candidates.firstOrNull(::isCompletedLogFile)?.let { return serveFile(it) }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
    }

    private fun serveFile(file: File): Response {
        val mimeType = when {
            file.name.endsWith(".csv.gz", ignoreCase = true) -> "application/gzip"
            file.name.endsWith(".jsonl", ignoreCase = true) -> "application/x-jsonlines"
            file.name.endsWith(".csv", ignoreCase = true) -> "text/csv"
            file.name.endsWith(".log", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
        return try {
            newChunkedResponse(Response.Status.OK, mimeType,
                Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to read log file")
        }
    }

    private fun handleApiDelete(session: IHTTPSession): Response {
        val configuredToken = deleteToken
            ?: return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "application/json",
                """{"error":"Log deletion is disabled"}"""
            )
        if (!hasValidDeleteToken(session, configuredToken)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                """{"error":"Unauthorized"}"""
            )
        }
        val lengthHeader = session.headers["content-length"]
        val length = if (lengthHeader == null) 0L else lengthHeader.toLongOrNull()
        if (length == null || length < 0L || !session.headers["transfer-encoding"].isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid deletion body framing")
        }
        if (length > MAX_DELETE_BODY_BYTES) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT, "Deletion body too large")
        }
        if (length > 0L) session.parseBody(HashMap())
        val fileName = session.parameters["file"]?.singleOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"Expected one file parameter"}""")
        if (!isSafeCompletedLogRequest(fileName)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"Access denied"}""")
        }
        val files = arrayOf(File(logDir, fileName), File(syncedDir, fileName)).filter(::isCompletedLogFile)
        if (files.isEmpty()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"File not found"}""")
        // Basename deletion removes both completed copies, as requested by existing clients.
        var failed = false
        for (file in files) if (!file.delete()) failed = true
        return if (failed) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"Some log copies could not be deleted"}""")
        } else {
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
        }
    }

    /**
     * Enables destructive log deletion with a shared token, or disables it with `null`.
     * Tokens shorter than 16 characters are rejected to prevent accidental weak field-network
     * credentials. Clients may send `Authorization: Bearer ...` or `X-ARES-Delete-Token`.
     */
    @JvmStatic
    fun configureDeleteToken(token: String?) {
        val normalized = token?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length >= MIN_DELETE_TOKEN_LENGTH) {
            "Log delete token must contain at least $MIN_DELETE_TOKEN_LENGTH characters"
        }
        deleteToken = normalized?.toByteArray(Charsets.UTF_8)
    }

    private fun hasValidDeleteToken(session: IHTTPSession, expected: ByteArray): Boolean {
        val authorization = session.headers["authorization"]
        val supplied = when {
            authorization?.startsWith("Bearer ", ignoreCase = true) == true -> authorization.substring(7)
            else -> session.headers["x-ares-delete-token"]
        } ?: return false
        return MessageDigest.isEqual(
            expected,
            supplied.toByteArray(Charsets.UTF_8)
        )
    }

    private fun configuredDeleteToken(): ByteArray? {
        val token = System.getProperty("ares.log.deleteToken")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("ARES_LOG_DELETE_TOKEN")?.takeIf(String::isNotBlank)
        return token?.trim()?.takeIf { it.length >= MIN_DELETE_TOKEN_LENGTH }?.toByteArray(Charsets.UTF_8)
    }

    private fun createLogFileInfo(file: File, synced: Boolean, formatter: SimpleDateFormat): LogFileInfo {
        val lastMod = file.lastModified()
        val fmt = formatter.format(Date(lastMod))
        return LogFileInfo(
            name = file.name,
            sizeBytes = file.length(),
            lastModifiedMs = lastMod,
            lastModifiedFmt = fmt,
            synced = synced,
            isActive = false
        )
    }

    /** True only for supported completed logs, never abandoned or writer-owned reservations. */
    private fun isCompletedLogFile(file: File): Boolean {
        if (!isSafeCompletedLogRequest(file.name) || !file.isFile || Files.isSymbolicLink(file.toPath())) return false
        val canonical = file.canonicalFile
        if (!isSafeCompletedLogRequest(canonical.name)) return false
        val parent = canonical.parentFile?.toPath()
        return parent == logDirCanonical || parent == syncedDirCanonical
    }

    /**
     * Endpoint requests use one basename and let the server search the unsynced and synced roots.
     * Rejecting separators, NTFS stream syntax, and trailing aliases prevents alternate spellings
     * from resolving an active file after the raw suffix check.
     */
    private fun isSafeCompletedLogRequest(fileName: String): Boolean {
        if (fileName.isBlank() || fileName != fileName.trim() || fileName.any { it.code < 32 || it.code == 127 }) return false
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf(':') >= 0) return false
        if (fileName.endsWith('.') || fileName.endsWith(' ')) return false
        if (isActiveLogName(fileName) || fileName.endsWith(".abandoned", ignoreCase = true)) return false
        return COMPLETED_LOG_SUFFIXES.any { suffix -> fileName.endsWith(suffix, ignoreCase = true) }
    }

    private fun isActiveLogName(fileName: String): Boolean =
        fileName.trimEnd(' ', '.').endsWith(ACTIVE_LOG_SUFFIX, ignoreCase = true)

    /** Completed basename metadata; unsynced content wins if a synced copy has the same name. */
    data class LogFileInfo(
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val lastModifiedFmt: String,
        val synced: Boolean,
        val isActive: Boolean = false
    )

    private val dashboardBytes by lazy { LogDashboardPage.html.toByteArray(Charsets.UTF_8) }
    private fun serveDashboard(): Response = newFixedLengthResponse(
        Response.Status.OK, "text/html; charset=UTF-8", ByteArrayInputStream(dashboardBytes), dashboardBytes.size.toLong()
    )

    private const val MAX_DELETE_BODY_BYTES = 4096L
    private const val MIN_DELETE_TOKEN_LENGTH = 16
    private const val ACTIVE_LOG_SUFFIX = ".active"
    private val COMPLETED_LOG_SUFFIXES = listOf(
        ".csv",
        ".csv.gz",
        ".jsonl",
        ".wpilog",
        ".wpilogxz",
        ".rlog",
        ".revlog",
        ".parquet",
        ".dslog",
        ".dsevents",
        ".log"
    )
}
