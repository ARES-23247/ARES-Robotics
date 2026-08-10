package com.areslib.action

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActionLoggerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `mutable action is snapshotted and file becomes visible only after drain`() {
        val logger = ActionLogger(
            runId = "run\"quoted",
            robotId = "robot",
            mode = "Tele/op",
            logDirectory = tempDir
        )
        val source = RobotAction.JoystickDriveIntent(
            targetXVelocity = 1.0,
            targetYVelocity = 2.0,
            targetAngularVelocity = 3.0,
            timestampMs = 123L
        )

        logger.logAction(source)
        source.targetXVelocity = 99.0

        val active = tempDir.listFiles()?.singleOrNull { it.name.endsWith(".jsonl.active") }
        assertNotNull(active)
        assertFalse(tempDir.listFiles().orEmpty().any { it.extension == "jsonl" })

        logger.stop()

        assertFalse(active.exists())
        val completed = tempDir.listFiles()?.singleOrNull { it.extension == "jsonl" }
        assertNotNull(completed)
        val root = JsonParser.parseString(completed.readText().trim()).asJsonObject
        assertEquals("run\"quoted", root["run_id"].asString)
        assertEquals("Tele/op", root["op_mode"].asString)
        assertEquals(1.0, root["payload"].asJsonObject["targetXVelocity"].asDouble, 1e-9)
        assertEquals(0L, logger.droppedActionCount)
        assertTrue(completed.name.contains("Tele_op"))
    }
}
