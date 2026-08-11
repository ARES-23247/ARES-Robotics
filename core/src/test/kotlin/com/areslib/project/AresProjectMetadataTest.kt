package com.areslib.project

import org.junit.jupiter.api.Assertions.assertEquals
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
}
