package com.ares.analytics.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsProcessAdapterTest {
    private val directory = Files.createTempDirectory("ares-windows-process-test-").toFile()
    @AfterTest fun cleanup() { directory.deleteRecursively() }
    private fun windows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

    @Test fun `actual Authenticode adapter handles spaces quotes and brackets as literal data`() = runBlocking {
        windows()
        val signed = File(System.getenv("WINDIR"), "System32/WindowsPowerShell/v1.0/powershell.exe")
        val fixture = File(directory, "signed [1] dollar$ apostrophe' installer.exe")
        signed.copyTo(fixture)
        val signature = PowerShellAuthenticodeVerifier().verify(fixture)
        assertTrue(signature.valid)
        assertNotNull(signature.thumbprint)
        val unsigned = File(directory, "unsigned [2].msi").apply { writeText("not signed") }
        assertFalse(PowerShellAuthenticodeVerifier().verify(unsigned).valid)
    }

    @Test fun `deadline kills a child that keeps stdout open`() = runBlocking {
        windows()
        val pidFile = File(directory, "pid")
        val builder = helper(pidFile)
        val result = withTimeout(5000) { runBoundedProcess(builder, 1500) }
        assertNull(result)
        val pid = pidFile.readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test fun `cancellation terminates and joins the owned child`() = runBlocking {
        windows()
        val pidFile = File(directory, "pid")
        val job = launch { runBoundedProcess(helper(pidFile), 30000) }
        withTimeout(5000) { while (!pidFile.exists() || pidFile.length() == 0L) delay(25) }
        val pid = pidFile.readText().trim().toLong()
        job.cancelAndJoin()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    private fun helper(pidFile: File) = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "[IO.File]::WriteAllText(${'$'}env:ARES_TEST_PID, [string]${'$'}PID); Start-Sleep -Seconds 60",
    ).apply { environment()["ARES_TEST_PID"] = pidFile.absolutePath }
}
