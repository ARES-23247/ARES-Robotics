package com.ares.analytics.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CliDriverLauncherTest {
    @Test
    fun `Unix launcher safely quotes paths and target`() {
        val script = CliDriverLauncher.unixScript(File("/tmp/ARES workspace/it's-here"), File("/tmp/cli task.gradle"))

        assertTrue(script.contains("cd '/tmp/ARES workspace/it'\"'\"'s-here' || exit 1"))
        assertTrue(script.contains("-I '/tmp/cli task.gradle'"))
        assertTrue(script.contains(":simulator:runAresCliDriver"))
    }

    @Test
    fun `Gradle init task passes the target without requiring an ARESLib checkout`() {
        val script = CliDriverLauncher.gradleInitScript("10.0.0.2")
        assertTrue(script.contains("project.path == ':simulator'"))
        assertTrue(script.contains("com.areslib.sim.infra.FakeControllerClient"))
        assertTrue(script.contains("args('10.0.0.2')"))
    }
}
