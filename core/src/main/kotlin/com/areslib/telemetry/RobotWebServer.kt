package com.areslib.telemetry

import com.areslib.telemetry.web.LogEndpointHandler
import com.areslib.telemetry.web.PortForwarder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Global tracker for robot runtime status, exposed to the web portal over Wi-Fi.
 */
object RobotStatusTracker {
    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var activeOpMode: String = "Disabled"

    @Volatile
    var visionConnected: Boolean = false

    @Volatile
    var visionStatus: String = "OFFLINE"

    @Volatile
    var odometrySource: String = "UNINITIALIZED"

    @Volatile
    var odometryStatus: String = "UNKNOWN"

    @Volatile
    var resolvedLimelightIp: String? = null

    @Volatile
    var activeLimelightIps: List<String> = emptyList()

    @Volatile
    var uploadProgress: Double = 0.0

    @Volatile
    var activeUploadFile: String? = null
}

/**
 * Embedded HTTP Server running on the robot (REV Control Hub or RoboRIO).
 * Listens on port 8082 by default, serving state queries and log uploads to the web app.
 * Built using pure Java Socket and ServerSocket to run on Android (REV Control Hub)
 * where com.sun.net.httpserver is unavailable.
 */
object RobotWebServer {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var discoveryThread: Thread? = null
    private val activeForwarders = CopyOnWriteArrayList<PortForwarder>()
    private var executor: ThreadPoolExecutor? = null
    private val rejectedConnections = AtomicInteger()

    /**
     * Shared-secret bearer token required on `/api/` routes when non-null.
     * `null` (default) leaves the server open — set one in production to prevent
     * other devices on the field network from reading or mutating logs.
     */
    private var authToken: String? = null

    private val logDir: File by lazy {
        val javaVendor = System.getProperty("java.vendor") ?: ""
        val isAndroid = javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()
        if (isAndroid) File("/sdcard/FIRST/telemetry_logs/") else File("./logs/")
    }

    @Volatile
    private var endpointHandler: LogEndpointHandler? = null

    /**
     * Starts the web server in a background thread.
     */
    @Synchronized
    fun start(port: Int = 8082, authToken: String? = null) {
        // If the auth token changed (or the handler has not been built yet), rebuild the
        // endpoint handler so it picks up the new token. The previous `by lazy` captured
        // authToken once and silently ignored later start() calls with a different token.
        if (this.authToken != authToken || endpointHandler == null) {
            this.authToken = authToken
            endpointHandler = LogEndpointHandler(logDir, authToken)
        }
        if (serverSocket != null) return
        if (authToken == null) {
            System.err.println(
                "ARES Robot WebServer: WARNING - no auth token configured. /api/* endpoints are " +
                "unauthenticated and reachable by any device on this network. Pass an authToken to start()."
            )
        }
        try {
            if (executor == null || executor!!.isShutdown) {
                executor = ThreadPoolExecutor(
                    MAX_WORKERS,
                    MAX_WORKERS,
                    0L,
                    TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
                    { runnable -> Thread(runnable, "ARES-WebServer-Worker").apply { isDaemon = true } },
                    ThreadPoolExecutor.AbortPolicy()
                ).apply { prestartAllCoreThreads() }
            }

            serverSocket = ServerSocket(port)
            val socket = serverSocket!!

            serverThread = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val client = socket.accept()
                        client.soTimeout = CLIENT_READ_TIMEOUT_MS
                        client.tcpNoDelay = true
                        client.keepAlive = false
                        try {
                            executor?.execute {
                                endpointHandler?.handleClient(client)
                            } ?: client.close()
                        } catch (_: RejectedExecutionException) {
                            rejectedConnections.incrementAndGet()
                            rejectBusyClient(client)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }, "ARES-WebServer-Acceptor").apply {
                isDaemon = true
                start()
            }

            val javaVendor = System.getProperty("java.vendor") ?: ""
            val isAndroid = javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()

            if (isAndroid) {
                // Start discovery thread to scan for active Limelight cameras and configure port forwards
                discoveryThread = Thread {
                    val possibleIps = listOf("172.29.11.7", "172.22.11.2", "limelight.local", "172.29.11.2", "172.29.11.8", "172.29.11.9", "172.22.11.3")
                    var lastIps = emptyList<String>()

                    while (!Thread.currentThread().isInterrupted) {
                        val currentIps = mutableListOf<String>()
                        for (ip in possibleIps) {
                            try {
                                val testSocket = Socket()
                                testSocket.connect(InetSocketAddress(ip, 5800), 150)
                                testSocket.close()
                                currentIps.add(ip)
                            } catch (_: Exception) {}
                        }

                        if (currentIps != lastIps) {
                            // Stop old forwarders
                            for (f in activeForwarders) {
                                f.stopForwarder()
                            }
                            activeForwarders.clear()

                            // Start new forwarders
                            for ((index, ip) in currentIps.withIndex()) {
                                val basePort = 5800 + (index * 2)
                                activeForwarders.add(PortForwarder(basePort, 5800, ip).apply { start() })
                                activeForwarders.add(PortForwarder(basePort + 1, 5801, ip).apply { start() })
                            }
                            lastIps = currentIps
                            RobotStatusTracker.activeLimelightIps = currentIps
                        }

                        try {
                            Thread.sleep(5000)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }.apply {
                    isDaemon = true
                    name = "ARES-LimelightDiscovery-Thread"
                    start()
                }
            }

            println("ARES Robot WebServer started successfully on port $port")
        } catch (e: Exception) {
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            executor?.shutdownNow()
            executor = null
            System.err.println("ARES Robot WebServer: Failed to start on port $port! ${e.message}")
        }
    }

    /**
     * Stops the web server.
     */
    @Synchronized
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        serverThread?.interrupt()
        serverThread = null

        discoveryThread?.interrupt()
        discoveryThread = null

        for (f in activeForwarders) {
            f.stopForwarder()
        }
        activeForwarders.clear()

        executor?.shutdownNow()
        executor = null
    }

    private fun rejectBusyClient(client: Socket) {
        try {
            val body = "{\"error\":\"Server busy\"}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            client.getOutputStream().use { output ->
                output.write("HTTP/1.1 503 Service Unavailable\r\n".toByteArray(Charsets.US_ASCII))
                output.write("Content-Type: application/json\r\n".toByteArray(Charsets.US_ASCII))
                output.write("Content-Length: ${bytes.size}\r\n".toByteArray(Charsets.US_ASCII))
                output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes)
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    internal fun rejectedConnectionCount(): Int = rejectedConnections.get()
    internal const val MAX_WORKERS = 8
    internal const val MAX_QUEUED_CLIENTS = 32
    internal const val CLIENT_READ_TIMEOUT_MS = 2_000
}
