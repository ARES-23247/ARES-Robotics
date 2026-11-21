package com.ares.analytics.desktop

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal sealed interface DesktopTestCommand {
    data class Click(val x: Int, val y: Int) : DesktopTestCommand
    data class Wheel(val x: Int, val y: Int, val rotation: Int) : DesktopTestCommand
    data class Key(val keyCode: Int, val modifiers: Int) : DesktopTestCommand
    data class KeyDown(val keyCode: Int, val modifiers: Int) : DesktopTestCommand
    data class KeyUp(val keyCode: Int, val modifiers: Int) : DesktopTestCommand
    data class Text(val value: String) : DesktopTestCommand
    data class ChoosePath(val value: String) : DesktopTestCommand
    data object Capture : DesktopTestCommand
    data object Close : DesktopTestCommand
    data object Ping : DesktopTestCommand
}

internal object DesktopTestCommandParser {
    fun parse(line: String): DesktopTestCommand {
        val parts = line.trim().split(' ', limit = 3)
        return when (parts.firstOrNull()?.uppercase()) {
            "CLICK" -> {
                require(parts.size == 3) { "CLICK requires x and y coordinates" }
                DesktopTestCommand.Click(parts[1].toInt(), parts[2].toInt())
            }
            "WHEEL" -> {
                val wheelParts = line.trim().split(' ')
                require(wheelParts.size == 4) { "WHEEL requires x, y, and rotation" }
                DesktopTestCommand.Wheel(
                    x = wheelParts[1].toInt(),
                    y = wheelParts[2].toInt(),
                    rotation = wheelParts[3].toInt(),
                )
            }
            "KEY" -> {
                require(parts.size >= 2) { "KEY requires a Java key code" }
                DesktopTestCommand.Key(parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
            }
            "KEY_DOWN" -> {
                require(parts.size >= 2) { "KEY_DOWN requires a Java key code" }
                DesktopTestCommand.KeyDown(parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
            }
            "KEY_UP" -> {
                require(parts.size >= 2) { "KEY_UP requires a Java key code" }
                DesktopTestCommand.KeyUp(parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
            }
            "TEXT" -> {
                require(parts.size == 2) { "TEXT requires one Base64-encoded UTF-8 value" }
                val decoded = Base64.getDecoder().decode(parts[1])
                DesktopTestCommand.Text(String(decoded, StandardCharsets.UTF_8))
            }
            "CHOOSE_PATH" -> {
                require(parts.size == 2) { "CHOOSE_PATH requires one Base64-encoded UTF-8 path" }
                val decoded = Base64.getDecoder().decode(parts[1])
                DesktopTestCommand.ChoosePath(String(decoded, StandardCharsets.UTF_8))
            }
            "CAPTURE" -> DesktopTestCommand.Capture
            "CLOSE" -> DesktopTestCommand.Close
            "PING" -> DesktopTestCommand.Ping
            else -> error("Unsupported desktop test command")
        }
    }
}

/**
 * Explicitly opt-in, loopback-only command transport for visible desktop UI tests.
 *
 * Windows prevents lower-integrity test helpers from injecting input into an elevated Gradle
 * launch. This server keeps the test outside product state and routes commands through the
 * real AWT/Skia event surface. It is created only when the caller supplies an explicit port
 * through [TEST_CONTROL_PORT_ENV]; installed and ordinary developer launches create no socket.
 */
internal class DesktopTestControlServer(
    private val port: Int,
    private val authToken: String? = null,
    private val execute: (DesktopTestCommand) -> String,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val clientSequence = AtomicInteger(0)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var clientExecutor: ExecutorService? = null

    internal val localPort: Int?
        get() = serverSocket?.localPort

    fun start() {
        check(running.compareAndSet(false, true)) { "Desktop test control server is already running" }
        val socket = ServerSocket(port, 4, InetAddress.getLoopbackAddress())
        serverSocket = socket
        val executor = ThreadPoolExecutor(
            2,
            MAX_CLIENT_THREADS,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(MAX_PENDING_CLIENTS),
        ) { runnable ->
            Thread(runnable, "desktop-test-control-client-${clientSequence.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
        clientExecutor = executor
        serverThread = thread(
            start = true,
            isDaemon = true,
            name = "desktop-test-control",
        ) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                try {
                    executor.execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: java.net.Socket) {
        runCatching {
            client.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
            client.use { connection ->
                val rawLine = readBoundedLine(connection.getInputStream(), MAX_COMMAND_LINE_BYTES)
                val response = if (rawLine == null) {
                    "ERROR Command line exceeded $MAX_COMMAND_LINE_BYTES bytes"
                } else {
                    val commandLine = verifyAndStripAuth(rawLine)
                    if (commandLine == null) {
                        "ERROR Unauthorized desktop test control request"
                    } else {
                        runCatching {
                            execute(DesktopTestCommandParser.parse(commandLine))
                        }.fold(
                            onSuccess = { "OK $it" },
                            onFailure = { "ERROR ${it.message ?: it::class.simpleName}" },
                        )
                    }
                }
                connection.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.appendLine(response)
                }
            }
        }.onFailure { failure ->
            if (running.get()) {
                System.err.println(
                    "[ARES-Analytics] Desktop test control client disconnected: " +
                        (failure.message ?: failure::class.simpleName)
                )
            }
        }
    }

    private fun verifyAndStripAuth(rawLine: String): String? {
        val expected = authToken ?: return rawLine
        val parts = rawLine.trim().split(' ', limit = 3)
        if (parts.size < 3 || !parts[0].equals("AUTH", ignoreCase = true)) return null
        val suppliedBytes = parts[1].toByteArray(StandardCharsets.UTF_8)
        val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
        return if (MessageDigest.isEqual(suppliedBytes, expectedBytes)) parts[2] else null
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        clientExecutor?.shutdownNow()
        serverThread?.join(1_000)
        clientExecutor?.awaitTermination(1_000, TimeUnit.MILLISECONDS)
        serverThread = null
        clientExecutor = null
        serverSocket = null
    }

    companion object {
        internal const val CLIENT_SOCKET_TIMEOUT_MS = 5_000
        internal const val MAX_COMMAND_LINE_BYTES = 8 * 1024
        private const val MAX_CLIENT_THREADS = 4
        private const val MAX_PENDING_CLIENTS = 8

        internal fun readBoundedLine(input: InputStream, maxBytes: Int): String? {
            val out = ByteArrayOutputStream(minOf(maxBytes, 256))
            while (true) {
                val b = input.read()
                if (b == -1 || b == '\n'.code) break
                if (b == '\r'.code) continue
                if (out.size() >= maxBytes) return null
                out.write(b)
            }
            return out.toString(StandardCharsets.UTF_8)
        }
    }
}
