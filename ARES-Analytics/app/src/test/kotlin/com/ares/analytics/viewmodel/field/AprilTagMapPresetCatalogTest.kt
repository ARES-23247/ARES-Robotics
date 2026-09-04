package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.League
import com.areslib.state.AprilTagMapCodec
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AprilTagMapPresetCatalogTest {
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
