package com.areslib.logging

import com.areslib.util.RobotClock
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.io.File
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
 * Requests are limited per remote IP by a ten-token bucket refilled at ten requests per second.
 * Startup failure is logged and leaves the singleton inactive rather than aborting robot startup.
 */
object LogManagerServer : NanoHTTPD(5002) {

    private val gson = Gson()
    private val logDir = RobotLogEnvironment.logDirectory
    private val syncedDir = File(logDir, "synced")
    // Canonical (symlink-resolved) base paths, resolved once at construction to avoid a
    // filesystem syscall on every request.
    private val logDirCanonical: String = logDir.canonicalPath
    private val syncedDirCanonical: String = syncedDir.canonicalPath
    @Volatile
    private var deleteToken: String? = configuredDeleteToken()

    init {
        // Ensure directories exist
        if (!logDir.exists()) logDir.mkdirs()
        if (!syncedDir.exists()) syncedDir.mkdirs()
    }
    
    /** Starts the NanoHTTPD listener if it is not already alive; failures are reported to stderr. */
    fun startServer() {
        if (!this.isAlive) {
            try {
                this.start(SOCKET_READ_TIMEOUT, true)
            } catch (e: Exception) {
                System.err.println("LogManagerServer: Failed to start on port 5002: ${e.message}")
            }
        }
    }

    /** Per-client monotonic token bucket. Access is synchronized because requests are concurrent. */
    private class TokenBucket(val capacity: Int, val refillRatePerSecond: Double) {
        var tokens: Double = capacity.toDouble()
        var lastRefillTime: Long = RobotClock.nanoTime()

        @Synchronized
        fun tryConsume(): Boolean {
            val now = RobotClock.nanoTime()
            val elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0
            tokens = kotlin.math.min(capacity.toDouble(), tokens + elapsedSeconds * refillRatePerSecond)
            lastRefillTime = now

            return if (tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    private val rateLimiters = java.util.concurrent.ConcurrentHashMap<String, TokenBucket>()

    /** Routes one request, applying rate limiting before endpoint validation. */
    override fun serve(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress ?: "unknown"
        val bucket = rateLimiters.getOrPut(ip) { TokenBucket(10, 10.0) }
        
        if (!bucket.tryConsume()) {
            val status429 = object : Response.IStatus {
                override fun getDescription() = "429 Too Many Requests"
                override fun getRequestStatus() = 429
            }
            return newFixedLengthResponse(status429, MIME_PLAINTEXT, "429 Too Many Requests")
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
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: " + e.message)
        }
    }

    private fun serveApiLogs(): Response {
        val allFiles = mutableListOf<LogFileInfo>()
        
        // Unsynced logs
        logDir.listFiles { file -> isCompletedLogFile(file) }?.forEach {
            allFiles.add(createLogFileInfo(it, synced = false))
        }
        
        // Synced logs
        syncedDir.listFiles { file -> isCompletedLogFile(file) }?.forEach {
            allFiles.add(createLogFileInfo(it, synced = true))
        }

        allFiles.sortByDescending { it.lastModifiedMs }

        return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(allFiles))
    }

    private fun handleApiDownload(session: IHTTPSession): Response {
        val fileName = session.parameters["file"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file parameter")
        if (!isSafeCompletedLogRequest(fileName)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
        }

        val file = File(logDir, fileName)
        if (!java.nio.file.Path.of(file.canonicalPath).startsWith(java.nio.file.Path.of(logDirCanonical))) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
        if (!file.exists() || !file.isFile) {
            val syncedFile = File(syncedDir, fileName)
            if (java.nio.file.Path.of(syncedFile.canonicalPath).startsWith(java.nio.file.Path.of(syncedDirCanonical)) &&
                syncedFile.exists() && syncedFile.isFile) {
                return serveFile(syncedFile)
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        return serveFile(file)
    }

    private fun serveFile(file: File): Response {
        val mimeType = if (file.name.endsWith(".jsonl")) "application/x-jsonlines" else "text/csv"
        return try {
            newChunkedResponse(Response.Status.OK, mimeType, file.inputStream())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to read file: ${e.message}")
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
        session.parseBody(HashMap())
        val fileName = session.parameters["file"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error": "Missing file parameter"}""")
        if (!isSafeCompletedLogRequest(fileName)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"Access denied"}""")
        }

        val file = File(logDir, fileName)
        if (!java.nio.file.Path.of(file.canonicalPath).startsWith(java.nio.file.Path.of(logDirCanonical))) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")
        val syncedFile = File(syncedDir, fileName)
        if (!java.nio.file.Path.of(syncedFile.canonicalPath).startsWith(java.nio.file.Path.of(syncedDirCanonical))) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied")

        var deleted = false
        if (file.exists() && file.isFile) deleted = file.delete() || deleted
        if (syncedFile.exists() && syncedFile.isFile) deleted = syncedFile.delete() || deleted

        return if (deleted) {
            newFixedLengthResponse(Response.Status.OK, "application/json", """{"success": true}""")
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error": "File not found or could not be deleted"}""")
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
        deleteToken = normalized
    }

    private fun hasValidDeleteToken(session: IHTTPSession, expected: String): Boolean {
        val authorization = session.headers["authorization"]
        val supplied = when {
            authorization?.startsWith("Bearer ", ignoreCase = true) == true -> authorization.substring(7)
            else -> session.headers["x-ares-delete-token"]
        } ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8)
        )
    }

    private fun configuredDeleteToken(): String? {
        val token = System.getProperty("ares.log.deleteToken")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("ARES_LOG_DELETE_TOKEN")?.takeIf(String::isNotBlank)
        return token?.trim()?.takeIf { it.length >= MIN_DELETE_TOKEN_LENGTH }
    }

    private fun createLogFileInfo(file: File, synced: Boolean): LogFileInfo {
        val lastMod = file.lastModified()
        val fmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(lastMod))
        return LogFileInfo(
            name = file.name,
            sizeBytes = file.length(),
            lastModifiedMs = lastMod,
            lastModifiedFmt = fmt,
            synced = synced,
            isActive = false
        )
    }

    /** True only for regular files whose writer has atomically removed the `.active` reservation. */
    private fun isCompletedLogFile(file: File): Boolean =
        file.isFile && !isActiveLogName(file.name)

    /**
     * Endpoint requests use one basename and let the server search the unsynced and synced roots.
     * Rejecting separators, NTFS stream syntax, and trailing aliases prevents alternate spellings
     * from resolving an active file after the raw suffix check.
     */
    private fun isSafeCompletedLogRequest(fileName: String): Boolean {
        if (fileName.isBlank() || fileName != fileName.trim()) return false
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf(':') >= 0) return false
        if (fileName.endsWith('.') || fileName.endsWith(' ')) return false
        return !isActiveLogName(fileName)
    }

    private fun isActiveLogName(fileName: String): Boolean =
        fileName.trimEnd(' ', '.').endsWith(ACTIVE_LOG_SUFFIX, ignoreCase = true)

    /**
     * Class implementation for Log File Info.
     *
     * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
     */
    data class LogFileInfo(
        val name: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val lastModifiedFmt: String,
        val synced: Boolean,
        val isActive: Boolean = false
    )

    private fun serveDashboard(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>ARES Telemetry | Log Manager</title>
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;600&display=swap" rel="stylesheet">
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
                        font-family: 'Inter', sans-serif;
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
                        font-family: 'Inter', sans-serif;
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
                        if (bytes === 0) return '0 Bytes';
                        const k = 1024;
                        const dm = decimals < 0 ? 0 : decimals;
                        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
                    }

                    async function fetchLogs() {
                        const container = document.getElementById('logs-container');
                        try {
                            const res = await fetch('/api/logs');
                            const logs = await res.json();
                            
                            if (logs.length === 0) {
                                container.innerHTML = '<div class="empty-state">No logs found on device.</div>';
                                return;
                            }

                            container.innerHTML = logs.map(function(log) {
                                return '<div class="glass-card" id="card-' + log.name + '">' +
                                    '<div class="log-info">' +
                                        '<h3>' + log.name + '</h3>' +
                                        '<div class="log-meta">' +
                                            '<span>' + formatBytes(log.sizeBytes) + '</span>' +
                                            '<span>' + log.lastModifiedFmt + '</span>' +
                                            '<span class="badge ' + (log.synced ? '' : 'unsynced') + '">' + (log.synced ? 'Synced' : 'Unsynced') + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="actions">' +
                                        '<button class="btn-delete" onclick="deleteLog(\'' + log.name + '\')">Delete</button>' +
                                    '</div>' +
                                '</div>';
                            }).join('');
                        } catch (e) {
                            container.innerHTML = '<div class="empty-state" style="color: var(--accent-red)">Error loading logs.</div>';
                        }
                    }

                    async function deleteLog(fileName) {
                        if (!confirm('Are you sure you want to delete ' + fileName + '?')) return;
                        
                        const btn = document.querySelector('#card-' + fileName + ' .btn-delete');
                        btn.disabled = true;
                        btn.innerText = 'Deleting...';
                        
                        try {
                            let token = sessionStorage.getItem('aresLogDeleteToken');
                            if (!token) {
                                token = prompt('Enter the ARES log-delete token:');
                                if (!token) throw new Error('Delete token required');
                                sessionStorage.setItem('aresLogDeleteToken', token);
                            }
                            const res = await fetch('/api/delete?file=' + encodeURIComponent(fileName), {
                                method: 'POST',
                                headers: { 'X-ARES-Delete-Token': token }
                            });
                            if (res.ok) {
                                await fetchLogs();
                            } else {
                                alert('Delete failed.');
                                btn.disabled = false;
                                btn.innerText = 'Delete';
                            }
                        } catch (e) {
                            alert('Network error.');
                            btn.disabled = false;
                            btn.innerText = 'Delete';
                        }
                    }

                    // Initial load
                    fetchLogs();
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private const val MIN_DELETE_TOKEN_LENGTH = 16
    private const val ACTIVE_LOG_SUFFIX = ".active"
}
