package com.ares.analytics.ui.components

import com.areslib.math.kinematics.TwoDofLinkageKinematics
import com.areslib.math.kinematics.TwoDofLinkageParameters
import com.areslib.subsystem.SubsystemLinkageDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkageAndBehaviorComponentsTest {

    @Test
    fun `linkage parameters model initializes and calculates physical workspace envelope`() {
        val linkageDoc = SubsystemLinkageDocument(
            enabled = true,
            link1LengthMeters = 0.35,
            link2LengthMeters = 0.25,
            link1MassKg = 0.6,
            link2MassKg = 0.4,
        )
        val params = TwoDofLinkageParameters(
            l1 = linkageDoc.link1LengthMeters,
            l2 = linkageDoc.link2LengthMeters,
            m1 = linkageDoc.link1MassKg,
            m2 = linkageDoc.link2MassKg,
        )
        val kinematics = TwoDofLinkageKinematics(params)

        assertEquals(0.60, params.maxReach, 1e-4)
        assertEquals(0.10, params.minReach, 1e-4)
        assertTrue(kinematics.isReachable(0.40, 0.20))
    }

    @Test
    fun `game piece type catalog converts to canonical field element types`() {
        val customType = com.ares.analytics.shared.GamePieceType(
            id = "frc-reefscape-coral",
            name = "Reefscape Coral",
            shape = "cylinder",
            diameter = 0.25,
            width = 0.10,
            height = 0.10,
            colorHex = "#9C27B0",
            massKg = 0.30,
            friction = 0.65,
            restitution = 0.25,
        )

        val canonical = with(com.ares.analytics.viewmodel.field.FieldDocumentMapper) { customType.toCanonical() }
        assertEquals("frc-reefscape-coral", canonical.id)
        assertEquals("Reefscape Coral", canonical.name)
        assertEquals("cylinder", canonical.shape)
        assertEquals(0.25, canonical.diameter)
        assertEquals("#9C27B0", canonical.color)
        assertEquals(0.30, canonical.massKg, 1e-4)
        assertEquals(0.65, canonical.friction, 1e-4)
        assertEquals(0.25, canonical.restitution, 1e-4)
        assertTrue(canonical.movable)
    }
}
