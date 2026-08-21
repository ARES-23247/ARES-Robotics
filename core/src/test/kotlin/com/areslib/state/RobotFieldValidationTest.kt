package com.areslib.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RobotFieldValidationTest {
    @Test
    fun `FTC runtime requirements reject missing duplicate and invalid tags`() {
        val missing = RobotFieldValidator.validate(
            RobotFieldConfig(fieldType = FieldType.FTC),
            requiredFieldType = FieldType.FTC,
            requireAprilTags = true,
        )
        assertEquals(RobotFieldValidationCode.APRIL_TAGS_REQUIRED, missing.single().code)

        val duplicateAndInvalid = RobotFieldValidator.validate(
            RobotFieldConfig(
                fieldType = FieldType.FTC,
                apriltags = listOf(
                    RobotFieldAprilTag(id = 1, x = 0.0, y = 0.0, z = 0.2, editorId = "tag-a"),
                    RobotFieldAprilTag(id = 1, x = Double.NaN, y = 0.0, z = 0.2, editorId = "tag-b"),
                ),
            ),
            requiredFieldType = FieldType.FTC,
            requireAprilTags = true,
        )

        assertTrue(duplicateAndInvalid.any { it.code == RobotFieldValidationCode.APRIL_TAG_DUPLICATE })
        assertTrue(duplicateAndInvalid.any { it.code == RobotFieldValidationCode.APRIL_TAG_INVALID })
    }

    @Test
    fun `zero dimensions select defaults while invalid explicit dimensions fail`() {
        assertTrue(RobotFieldValidator.validate(RobotFieldConfig()).isEmpty())

        val issues = RobotFieldValidator.validate(RobotFieldConfig(widthMeters = Double.POSITIVE_INFINITY))

        assertEquals(RobotFieldValidationCode.FIELD_DIMENSIONS, issues.single().code)
    }

    @Test
    fun `placements must reference valid element types`() {
        val issues = RobotFieldValidator.validate(
            RobotFieldConfig(
                elements = listOf(
                    RobotFieldElementInstance(id = "piece", elementTypeId = "missing", x = 0.0, y = 0.0)
                )
            )
        )

        assertEquals(RobotFieldValidationCode.ELEMENT_INVALID, issues.single().code)
        assertEquals(setOf("piece"), issues.single().elementIds)
    }
}
