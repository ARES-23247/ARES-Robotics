package com.areslib.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AresProjectMetadataTest {
    @Test
    fun `metadata round trips and owns robot and field geometry`() {
        val metadata = AresProjectMetadataDocument(
            projectId = "marvin-xix",
            identity = identity("23247", "2024", "marvin-xix", "Marvin XIX"),
            league = AresLeague.FRC,
            coordinateConvention = AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW,
            robotLengthMeters = 0.8,
            robotWidthMeters = 0.8,
            fieldLengthMeters = 16.54175,
            fieldWidthMeters = 8.21055,
        )

        assertEquals(metadata, AresProjectMetadataCodec.decode(AresProjectMetadataCodec.encode(metadata)))
    }

    @Test
    fun `authoring ownership round trips and older schema three projects default to GUI ownership`() {
        val codeFirst = AresProjectMetadataDocument(
            projectId = "code-first-frc",
            identity = identity("9999", "2027", "code-first-frc", "Code First FRC"),
            league = AresLeague.FRC,
            coordinateConvention = AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW,
            robotLengthMeters = 0.8,
            robotWidthMeters = 0.8,
            fieldLengthMeters = 16.5,
            fieldWidthMeters = 8.2,
            authoringModel = AresProjectAuthoringModel.CODE_FIRST,
        )
        assertEquals(codeFirst, AresProjectMetadataCodec.decode(AresProjectMetadataCodec.encode(codeFirst)))

        val priorSchemaThree = AresProjectMetadataCodec.encode(codeFirst)
            .replace("  \"authoringModel\": \"CODE_FIRST\",\n", "")
        assertEquals(
            AresProjectAuthoringModel.GUI_OWNED,
            AresProjectMetadataCodec.decode(priorSchemaThree).authoringModel,
        )
    }

    @Test
    fun `league coordinate mismatch and impossible footprint fail closed`() {
        val invalid = AresProjectMetadataDocument(
            projectId = "ftc-project",
            identity = identity("99999", "2026", "student-robot", "Student Robot"),
            league = AresLeague.FTC,
            coordinateConvention = AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW,
            robotLengthMeters = 4.0,
            robotWidthMeters = 0.45,
            fieldLengthMeters = 3.6576,
            fieldWidthMeters = 3.6576,
        )

        assertThrows(IllegalArgumentException::class.java) { AresProjectMetadataCodec.encode(invalid) }
    }

    @Test
    fun `legacy FTC metadata migrates to explicit safe runtime defaults`() {
        val legacyJson =
            """{
              "schemaVersion": 1,
              "projectId": "student-robot",
              "league": "FTC",
              "coordinateConvention": "CENTER_ORIGIN_CCW",
              "robotLengthMeters": 0.44,
              "robotWidthMeters": 0.44,
              "fieldLengthMeters": 3.6576,
              "fieldWidthMeters": 3.6576
            }"""
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.decode(legacyJson)
        }

        val migrated = AresProjectMetadataCodec.migrateLegacy(
            projectJson = legacyJson,
            legacyIdentity = AresLegacyRobotIdentityDocument(
                teamId = "99999",
                seasonId = "2026",
                robotId = "student-robot",
                name = "Student Robot",
                league = AresLeague.FTC,
            ),
        )

        assertEquals(ARES_PROJECT_METADATA_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(AresFtcHubCommandTransport.STANDARD_SDK, migrated.resolvedFtcRuntimeOptions().hubCommandTransport)
        assertFalse(migrated.resolvedFtcRuntimeOptions().limelightProxyEnabled)
        val encoded = AresProjectMetadataCodec.encode(migrated)
        assertEquals(migrated, AresProjectMetadataCodec.decode(encoded))
    }

    @Test
    fun `FTC runtime options round trip while FRC rejects FTC-only policy`() {
        val ftc = AresProjectMetadataDocument(
            projectId = "fast-ftc",
            identity = identity("23247", "2026", "fast-ftc", "Fast FTC"),
            league = AresLeague.FTC,
            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.44,
            robotWidthMeters = 0.44,
            fieldLengthMeters = 3.6576,
            fieldWidthMeters = 3.6576,
            runtimeOptions = AresRuntimeOptionsDocument(
                AresFtcRuntimeOptionsDocument(AresFtcHubCommandTransport.ARES_PHOTON, true),
            ),
        )
        assertEquals(ftc, AresProjectMetadataCodec.decode(AresProjectMetadataCodec.encode(ftc)))

        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.encode(
                ftc.copy(
                    league = AresLeague.FRC,
                    coordinateConvention = AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW,
                ),
            )
        }
    }

    @Test
    fun `schema two requires explicit typed FTC runtime policy`() {
        val missingPolicy = """{
          "schemaVersion": 2,
          "projectId": "student-robot",
          "league": "FTC",
          "coordinateConvention": "CENTER_ORIGIN_CCW",
          "robotLengthMeters": 0.44,
          "robotWidthMeters": 0.44,
          "fieldLengthMeters": 3.6576,
          "fieldWidthMeters": 3.6576,
          "runtimeOptions": {}
        }"""
        val stringBoolean = missingPolicy.replace(
            "\"runtimeOptions\": {}",
            "\"runtimeOptions\": {\"ftc\": {\"hubCommandTransport\": \"STANDARD_SDK\", \"limelightProxyEnabled\": \"false\"}}",
        )

        assertThrows(IllegalArgumentException::class.java) { AresProjectMetadataCodec.decode(missingPolicy) }
        assertThrows(IllegalArgumentException::class.java) { AresProjectMetadataCodec.decode(stringBoolean) }
    }

    @Test
    fun `legacy migration rejects conflicting league identity`() {
        val legacyJson = """{
          "schemaVersion": 2,
          "projectId": "student-robot",
          "league": "FTC",
          "coordinateConvention": "CENTER_ORIGIN_CCW",
          "robotLengthMeters": 0.44,
          "robotWidthMeters": 0.44,
          "fieldLengthMeters": 3.6576,
          "fieldWidthMeters": 3.6576,
          "runtimeOptions": {"ftc": {"hubCommandTransport": "STANDARD_SDK", "limelightProxyEnabled": false}}
        }"""

        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.migrateLegacy(
                legacyJson,
                AresLegacyRobotIdentityDocument("99999", "2026", "student-robot", "Student Robot", AresLeague.FRC),
            )
        }
    }

    @Test
    fun `legacy identity decoder is strict and reusable at migration boundaries`() {
        val identity = AresProjectMetadataCodec.decodeLegacyIdentity(
            """{"teamId":"23247","seasonId":"2026","robotId":"Lightbot","name":"Lightbot","league":"FTC"}""",
        )

        assertEquals(AresLegacyRobotIdentityDocument("23247", "2026", "Lightbot", "Lightbot", AresLeague.FTC), identity)
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.decodeLegacyIdentity(
                """{"teamId":"23247","seasonId":"2026","robotId":"Lightbot","name":"Lightbot"}""",
            )
        }
    }

    private fun identity(team: String, season: String, robot: String, name: String) =
        AresProjectIdentityDocument(team, season, robot, name)
}
