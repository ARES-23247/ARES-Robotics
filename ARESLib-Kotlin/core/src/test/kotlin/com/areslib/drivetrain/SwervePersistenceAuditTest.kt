package com.areslib.drivetrain

import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwervePersistenceAuditTest {
    @TempDir lateinit var directory: Path
    private var originalRoot: String? = null
    private val canonical = SwerveOffsetData(0.1, 0.2, 0.3, 0.4)

    @BeforeEach fun setup() {
        originalRoot = System.getProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY)
        System.setProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY, directory.toString())
        RobotClock.useMockTime(1000)
    }

    @AfterEach fun restore() {
        if (originalRoot == null) System.clearProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY)
        else System.setProperty(SwerveOffsetManager.STORAGE_ROOT_PROPERTY, originalRoot!!)
        RobotClock.useSystemTime()
    }

    private fun backup(name: String, contents: String, modified: Long): File {
        val parent = SwerveOffsetManager.backupsDir
        assertTrue(parent.isDirectory || parent.mkdirs())
        return File(parent, "swerve_offsets_$name.json").also {
            it.writeText(contents)
            assertTrue(it.setLastModified(modified))
        }
    }

    @Test fun `explicit recovery skips corrupt newest backups without changing startup fallback`() {
        backup("old", canonical.toJsonString(), 1000)
        backup("new", "{broken", 2000)
        backup("empty", "", 3000)
        assertEquals(canonical, SwerveOffsetManager.loadLatestBackup())
        val startup = SwerveOffsetData(0.5, 0.6, 0.7, 0.8)
        assertEquals(startup, SwerveOffsetManager.loadOffsets(startup))
    }

    @Test fun `repeated saves at the same robot timestamp retain separate recovery snapshots`() {
        SwerveOffsetManager.saveRuntimeOffsets(canonical)
        val next = canonical.copy(frontLeft = 0.9)
        SwerveOffsetManager.saveRuntimeOffsets(next)
        val snapshots = SwerveOffsetManager.backupsDir.listFiles()!!.filter { it.isFile }
            .map { SwerveOffsetData.fromJsonString(it.readText()) }
        assertEquals(2, snapshots.size)
        assertEquals(setOf(canonical, next), snapshots.toSet())
        assertEquals(next, SwerveOffsetManager.loadOffsets(canonical))
    }

    @Test fun `backup pruning ignores matching directories and unrelated files`() {
        val parent = SwerveOffsetManager.backupsDir
        assertTrue(parent.mkdirs())
        val dirs = (0 until 15).map { File(parent, "swerve_offsets_directory_$it.json").also { file ->
            assertTrue(file.mkdir())
            assertTrue(file.setLastModified(1000L + it))
        } }
        val unrelated = File(parent, "operator-notes.json").also { it.writeText("retain") }
        SwerveOffsetManager.saveRuntimeOffsets(canonical)
        assertTrue(dirs.all { it.isDirectory }, "Retention must not delete matching directories")
        assertEquals("retain", unrelated.readText())
    }

    @Test fun `retention keeps the ten newest ordinary backup files`() {
        val old = (0 until 12).map { backup("$it", canonical.toJsonString(), 1000L + it) }
        SwerveOffsetManager.saveRuntimeOffsets(canonical)
        assertEquals(10, SwerveOffsetManager.backupsDir.listFiles()!!.count { it.isFile })
        assertTrue(old.take(3).none { it.exists() })
        assertTrue(old.drop(3).all { it.isFile })
        assertFalse(directory.toFile().walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test fun `oversized runtime and invalid recovery files are rejected`() {
        SwerveOffsetManager.runtimeFile.writeText(" ".repeat(100_000))
        assertEquals(canonical, SwerveOffsetManager.loadOffsets(canonical))
        backup("huge", " ".repeat(100_000), 1000)
        assertNull(SwerveOffsetManager.loadLatestBackup())
    }

    @Test fun `failed authoritative write does not create backup or publish success`() {
        assertTrue(SwerveOffsetManager.runtimeFile.mkdir())
        File(SwerveOffsetManager.runtimeFile, "keep").writeText("occupied")
        val telemetry = RecordingTelemetry()
        assertFailsWith<Exception> { SwerveOffsetManager.saveRuntimeOffsets(canonical, telemetry) }
        assertEquals(0, telemetry.writes)
        assertFalse(SwerveOffsetManager.backupsDir.exists())
        assertFalse(directory.toFile().walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test fun `backup failure leaves the already committed runtime and suppresses telemetry`() {
        SwerveOffsetManager.backupsDir.writeText("not a directory")
        val telemetry = RecordingTelemetry()
        assertFailsWith<Exception> { SwerveOffsetManager.saveRuntimeOffsets(canonical, telemetry) }
        assertEquals(canonical, SwerveOffsetManager.loadOffsets(SwerveOffsetData()))
        assertEquals(0, telemetry.writes)
    }

    @Test fun `telemetry failure does not undo committed runtime or backup`() {
        val telemetry = RecordingTelemetry(fail = true)
        assertFailsWith<IllegalStateException> { SwerveOffsetManager.saveRuntimeOffsets(canonical, telemetry) }
        assertEquals(canonical, SwerveOffsetManager.loadOffsets(SwerveOffsetData()))
        assertEquals(canonical, SwerveOffsetManager.loadLatestBackup())
    }

    @Test fun `file acquisition and parser agree on the exact character limit`() {
        val text = canonical.toJsonString().padEnd(16_384, ' ')
        assertEquals(canonical, SwerveOffsetData.fromJsonString(text))
        SwerveOffsetManager.runtimeFile.writeText(text)
        assertEquals(canonical, SwerveOffsetManager.loadOffsets(SwerveOffsetData()))
        assertFailsWith<IllegalArgumentException> { SwerveOffsetData.fromJsonString(text + " ") }
        SwerveOffsetManager.runtimeFile.writeText(text + " ")
        assertEquals(SwerveOffsetData(), SwerveOffsetManager.loadOffsets(SwerveOffsetData()))
    }

    @Test fun `strict JSON rejects escaped duplicate keys trailing values and malformed numbers`() {
        val invalid = listOf("", "[]", "null", canonical.toJsonString() + "{}",
            """{"frontLeft":0,"front\u004ceft":1,"frontRight":0,"backLeft":0,"backRight":0}""",
            """{"frontLeft":01,"frontRight":0,"backLeft":0,"backRight":0}""",
            """{"frontLeft":1e999,"frontRight":0,"backLeft":0,"backRight":0}""",
            """{"frontLeft":true,"frontRight":0,"backLeft":0,"backRight":0}""",
            """{"frontLeft":0,"frontRight":0,"backLeft":0,"backRight":0,}""")
        for (json in invalid) assertFailsWith<IllegalArgumentException>(json) {
            SwerveOffsetData.fromJsonString(json)
        }
    }

    @Test fun `all offset components reject nonfinite values including copy`() {
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { canonical.copy(frontLeft = invalid) }
            assertFailsWith<IllegalArgumentException> { canonical.copy(frontRight = invalid) }
            assertFailsWith<IllegalArgumentException> { canonical.copy(backLeft = invalid) }
            assertFailsWith<IllegalArgumentException> { canonical.copy(backRight = invalid) }
        }
    }

    @Test fun `serialization is locale independent with explicit seven decimal precision`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val values = SwerveOffsetData(0.12345674, -0.12345674, 1e-10, -1e-10)
            val json = values.toJsonString()
            assertTrue(json.contains("0.1234567"))
            val parsed = SwerveOffsetData.fromJsonString(json)
            assertEquals(0.1234567, parsed.frontLeft)
            assertEquals(-0.1234567, parsed.frontRight)
            assertEquals(0.0, parsed.backLeft)
            assertEquals(-0.0, parsed.backRight)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    private class RecordingTelemetry(private val fail: Boolean = false) : ITelemetry {
        var writes = 0
        override fun putNumber(key: String, value: Double) { writes++ }
        override fun putBoolean(key: String, value: Boolean) { writes++ }
        override fun putString(key: String, value: String) {
            if (fail) error("Injected telemetry failure")
            writes++
        }
        override fun putDoubleArray(key: String, value: DoubleArray) { writes++ }
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
}
