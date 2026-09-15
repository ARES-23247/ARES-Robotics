package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.League
import com.areslib.state.AprilTagMapCodec
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AprilTagMapPresetCatalogTest {
    @Test
    fun `catalog source labels agree with the bundled tag identities`() {
        val expected = mapOf("ftc-2025-2026-decode-team23247" to setOf(20, 24),
            "frc-2024-crescendo" to (1..16).toSet(), "xrp-2026-orbit-odyssey" to setOf(1, 2))
        val presets = League.entries.flatMap(AprilTagMapPresetCatalog::forLeague)
        for ((id, ids) in expected) {
            val preset = presets.single { it.id == id }
            assertTrue(preset.displayName.isNotBlank())
            assertTrue(preset.sourceLabel.isNotBlank())
            assertEquals(ids, RobotFieldDocument.decode(preset.readContent()).apriltags.map { it.id }.toSet())
        }
    }

    @Test
    fun `missing resource reports its path instead of producing an empty map`() {
        val preset = AprilTagMapPreset("missing", League.FTC, "Missing", "Test", "field-presets/missing-audit.json")
        val failure = assertFailsWith<IllegalArgumentException> { preset.readContent() }
        assertTrue(failure.message.orEmpty().contains(preset.resourcePath))
    }

    @Test
    fun `blank XRP practice field has exact inch dimensions and no implicit content`() {
        val content = AprilTagMapPreset("blank", League.XRP, "Blank", "Practice", "field-presets/xrp/xrp_blank.json").readContent()
        val document = RobotFieldDocument.decode(content)
        assertEquals(FieldType.XRP, document.fieldType)
        assertEquals(100.0 * 0.0254, document.resolvedWidthMeters, 1e-12)
        assertEquals(56.0 * 0.0254, document.resolvedHeightMeters, 1e-12)
        assertTrue(document.apriltags.isEmpty())
        assertTrue(document.obstacles.isEmpty())
        assertTrue(document.elementTypes.isEmpty())
        assertTrue(document.elements.isEmpty())
        assertTrue(document.fieldWaypoints.isEmpty())
        assertEquals("", document.image?.imagePath)
        assertTrue(RobotFieldValidator.validate(document, requiredFieldType = FieldType.XRP).isEmpty())
    }
    @Test
    fun `every bundled preset is readable typed and nonempty`() {
        League.entries.forEach { league ->
            val presets = AprilTagMapPresetCatalog.forLeague(league)
            assertTrue(presets.isNotEmpty(), "$league should offer at least one reviewed map")
            presets.forEach { preset ->
                val document = RobotFieldDocument.decode(preset.readContent())
                val expectedType = when (league) {
                    League.FTC -> FieldType.FTC
                    League.FRC -> FieldType.FRC
                    League.XRP -> FieldType.XRP
                }
                assertEquals(expectedType, document.fieldType)
                assertTrue(document.apriltags.isNotEmpty())
                assertTrue(
                    RobotFieldValidator.validate(
                        document,
                        requiredFieldType = expectedType,
                        requireAprilTags = true,
                    ).isEmpty(),
                    "${preset.displayName} must pass canonical runtime validation",
                )
                assertTrue(AprilTagMapCodec.decodeAresField(preset.readContent()).tags.isNotEmpty())
            }
        }
    }

    @Test
    fun `preset identifiers and resource paths remain unique`() {
        val presets = League.entries.flatMap(AprilTagMapPresetCatalog::forLeague)
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        assertEquals(presets.size, presets.map { it.resourcePath }.distinct().size)
    }
}
