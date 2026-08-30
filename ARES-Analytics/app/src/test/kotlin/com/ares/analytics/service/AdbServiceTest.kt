package com.ares.analytics.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbServiceTest {
    @Test
    fun `connection monitoring and logcat have independent lifecycle ownership`() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val service = AdbService(
            monitorConnection = true,
            connectionPollMs = 10L,
            adbPath = { "test-adb" },
            startProcess = { command ->
                synchronized(commands) { commands += command }
                CompletedProcess(
                    output = if (command.last() == "devices") {
                        "List of devices attached\n192.168.43.1:5555\tdevice\n"
                    } else {
                        "08-30 12:00:00.000 I/Robot: ready\n"
                    },
                )
            },
        )

        withTimeout(1_000L) { service.connected.first { it } }
        service.startLogcat()
        assertEquals(
            "08-30 12:00:00.000 I/Robot: ready",
            withTimeout(1_000L) { service.logcatOutput.first { it.startsWith("08-30") } },
        )

        service.shutdownAndJoin()

        assertFalse(service.connected.value)
        val captured = synchronized(commands) { commands.toList() }
        assertTrue(captured.any { it == listOf("test-adb", "devices") })
        assertTrue(captured.any { it == listOf("test-adb", "logcat", "-v", "time") })
    }
}

private class CompletedProcess(output: String) : Process() {
    private val stdout = ByteArrayInputStream(output.toByteArray())
    private val stdin = ByteArrayOutputStream()

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() = Unit
    override fun isAlive(): Boolean = false
}
