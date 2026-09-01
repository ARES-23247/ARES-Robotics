package com.ares.analytics.desktop

import java.nio.charset.StandardCharsets
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTestControlServerTest {
    @Test
    fun `parses supported visible UI commands`() {
        assertEquals(DesktopTestCommand.Click(12, 34), DesktopTestCommandParser.parse("CLICK 12 34"))
        assertEquals(DesktopTestCommand.Wheel(12, 34, 5), DesktopTestCommandParser.parse("WHEEL 12 34 5"))
        assertEquals(DesktopTestCommand.Key(65, 128), DesktopTestCommandParser.parse("KEY 65 128"))
        assertEquals(DesktopTestCommand.KeyDown(87, 0), DesktopTestCommandParser.parse("KEY_DOWN 87"))
        assertEquals(DesktopTestCommand.KeyUp(87, 0), DesktopTestCommandParser.parse("KEY_UP 87 0"))
        assertEquals(DesktopTestCommand.Capture, DesktopTestCommandParser.parse("CAPTURE"))
        assertEquals(DesktopTestCommand.Close, DesktopTestCommandParser.parse("CLOSE"))
        assertEquals(DesktopTestCommand.Ping, DesktopTestCommandParser.parse("PING"))

        val encoded = Base64.getEncoder().encodeToString("Robot π".toByteArray(StandardCharsets.UTF_8))
        assertEquals(DesktopTestCommand.Text("Robot π"), DesktopTestCommandParser.parse("TEXT $encoded"))

        val encodedPath = Base64.getEncoder().encodeToString("C:\\robots\\Lightbot".toByteArray(StandardCharsets.UTF_8))
        assertEquals(
            DesktopTestCommand.ChoosePath("C:\\robots\\Lightbot"),
            DesktopTestCommandParser.parse("CHOOSE_PATH $encodedPath"),
        )
    }

    @Test
    fun `rejects malformed or unknown commands`() {
        assertFailsWith<IllegalArgumentException> { DesktopTestCommandParser.parse("CLICK 12") }
        assertFailsWith<IllegalArgumentException> { DesktopTestCommandParser.parse("WHEEL 12 34") }
        assertFailsWith<IllegalArgumentException> { DesktopTestCommandParser.parse("KEY") }
        assertFailsWith<IllegalArgumentException> { DesktopTestCommandParser.parse("CHOOSE_PATH") }
        assertFailsWith<IllegalStateException> { DesktopTestCommandParser.parse("DELETE EVERYTHING") }
    }

    @Test
    fun `accepts another command while a modal UI command is still running`() {
        val modalCommandStarted = CountDownLatch(1)
        val releaseModalCommand = CountDownLatch(1)
        DesktopTestControlServer(port = 0) { command ->
            if (command is DesktopTestCommand.Click) {
                modalCommandStarted.countDown()
                assertTrue(releaseModalCommand.await(2, TimeUnit.SECONDS))
                "clicked"
            } else {
                "pong"
            }
        }.use { server ->
            server.start()
            val port = assertNotNull(server.localPort)
            val modalClient = thread(isDaemon = true) {
                sendCommand(port, "CLICK 12 34")
            }

            assertTrue(modalCommandStarted.await(1, TimeUnit.SECONDS))
            assertEquals("OK pong", sendCommand(port, "PING"))
            releaseModalCommand.countDown()
            modalClient.join(1_000)
            assertTrue(!modalClient.isAlive)
        }
    }

    @Test
    fun `continues accepting commands after a client resets before reading its response`() {
        val invocation = AtomicInteger()
        val firstCommandReceived = CountDownLatch(1)
        DesktopTestControlServer(port = 0) {
            if (invocation.incrementAndGet() == 1) {
                firstCommandReceived.countDown()
                Thread.sleep(150)
            }
            "pong"
        }.use { server ->
            server.start()
            val port = assertNotNull(server.localPort)

            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.setSoLinger(true, 0)
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.appendLine("PING")
                    writer.flush()
                    assertTrue(firstCommandReceived.await(1, TimeUnit.SECONDS))
                }
            }

            Thread.sleep(250)
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 1_000
                socket.getOutputStream().bufferedWriter().apply {
                    appendLine("PING")
                    flush()
                }
                assertEquals("OK pong", socket.getInputStream().bufferedReader().readLine())
            }
        }
    }

    private fun sendCommand(port: Int, command: String): String =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 1_000
            socket.getOutputStream().bufferedWriter().apply {
                appendLine(command)
                flush()
            }
            socket.getInputStream().bufferedReader().readLine()
        }
}
