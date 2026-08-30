package com.areslib.codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GeneratedFileWriterTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `atomic writer creates parents replaces content and removes temporary files`() {
        val output = root.resolve("generated/nested/Robot.kt")

        GeneratedFileWriter.writeAtomically(output, "first")
        GeneratedFileWriter.writeAtomically(output, "second")

        assertEquals("second", Files.readString(output))
        Files.list(output.parent).use { files ->
            assertTrue(files.map { it.fileName.toString() }.toList() == listOf("Robot.kt"))
        }
    }
}
