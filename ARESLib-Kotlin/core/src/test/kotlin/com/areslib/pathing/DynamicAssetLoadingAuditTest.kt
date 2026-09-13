package com.areslib.pathing

import com.areslib.action.RobotAction
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Test
import kotlin.test.*

class DynamicAssetLoadingAuditTest {
    private fun withAuto(body: String, action: (String, Path) -> Unit) {
        val name = "audit_" + UUID.randomUUID().toString()
        val directory = Path.of("src/main/deploy/pathplanner/autos")
        Files.createDirectories(directory)
        val file = Files.createFile(directory.resolve("$name.auto"))
        try { Files.writeString(file, body); action(name, file) } finally { Files.delete(file) }
    }

    @Test fun `auto loader reads utf8 and observes edits on subsequent calls`() {
        val initial = """{"note":"café","command":{"type":"wait","data":{"waitTime":0}}}"""
        withAuto(initial) { name, file ->
            assertEquals(initial, DynamicPathLoader.loadAutoJsonString(name))
            val changed = initial.replace("café", "changed")
            Files.writeString(file, changed)
            assertEquals(changed, DynamicPathLoader.loadAutoJsonString(name))
        }
    }

    @Test fun `facade loads auto file and honors requested red geometry`() {
        withAuto("""{"command":{"type":"path","data":{"pathName":"example_path"}}}""") { name, _ ->
            val task = AutoBuilder().configureFollower(auditFollower()).buildAuto(name, 0, Alliance.RED)
            try {
                val path = task.initialize(RobotState()).filterIsInstance<RobotAction.SwitchPath>().single().path
                assertEquals(-1.0, path.points.last().pose.y, 1e-9)
            } finally { task.end(RobotState(), true); task.reset() }
        }
    }

    @Test fun `missing asset diagnostics enumerate file and classpath candidates`() {
        val name = "audit_missing_" + UUID.randomUUID().toString()
        val error = assertFailsWith<IOException> { DynamicPathLoader.loadAutoJsonString(name) }
        assertTrue(error.message.orEmpty().contains("$name.auto"))
        assertTrue(error.message.orEmpty().contains("/deploy/pathplanner/autos/"))
        assertFailsWith<IOException> { DynamicPathLoader.loadPath(name) }
        for (bad in listOf("", ".", "..", "../x", "x/y", "x\\y", "C:x", "x\n", "a".repeat(129))) {
            assertFailsWith<IllegalArgumentException> { DynamicPathLoader.loadPath(bad) }
            assertFailsWith<IllegalArgumentException> { DynamicPathLoader.loadAutoJsonString(bad) }
        }
    }

    @Test fun `oversized filesystem auto fails before whole input is materialized`() {
        withAuto(" ".repeat(4_194_305)) { name, _ ->
            assertFailsWith<IllegalArgumentException> { DynamicPathLoader.loadAutoJsonString(name) }
        }
    }
}
