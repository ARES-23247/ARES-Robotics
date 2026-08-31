package org.aresfirst.marvin.marvin

import org.aresfirst.marvin.generated.GeneratedAresProject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarvinConfigFieldGeometryTest {
    @Test
    fun `simulator mechanism geometry uses the canonical bumper and rear shooter`() {
        assertEquals(0.80, MarvinConfig.ROBOT_BUMPER_LENGTH_METERS, 1e-9)
        assertEquals(0.80, MarvinConfig.ROBOT_BUMPER_WIDTH_METERS, 1e-9)
        assertEquals(GeneratedAresProject.ROBOT_LENGTH_METERS, MarvinConfig.ROBOT_BUMPER_LENGTH_METERS, 1e-9)
        assertEquals(GeneratedAresProject.ROBOT_WIDTH_METERS, MarvinConfig.ROBOT_BUMPER_WIDTH_METERS, 1e-9)
        assertEquals(-0.45, MarvinConfig.MechanismGeometry.SHOOTER_EXIT_X_METERS, 1e-9)
        assertEquals(-0.055626, MarvinConfig.MechanismGeometry.SHOOTER_EXIT_Y_METERS, 1e-9)
        assertEquals(
            MarvinConfig.MechanismGeometry.SHOOTER_EXIT_X_METERS,
            MarvinConfig.SHOT_CONFIG.shooterOffsetX,
            1e-9
        )
        assertEquals(
            MarvinConfig.MechanismGeometry.SHOOTER_EXIT_Y_METERS,
            MarvinConfig.SHOT_CONFIG.shooterOffsetY,
            1e-9
        )
    }

    @Test
    fun `speaker targets match official Crescendo field dimensions`() {
        // Independent literals from the official FIRST 2024 field drawings.
        val officialFieldLengthMeters = 651.25 * 0.0254
        val officialSpeakerCenterYMeters = 218.42 * 0.0254

        assertEquals(0.0, MarvinConfig.FieldTargets.blueSpeaker.x, 1e-9)
        assertEquals(officialSpeakerCenterYMeters, MarvinConfig.FieldTargets.blueSpeaker.y, 1e-9)
        assertEquals(officialFieldLengthMeters, MarvinConfig.FieldTargets.redSpeaker.x, 1e-9)
        assertEquals(officialSpeakerCenterYMeters, MarvinConfig.FieldTargets.redSpeaker.y, 1e-9)
    }
}
