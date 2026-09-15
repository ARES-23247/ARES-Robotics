package com.ares.analytics.service

import com.areslib.simulation.SimulationProductId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class SimulatorRuntimeCleanupTest {
    @Test fun `stop restart and shutdown remove only each owned snapshot`() = runBlocking {
        val project = Files.createTempDirectory("sim-cleanup-project").toFile()
        val unrelated = Files.createTempDirectory("sim-cleanup-neighbor").toFile()
        val service = SimulatorProcessService(aresRepositoryUri = null)
        try {
            unrelated.resolve("keep.txt").writeText("another owner's evidence")
            val command = fixture(project)
            service.start(project.path, SimulationProductId.FTC_DESKTOP_OPMODE, command)
            val first = awaitSnapshot(project)
            assertTrue(first.walkTopDown().any { it.name == "partial.class" })
            service.stopAndJoin()
            assertFalse(first.exists())

            project.resolve("runtime-root.txt").delete()
            service.start(project.path, SimulationProductId.FTC_DESKTOP_OPMODE, command)
            val second = awaitSnapshot(project)
            assertNotEquals(first, second)
            service.shutdownAndJoin()
            assertFalse(second.exists())
            assertTrue(unrelated.resolve("keep.txt").isFile)
        } finally {
            service.shutdownAndJoin()
            project.deleteRecursively()
            unrelated.deleteRecursively()
        }
    }

    @Test fun `normal exit and failed preparation clean snapshots without a Gradle finalizer`() = runBlocking {
        val project = Files.createTempDirectory("sim-cleanup-failure").toFile()
        val service = SimulatorProcessService(aresRepositoryUri = null)
        try {
            for (exitCode in listOf(0, 17)) {
                service.start(project.path, SimulationProductId.FTC_DESKTOP_OPMODE, fixture(project, exitCode))
                withTimeout(10_000) { service.output.first { it.contains("finished with exit code $exitCode") } }
                withTimeout(10_000) { service.state.first { !it.running } }
                val root = File(project.resolve("runtime-root.txt").readText().trim())
                assertFalse(root.exists())
            }
        } finally {
            service.shutdownAndJoin()
            project.deleteRecursively()
        }
    }

    @Test fun `cleanup retains files until its recorded process exits`() {
        val workspace = SimulatorRuntimeWorkspace.create()
        val windows = System.getProperty("os.name").contains("win", true)
        val process = ProcessBuilder(if (windows) listOf("ping", "-n", "30", "127.0.0.1") else listOf("sleep", "30")).start()
        try {
            workspace.directory.resolve("keep.class").writeText("still in use")
            workspace.retainProcessTree(process)
            assertFalse(workspace.cleanup())
            assertTrue(workspace.directory.resolve("keep.class").exists())
        } finally {
            process.destroyForcibly().waitFor()
            assertTrue(workspace.cleanup())
            assertTrue(workspace.cleanup())
        }
    }

    private suspend fun awaitSnapshot(project: File): File = withTimeout(10_000) {
        val receipt = project.resolve("runtime-root.txt")
        while (!receipt.isFile || receipt.length() == 0L) delay(20)
        File(receipt.readText().trim())
    }

    private fun fixture(project: File, exitCode: Int? = null): String {
        // Exercise the legacy exported task's real JVM temporary-directory behavior, without
        // relying on the new explicit ARES_SIM_RUNTIME_ROOT support or a Gradle finalizer.
        project.resolve("LegacySnapshotProbe.java").writeText("""
            import java.nio.file.*;
            class LegacySnapshotProbe {
                public static void main(String[] args) throws Exception {
                    Path snapshot = Files.createTempDirectory("ares-ftc-sim-run-");
                    Files.writeString(snapshot.resolve("partial.class"), "owned fixture");
                    if (!snapshot.getParent().equals(Path.of(System.getenv("ARES_SIM_RUNTIME_ROOT"))))
                        throw new AssertionError("Legacy snapshot escaped the owned parent");
                    Files.writeString(Path.of("runtime-root.txt"), snapshot.getParent().toString());
                    if (args.length > 0) System.exit(Integer.parseInt(args[0]));
                    Thread.sleep(60000);
                }
            }
        """.trimIndent())
        val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").contains("win", true)) ".exe" else "")
        return "\"${java.path}\" LegacySnapshotProbe.java ${exitCode ?: ""}"
    }
}
