package com.areslib.telemetry.web

import com.areslib.telemetry.RobotStatusTracker
import java.io.File
import java.io.OutputStream
import java.net.Socket
import java.io.InputStream
import java.io.IOException

/**
 * Class implementation for Log Endpoint Handler.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
class LogEndpointHandler(private val logDir: File, private val authToken: String? = null) {

    /**
     * handleClient declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val firstLine = readHttpLine(input, MAX_REQUEST_LINE_BYTES) ?: return

            val tokens = firstLine.split(" ")
            if (tokens.size < 2) {
                sendErrorResponse(client, 400, "Bad Request")
                return
            }
            val method = tokens[0]
            val fullPath = tokens[1]

            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            var headerBytes = 0
            while (true) {
                val line = readHttpLine(input, MAX_HEADER_LINE_BYTES)
                if (line == null || line.trim().isEmpty()) break
                headerCount++
                headerBytes += line.length
                if (headerCount > MAX_HEADER_COUNT || headerBytes > MAX_HEADER_BYTES) {
                    sendErrorResponse(client, 431, "Request Header Fields Too Large")
                    return
                }
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            if (method == "OPTIONS") {
                sendOptionsResponse(client)
                return
            }

            val questionIdx = fullPath.indexOf('?')
            val path = if (questionIdx != -1) fullPath.substring(0, questionIdx) else fullPath
            val query = if (questionIdx != -1) fullPath.substring(questionIdx + 1) else ""

            // When a bearer token is configured, require it on every /api/* route
            // (CORS preflight OPTIONS above is exempt so browser preflights still succeed).
            if (authToken != null && !isAuthorized(headers)) {
                sendErrorResponse(client, 401, "Unauthorized")
                return
            }

            when (path) {
                "/api/status" -> handleStatus(client, method, headers)
                "/api/logs" -> handleLogs(client, method)
                "/api/logs/download" -> handleDownload(client, method, query)
                "/api/logs/markSynced" -> handleMarkSynced(client, method, query)
                "/api/limelight/stream" -> handleLimelightStream(client, method)
                else -> sendErrorResponse(client, 404, "Not Found")
            }
        } catch (e: Exception) {
            // Socket or parsing error
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpLine(input: InputStream, maximumBytes: Int): String? {
        val line = StringBuilder(kotlin.math.min(maximumBytes, 128))
        while (true) {
            val next = input.read()
            if (next == -1) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString()
            if (next != '\r'.code) {
                if (line.length >= maximumBytes) throw IOException("HTTP line exceeds $maximumBytes bytes")
                line.append(next.toChar())
            }
        }
    }

    private fun writeCorsHeaders(out: OutputStream) {
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n".toByteArray())
        out.write("Access-Control-Allow-Headers: Content-Type, Authorization\r\n".toByteArray())
        out.write("Access-Control-Allow-Private-Network: true\r\n".toByteArray())
    }

    private fun sendOptionsResponse(client: Socket) {
        val out = client.getOutputStream()
        out.write("HTTP/1.1 204 No Content\r\n".toByteArray())
        writeCorsHeaders(out)
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun sendJsonResponse(client: Socket, status: Int, statusText: String, json: String) {
        val out = client.getOutputStream()
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $status $statusText\r\n".toByteArray())
        writeCorsHeaders(out)
        out.write("Content-Type: application/json\r\n".toByteArray())
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun sendErrorResponse(client: Socket, code: Int, message: String) {
        sendJsonResponse(client, code, message, "{\"error\": \"$message\"}")
    }

    /**
     * Validates the `Authorization: Bearer <token>` header against the configured [authToken].
     * Returns `true` if no token is configured (open mode) or the header matches.
     */
    private fun isAuthorized(headers: Map<String, String>): Boolean {
        if (authToken == null) return true
        return headers["authorization"] == "Bearer $authToken"
    }

    private fun handleStatus(client: Socket, method: String, headers: Map<String, String>) {
        if (method != "GET") {
            sendErrorResponse(client, 405, "Method Not Allowed")
            return
        }

        val host = headers["host"] ?: "localhost:8082"
        val hostIp = host.split(":").firstOrNull() ?: "localhost"
        val camerasJson = RobotStatusTracker.activeLimelightIps.mapIndexed { index, ip ->
            val streamPort = 5800 + (index * 2)
            val configPort = 5801 + (index * 2)
            """{
                "name": "limelight-${index + 1}",
                "ip": "$ip",
                "streamUrl": "http://$hostIp:$streamPort",
                "configUrl": "http://$hostIp:$configPort"
            }"""
        }.joinToString(",")

        val activeFileJson = if (RobotStatusTracker.activeUploadFile != null) "\"${RobotStatusTracker.activeUploadFile}\"" else "null"
        val response = """{
            "enabled": ${RobotStatusTracker.isEnabled},
            "opMode": "${RobotStatusTracker.activeOpMode}",
            "vision": {
                "connected": ${RobotStatusTracker.visionConnected},
                "status": "${RobotStatusTracker.visionStatus}",
                "cameras": [$camerasJson]
            },
            "odometry": {
                "source": "${RobotStatusTracker.odometrySource}",
                "status": "${RobotStatusTracker.odometryStatus}"
            },
            "upload": {
                "activeFile": $activeFileJson,
                "progress": ${RobotStatusTracker.uploadProgress}
            }
        }""".trimIndent()

        sendJsonResponse(client, 200, "OK", response)
    }

    private fun handleLogs(client: Socket, method: String) {
        if (method != "GET") {
            sendErrorResponse(client, 405, "Method Not Allowed")
            return
        }
        val fileList = LogArchivePackager.listLogFiles(logDir).joinToString(",") { "\"${it}\"" }
        sendJsonResponse(client, 200, "OK", "[$fileList]")
    }

    private fun handleDownload(client: Socket, method: String, query: String) {
        if (method != "GET") {
            sendErrorResponse(client, 405, "Method Not Allowed")
            return
        }

        val fileName = query.split("&").firstOrNull { it.startsWith("file=") }?.substringAfter("file=")
        if (fileName == null) {
            sendErrorResponse(client, 400, "Bad Request: Missing 'file' parameter")
            return
        }

        if (!LogArchivePackager.isValidLogFile(logDir, fileName)) {
            sendErrorResponse(client, 404, "Log file not found")
            return
        }

        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        writeCorsHeaders(out)
        val contentType = if (fileName.endsWith(".jsonl")) "application/x-jsonlines" else "text/csv"
        out.write("Content-Type: $contentType\r\n".toByteArray())
        out.write("Content-Length: ${LogArchivePackager.getFileLength(logDir, fileName)}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())

        LogArchivePackager.streamLogFile(logDir, fileName, out)
        out.flush()
    }

    private fun handleMarkSynced(client: Socket, method: String, query: String) {
        if (method != "POST") {
            sendErrorResponse(client, 405, "Method Not Allowed")
            return
        }

        val fileName = query.split("&").firstOrNull { it.startsWith("file=") }?.substringAfter("file=")
        if (fileName == null) {
            sendErrorResponse(client, 400, "Bad Request: Missing 'file' parameter")
            return
        }

        if (!LogArchivePackager.isValidLogFile(logDir, fileName)) {
            sendErrorResponse(client, 404, "Log file not found")
            return
        }

        val success = LogArchivePackager.markSynced(logDir, fileName)

        if (success) {
            sendJsonResponse(client, 200, "OK", "{\"success\": true, \"message\": \"Marked file $fileName as synced\"}")
        } else {
            sendErrorResponse(client, 500, "Failed to archive log file")
        }
    }

    private fun handleLimelightStream(client: Socket, method: String) {
        if (method != "GET") {
            sendErrorResponse(client, 405, "Method Not Allowed")
            return
        }

        val ip = RobotStatusTracker.resolvedLimelightIp ?: RobotStatusTracker.activeLimelightIps.firstOrNull()
        if (ip == null) {
            sendErrorResponse(client, 502, "Bad Gateway: Limelight camera not found on USB tether subnets")
            return
        }

        val limelightUrl = java.net.URL("http://$ip:5800/stream.mjpeg")
        var headersSent = false
        try {
            val connection = limelightUrl.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1000
            connection.readTimeout = 15000

            val out = client.getOutputStream()
            out.write("HTTP/1.1 ${connection.responseCode} OK\r\n".toByteArray())
            writeCorsHeaders(out)

            for ((headerKey, headerValues) in connection.headerFields) {
                if (headerKey != null && headerKey.lowercase() != "connection") {
                    for (value in headerValues) {
                        out.write("$headerKey: $value\r\n".toByteArray())
                    }
                }
            }
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.flush()
            headersSent = true

            val input = connection.inputStream
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
                out.flush()
            }
            input.close()
        } catch (e: Exception) {
            RobotStatusTracker.resolvedLimelightIp = null
            if (!headersSent) {
                sendErrorResponse(client, 502, "Bad Gateway: Failed to stream from Limelight at $ip:5800. Error: ${e.message}")
            }
        }
    }

    private companion object {
        const val MAX_REQUEST_LINE_BYTES = 8_192
        const val MAX_HEADER_LINE_BYTES = 8_192
        const val MAX_HEADER_BYTES = 32_768
        const val MAX_HEADER_COUNT = 64
    }
}
