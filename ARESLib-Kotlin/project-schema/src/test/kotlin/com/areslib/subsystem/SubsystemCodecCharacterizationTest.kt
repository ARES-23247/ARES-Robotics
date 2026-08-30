package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemCodecCharacterizationTest {
    @Test
    fun `complete descriptor round trips without normalization drift`() {
        val document = indicatorDocument()
        val encoded = SubsystemDocumentCodec.encode(document)
        val decoded = SubsystemDocumentCodec.decode(encoded)

        assertEquals(document, decoded)
        assertEquals(encoded, SubsystemDocumentCodec.encode(decoded))
        assertEquals(
            SubsystemDocumentCodec.contentHash(document),
            SubsystemDocumentCodec.contentHash(decoded),
        )
        assertEquals(64, SubsystemDocumentCodec.contentHash(document).length)
    }

    @Test
    fun `decoder rejects unsupported or structurally incomplete documents before normalization`() {
        assertDecodeFailure("{}", "Unsupported subsystem schema null")
        assertDecodeFailure("""{"schemaVersion":10}""", "Unsupported subsystem schema 10")
        assertDecodeFailure(
            """{"schemaVersion":11,"displayName":"Light","kotlinTypeName":"Light"}""",
            "Subsystem implementation metadata is required",
        )
        assertDecodeFailure(
            """{
                "schemaVersion":11,
                "displayName":"Light",
                "kotlinTypeName":"Light",
                "implementation":{"kind":"DECLARATIVE_GENERATED","ownership":"GENERATED_DO_NOT_EDIT"},
                "safety":{}
            }""".trimIndent(),
            "Subsystem homing metadata is required",
        )
        assertDecodeFailure(
            """{
                "schemaVersion":11,
                "displayName":"Light",
                "kotlinTypeName":"Light",
                "implementation":{"kind":"DECLARATIVE_GENERATED","ownership":"GENERATED_DO_NOT_EDIT"},
                "safety":{"homing":{}}
            }""".trimIndent(),
            "Subsystem tuningParameters are required",
        )
    }

    @Test
    fun `validation retains deterministic issue ordering and paths`() {
        val invalid = indicatorDocument().copy(
            documentId = "Invalid Name",
            kotlinTypeName = "invalidType",
            parentContentHash = "not-a-hash",
            revision = 0,
        )

        assertEquals(
            listOf(
                SubsystemValidationIssue("documentId", "Document ID must be a stable lowercase key"),
                SubsystemValidationIssue("kotlinTypeName", "Kotlin type name must use PascalCase"),
                SubsystemValidationIssue("revision", "Revision must be positive"),
                SubsystemValidationIssue("parentContentHash", "Parent content hash must be SHA-256"),
            ),
            validateSubsystemDocument(invalid),
        )
    }

    private fun assertDecodeFailure(json: String, expectedMessage: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SubsystemDocumentCodec.decode(json)
        }
        assertTrue(error.message.orEmpty().contains(expectedMessage), error.message)
    }

    private fun indicatorDocument(): SubsystemDocument = SubsystemDocument(
        documentId = "indicator-light",
        displayName = "Indicator light",
        kotlinTypeName = "IndicatorLight",
        description = "A single GUI-owned indicator output.",
        platform = SubsystemPlatform.FTC,
        hardware = listOf(
            SubsystemHardwareDocument(
                hardwareId = "light",
                displayName = "Light",
                kind = SubsystemHardwareKind.INDICATOR_LIGHT,
                connection = SubsystemHardwareConnection(hardwareMapName = "light"),
                safeOutput = 0.0,
            ),
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument(
                fieldId = "color",
                displayName = "Color",
                type = SubsystemValueType.DOUBLE,
                role = SubsystemFieldRole.TARGET,
                defaultNumber = 0.0,
                minimum = 0.0,
                maximum = 1.0,
            ),
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument(
                loopId = "lightControl",
                displayName = "Light control",
                strategy = SubsystemControlStrategy.DIRECT,
                actuatorId = "light",
                targetFieldId = "color",
                minimumOutput = 0.0,
                maximumOutput = 1.0,
            ),
        ),
        template = SubsystemTemplate.INDICATOR_LIGHT_PWM,
        implementation = SubsystemImplementationDocument(
            kind = SubsystemImplementationKind.DECLARATIVE_GENERATED,
            ownership = SubsystemSourceOwnership.GENERATED_DO_NOT_EDIT,
        ),
    )
}
