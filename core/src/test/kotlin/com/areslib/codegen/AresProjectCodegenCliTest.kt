package com.areslib.codegen

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AresProjectCodegenCliTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `generates checked in source and check mode detects stale edits`() {
        val ares = Files.createDirectories(temporary.resolve(".ares/routines"))
        Files.writeString(
            temporary.resolve(".ares/action-catalog.json"),
            CapabilityCatalogCodec.encode(
                CapabilityCatalogDocument(
                    projectId = "test",
                    actions = listOf(ActionDescriptor("intake.stop", "Stop intake", "Stops intake."))
                )
            )
        )
        Files.writeString(
            temporary.resolve(".ares/project.json"),
            AresProjectMetadataCodec.encode(
                AresProjectMetadataDocument(
                    projectId = "test",
                    league = AresLeague.FTC,
                    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                    robotLengthMeters = 0.45,
                    robotWidthMeters = 0.45,
                    fieldLengthMeters = 3.6576,
                    fieldWidthMeters = 3.6576,
                )
            )
        )
        Files.writeString(
            ares.resolve("stop.aresroutine"),
            AresRoutineCodec.encode(
                RoutineDocument(
                    documentId = "stop",
                    name = "Stop",
                    steps = listOf(RoutineStep.action("intake.stop"))
                )
            )
        )
        val output = temporary.resolve("src/generated/GeneratedAresProject.kt")
        val baseArguments = arrayOf(
            "--project", temporary.toString(),
            "--output", output.toString(),
            "--package", "org.example.generated"
        )

        AresProjectCodegenCli.run(baseArguments)
        val generatedSource = Files.readString(output)
        assertTrue(generatedSource.contains("object GeneratedAresProject"))
        assertTrue(generatedSource.contains("const val ROBOT_LENGTH_METERS: Double = 0.45"))
        AresProjectCodegenCli.run(baseArguments + "--check")

        Files.writeString(output, Files.readString(output) + "// stale")
        assertThrows<IllegalArgumentException> { AresProjectCodegenCli.run(baseArguments + "--check") }
    }

    @Test
    fun `rejects generated output outside selected project`() {
        Files.createDirectories(temporary.resolve(".ares"))
        val outside = temporary.parent.resolve("outside.kt")
        assertThrows<IllegalArgumentException> {
            AresProjectCodegenCli.run(
                arrayOf(
                    "--project", temporary.toString(),
                    "--output", outside.toString(),
                    "--package", "org.example"
                )
            )
        }
    }
}
