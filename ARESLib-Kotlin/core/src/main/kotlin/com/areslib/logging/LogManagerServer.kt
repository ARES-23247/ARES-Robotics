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

    private val dashboardHtml by lazy {
        """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>ARES Telemetry | Log Manager</title>
                <style>
                    :root {
                        --bg-dark: #0f1115;
                        --glass-bg: rgba(255, 255, 255, 0.05);
                        --glass-border: rgba(255, 255, 255, 0.1);
                        --text-light: #e2e8f0;
                        --text-muted: #94a3b8;
                        --accent-blue: #3b82f6;
                        --accent-blue-hover: #2563eb;
                        --accent-red: #ef4444;
                        --accent-red-hover: #dc2626;
                        --accent-green: #10b981;
                    }
                    body {
                        margin: 0;
                        padding: 0;
                        font-family: 'Segoe UI', Arial, sans-serif;
                        background-color: var(--bg-dark);
                        color: var(--text-light);
                        background-image: radial-gradient(circle at 50% 0%, rgba(59,130,246,0.15), transparent 50%);
                        min-height: 100vh;
                    }
                    .container {
                        max-width: 900px;
                        margin: 0 auto;
                        padding: 2rem;
                    }
                    header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 2rem;
                        border-bottom: 1px solid var(--glass-border);
                        padding-bottom: 1rem;
                    }
                    h1 {
                        margin: 0;
                        font-weight: 600;
                        font-size: 1.8rem;
                        background: linear-gradient(to right, #60a5fa, #a78bfa);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                    }
                    .glass-card {
                        background: var(--glass-bg);
                        backdrop-filter: blur(10px);
                        -webkit-backdrop-filter: blur(10px);
                        border: 1px solid var(--glass-border);
                        border-radius: 12px;
                        padding: 1.5rem;
                        margin-bottom: 1rem;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        transition: transform 0.2s, background 0.2s;
                    }
                    .glass-card:hover {
                        transform: translateY(-2px);
                        background: rgba(255, 255, 255, 0.08);
                    }
                    .log-info h3 {
                        margin: 0 0 0.5rem 0;
                        font-size: 1.1rem;
                        font-weight: 400;
                    }
                    .log-meta {
                        display: flex;
                        gap: 1rem;
                        font-size: 0.85rem;
                        color: var(--text-muted);
                    }
                    .badge {
                        padding: 0.2rem 0.6rem;
                        border-radius: 9999px;
                        font-size: 0.75rem;
                        font-weight: 600;
                        background: rgba(16, 185, 129, 0.1);
                        color: var(--accent-green);
                        border: 1px solid rgba(16, 185, 129, 0.2);
                    }
                    .badge.unsynced {
                        background: rgba(245, 158, 11, 0.1);
                        color: #f59e0b;
                        border-color: rgba(245, 158, 11, 0.2);
                    }
                    .actions {
                        display: flex;
                        gap: 0.5rem;
                    }
                    button {
                        background: none;
                        border: none;
                        padding: 0.5rem 1rem;
                        border-radius: 6px;
                        font-family: 'Segoe UI', Arial, sans-serif;
                        font-size: 0.9rem;
                        font-weight: 600;
                        cursor: pointer;
                        transition: all 0.2s;
                        color: white;
                    }
                    .btn-upload {
                        background-color: var(--accent-blue);
                    }
                    .btn-upload:hover {
                        background-color: var(--accent-blue-hover);
                    }
                    .btn-delete {
                        background-color: transparent;
                        border: 1px solid var(--accent-red);
                        color: var(--accent-red);
                    }
                    .btn-delete:hover {
                        background-color: var(--accent-red);
                        color: white;
                    }
                    .btn-upload:disabled, .btn-delete:disabled {
                        opacity: 0.5;
                        cursor: not-allowed;
                    }
                    .empty-state {
                        text-align: center;
                        padding: 4rem 2rem;
                        color: var(--text-muted);
                        font-size: 1.1rem;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>ARES Telemetry Manager</h1>
                        <button class="btn-upload" onclick="fetchLogs()" style="background-color: rgba(255,255,255,0.1);">Refresh</button>
                    </header>
                    <div id="logs-container">
                        <div class="empty-state">Loading logs...</div>
                    </div>
                </div>

                <script>
                    function formatBytes(bytes, decimals = 2) {
                        if (!Number.isFinite(bytes) || bytes < 0) return 'Unknown size';
                        if (bytes === 0) return '0 Bytes';
                        const sizes = ['Bytes', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB', 'EiB'];
                        const i = Math.max(0, Math.min(sizes.length - 1, Math.floor(Math.log(bytes) / Math.log(1024))));
                        const digits = Number.isFinite(decimals) ? Math.max(0, Math.min(20, Math.trunc(decimals))) : 2;
                        return parseFloat((bytes / Math.pow(1024, i)).toFixed(digits)) + ' ' + sizes[i];
                    }

                    function element(tag, className, text) {
                        const node = document.createElement(tag);
                        if (className) node.className = className;
                        if (text !== undefined) node.textContent = text;
                        return node;
                    }

                    let refreshGeneration = 0;
                    async function fetchLogs() {
                        const generation = ++refreshGeneration;
                        const container = document.getElementById('logs-container');
                        try {
                            const res = await fetch('/api/logs', { cache: 'no-store' });
                            if (!res.ok) throw new Error('Listing failed');
                            const logs = await res.json();
                            if (!Array.isArray(logs)) throw new Error('Invalid listing');
                            if (generation !== refreshGeneration) return;
                            if (logs.length === 0) {
                                container.replaceChildren(element('div', 'empty-state', 'No logs found on device.'));
                                return;
                            }
                            const rows = document.createDocumentFragment();
                            for (const log of logs) {
                                const card = element('div', 'glass-card');
                                const info = element('div', 'log-info');
                                info.append(element('h3', '', log.name));
                                const meta = element('div', 'log-meta');
                                meta.append(element('span', '', formatBytes(log.sizeBytes)));
                                meta.append(element('span', '', log.lastModifiedFmt));
                                meta.append(element('span', 'badge' + (log.synced ? '' : ' unsynced'), log.synced ? 'Synced' : 'Unsynced'));
                                info.append(meta);
                                const actions = element('div', 'actions');
                                const button = element('button', 'btn-delete', 'Delete');
                                button.addEventListener('click', () => deleteLog(log.name, button));
                                actions.append(button);
                                card.append(info, actions);
                                rows.append(card);
                            }
                            container.replaceChildren(rows);
                        } catch (e) {
                            if (generation !== refreshGeneration) return;
                            const message = element('div', 'empty-state', 'Error loading logs.');
                            message.style.color = 'var(--accent-red)';
                            container.replaceChildren(message);
                        }
                    }

                    async function deleteLog(fileName, button) {
                        if (button.disabled || !confirm('Delete ' + fileName + ' and any synced copy?')) return;
                        button.disabled = true;
                        button.textContent = 'Deleting...';
                        try {
                            let token = sessionStorage.getItem('aresLogDeleteToken');
                            if (!token) {
                                token = prompt('Enter the ARES log-delete token:')?.trim();
                                if (!token) return;
                                sessionStorage.setItem('aresLogDeleteToken', token);
                            }
                            const res = await fetch('/api/delete?file=' + encodeURIComponent(fileName), {
                                method: 'POST',
                                headers: { 'X-ARES-Delete-Token': token }
                            });
                            if (res.status === 401) sessionStorage.removeItem('aresLogDeleteToken');
                            if (!res.ok) throw new Error('Delete failed');
                            await fetchLogs();
                        } catch (e) {
                            alert('Delete failed.');
                        } finally {
                            button.disabled = false;
                            button.textContent = 'Delete';
                        }
                    }
                    fetchLogs();
                </script>
            </body>
            </html>
        """.trimIndent()

    }
    private val dashboardBytes by lazy { dashboardHtml.toByteArray(Charsets.UTF_8) }
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
