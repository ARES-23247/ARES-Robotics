package com.ares.analytics.desktop

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal sealed interface DesktopTestCommand {
    data class Click(val x: Int, val y: Int) : DesktopTestCommand
    data class Wheel(val x: Int, val y: Int, val rotation: Int) : DesktopTestCommand
    data class Key(val keyCode: Int, val modifiers: Int) : DesktopTestCommand
    data class KeyDown(val keyCode: Int, val modifiers: Int) : DesktopTestCommand
    data class KeyUp(val keyCode: Int, val modifiers: Int) : DesktopTestCommand
    data class Text(val value: String) : DesktopTestCommand
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
    private val execute: (DesktopTestCommand) -> String,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    internal val localPort: Int?
        get() = serverSocket?.localPort

    fun start() {
        check(running.compareAndSet(false, true)) { "Desktop test control server is already running" }
        val socket = ServerSocket(port, 1, InetAddress.getLoopbackAddress())
        serverSocket = socket
        serverThread = thread(
            start = true,
            isDaemon = true,
            name = "desktop-test-control",
        ) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { connection ->
                        val request = connection.getInputStream().bufferedReader().readLine().orEmpty()
                        val response = runCatching {
                            execute(DesktopTestCommandParser.parse(request))
                        }.fold(
                            onSuccess = { "OK $it" },
                            onFailure = { "ERROR ${it.message ?: it::class.simpleName}" },
                        )
                        connection.getOutputStream().bufferedWriter().use { writer ->
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
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverThread?.join(1_000)
        serverThread = null
        serverSocket = null
    }
}
