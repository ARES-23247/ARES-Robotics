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
    fun `league coordinate mismatch and impossible footprint fail closed`() {
        val invalid = AresProjectMetadataDocument(
            projectId = "ftc-project",
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
        val migrated = AresProjectMetadataCodec.decode(
            """{
              "schemaVersion": 1,
              "projectId": "student-robot",
              "league": "FTC",
              "coordinateConvention": "CENTER_ORIGIN_CCW",
              "robotLengthMeters": 0.44,
              "robotWidthMeters": 0.44,
              "fieldLengthMeters": 3.6576,
              "fieldWidthMeters": 3.6576
            }""",
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
}
