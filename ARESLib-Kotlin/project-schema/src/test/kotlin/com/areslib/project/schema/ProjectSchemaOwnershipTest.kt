package com.areslib.project.schema

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectSchemaOwnershipTest {
    @Test
    fun `canonical descriptor codecs have one physical source owner`() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile, File::getParentFile)
            .firstOrNull { File(it, "project-schema/build.gradle.kts").isFile }
        requireNotNull(repository) { "Could not locate the ARESLib repository" }

        val descriptors = listOf(
            "catalog/CapabilityCatalog.kt",
            "controls/ControllerProfileDocument.kt",
            "controls/ControlSchemeDocument.kt",
            "drivetrain/DrivetrainDocument.kt",
            "project/AresProjectMetadata.kt",
            "routine/AutonomousCatalog.kt",
            "routine/RoutineCodec.kt",
            "routine/RoutineDocument.kt",
            "subsystem/SubsystemDocument.kt",
            "superstructure/SuperstructureDocument.kt",
            "tuning/TuningProfileDocument.kt",
        )
        descriptors.forEach { relativePath ->
            assertTrue(
                File(repository, "project-schema/src/main/kotlin/com/areslib/$relativePath").isFile,
                "$relativePath must be owned by project-schema",
            )
            assertFalse(
                File(repository, "core/src/main/kotlin/com/areslib/$relativePath").exists(),
                "$relativePath must not also be compiled from core",
            )
        }
    }
}
