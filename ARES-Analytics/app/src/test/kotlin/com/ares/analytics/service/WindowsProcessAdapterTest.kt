package com.ares.analytics.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
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
        val started = CompletableDeferred<Process>()
        val result = withTimeout(5000) { runBoundedProcess(helper(), 1500) { started.complete(it) } }
        assertNull(result)
        assertFalse(started.await().isAlive)
    }

    @Test fun `cancellation terminates and joins the owned child`() = runBlocking {
        windows()
        val started = CompletableDeferred<Process>()
        val job = launch { runBoundedProcess(helper(), 30000) { started.complete(it) } }
        val child = withTimeout(5000) { started.await() }
        job.cancelAndJoin()
        assertFalse(child.isAlive)
    }

    @Test fun `observer failure still terminates the owned process`() = runBlocking {
        windows()
        val started = CompletableDeferred<Process>()
        kotlin.test.assertFailsWith<IllegalStateException> {
            runBoundedProcess(helper(), 30000) { started.complete(it); error("observer failed") }
        }
        assertFalse(started.await().isAlive)
    }

    private fun helper() = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "Start-Sleep -Seconds 60",
    )
}
