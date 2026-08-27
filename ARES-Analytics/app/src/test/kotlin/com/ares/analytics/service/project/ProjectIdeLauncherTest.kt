package com.ares.analytics.service.project

import com.ares.analytics.shared.League
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectIdeLauncherTest {
    @Test
    fun `FTC prefers Android Studio while FRC prefers the WPILib toolchain`() {
        val root = File("C:/robots/student")
        val home = File("C:/Users/student")
        val env = mapOf("ProgramFiles" to "C:/Program Files", "LOCALAPPDATA" to "C:/Users/student/AppData/Local")

        assertEquals("Android Studio", preferredIdeCommands(root, League.FTC, "Windows 11", home, env).first().label)
        assertEquals("WPILib VS Code", preferredIdeCommands(root, League.FRC, "Windows 11", home, env).first().label)
    }

    @Test
    fun `launcher uses a reviewed executable and passes the repository as one argument`() {
        val root = Files.createTempDirectory("ares-code-first-project").toFile()
        val ide = File(root, "studio64.exe").apply { writeText("fixture") }
        var started: List<String>? = null
        try {
            val result = ProjectIdeLauncher(
                environment = mapOf("ARES_ANDROID_STUDIO" to ide.path),
                userHome = root,
                osName = "Windows 11",
                processStarter = { started = it },
            ).open(root.path, League.FTC)

            assertTrue(result.launched)
            assertEquals(listOf(ide.absolutePath, root.absolutePath), started)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing IDE returns an actionable result without changing the project`() {
        val root = Files.createTempDirectory("ares-code-first-no-ide").toFile()
        try {
            val result = ProjectIdeLauncher(
                environment = emptyMap(),
                userHome = root,
                osName = "Windows 11",
            ).open(root.path, League.FRC)
            assertFalse(result.launched)
            assertTrue(result.message.contains("WPILib VS Code"))
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `moved repository returns recovery guidance instead of throwing`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "ares-project-that-does-not-exist")
        val result = ProjectIdeLauncher(environment = emptyMap(), userHome = missing.parentFile)
            .open(missing.path, League.FTC)

        assertFalse(result.launched)
        assertTrue(result.message.contains("no longer exists"))
        assertTrue(result.message.contains(missing.absolutePath))
    }
}
