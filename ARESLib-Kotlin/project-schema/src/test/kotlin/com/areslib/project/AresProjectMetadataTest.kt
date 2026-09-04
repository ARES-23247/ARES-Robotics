package com.areslib.project

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `authoring ownership round trips and is required`() {
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

        val missingOwnership = AresProjectMetadataCodec.encode(codeFirst)
            .replace("  \"authoringModel\": \"CODE_FIRST\",\n", "")
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.decode(missingOwnership)
        }
        val priorSchemaThree = AresProjectMetadataCodec.encode(codeFirst)
            .replace("\"schemaVersion\": 4", "\"schemaVersion\": 3")
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.decode(priorSchemaThree)
        }
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
    fun `older project schemas fail closed`() {
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

        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.decode(legacyJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.decode(legacyJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 3"))
        }
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
    fun `XRP requires an explicit isolated fail closed link policy`() {
        val xrp = AresProjectMetadataDocument(
            projectId = "student-xrp",
            identity = identity("00000", "starter", "student-xrp", "Student XRP"),
            league = AresLeague.XRP,
            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.155,
            robotWidthMeters = 0.155,
            fieldLengthMeters = 2.54,
            fieldWidthMeters = 1.4224,
            runtimeOptions = AresRuntimeOptionsDocument(
                xrp = AresXrpRuntimeOptionsDocument(
                    wifiMode = "AP",
                    ssid = "ARES-XRP-TEST",
                    port = 5811,
                    deadmanTimeoutMs = 200,
                    brownoutThresholdVolts = 4.3,
                ),
            ),
        )

        assertEquals(xrp, AresProjectMetadataCodec.decode(AresProjectMetadataCodec.encode(xrp)))
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.encode(xrp.copy(runtimeOptions = AresRuntimeOptionsDocument()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectMetadataCodec.encode(
                xrp.copy(runtimeOptions = AresRuntimeOptionsDocument(xrp = xrp.runtimeOptions.xrp!!.copy(port = 5810))),
            )
        }
    }

    private fun identity(team: String, season: String, robot: String, name: String) =
        AresProjectIdentityDocument(team, season, robot, name)
}
