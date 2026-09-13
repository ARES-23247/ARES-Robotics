package com.areslib.subsystem

import java.math.BigDecimal
import java.math.MathContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SubsystemNumericalBoundaryAuditTest {
    @Test
    fun `integer defaults obey numeric bounds including fractional and one-sided limits`() {
        val base = SubsystemBoundaryFixtures.motor()
        val original = base.stateFields.first()
        fun issues(field: SubsystemStateFieldDocument) = SubsystemSchema.validate(base.copy(stateFields = listOf(field) + base.stateFields.drop(1)))
        val valid = original.copy(type = SubsystemValueType.INT, defaultNumber = null, defaultInt = 2, minimum = 1.0, maximum = 3.0)
        assertTrue(issues(valid).isEmpty())
        for (field in listOf(valid.copy(defaultInt = 0), valid.copy(defaultInt = 4), valid.copy(minimum = 2.5, maximum = null), valid.copy(minimum = null, maximum = 1.5))) {
            assertTrue(issues(field).any { it.path.startsWith("stateFields[0]") }, field.toString())
        }
    }

    @Test
    fun `scale agrees with a decimal reference across the double exponent range`() {
        val random = java.util.Random(226L)
        fun positive(): Double {
            while (true) {
                val value = Double.fromBits(random.nextLong() and Long.MAX_VALUE)
                if (value.isFinite() && value > 0.0) return value
            }
        }
        repeat(500) {
            val native = positive()
            val gearing = positive()
            val state = positive()
            val expected = BigDecimal(state).divide(BigDecimal(native).multiply(BigDecimal(gearing)), MathContext.DECIMAL128).toDouble()
            if (expected.isFinite() && expected > 0.0) {
                assertEquals(expected, SubsystemUnits.motorMeasurementScale(native, gearing, state), Math.ulp(expected) * 4)
            } else {
                assertThrows<IllegalArgumentException> { SubsystemUnits.motorMeasurementScale(native, gearing, state) }
            }
        }
    }

    @Test
    fun `motor scale survives overflowing and underflowing intermediate products`() {
        val cases = listOf(
            Triple(1e200, 1e200, 1e200),
            Triple(1e-200, 1e-200, 1e-200),
            Triple(Double.MIN_VALUE, Double.MAX_VALUE, 1.0),
            Triple(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE),
        )
        for ((native, gearing, state) in cases) {
            val expected = BigDecimal(state).divide(BigDecimal(native).multiply(BigDecimal(gearing)), MathContext.DECIMAL128).toDouble()
            val actual = SubsystemUnits.motorMeasurementScale(native, gearing, state)
            assertEquals(expected, actual, Math.ulp(expected) * 4, "$native $gearing $state")
            assertTrue(actual.isFinite() && actual > 0.0)
        }
    }

    @Test
    fun `motor scale rejects unrepresentable output and every invalid input position`() {
        for (triple in listOf(Triple(Double.MIN_VALUE, Double.MIN_VALUE, 1.0), Triple(Double.MAX_VALUE, Double.MAX_VALUE, Double.MIN_VALUE))) {
            assertThrows<IllegalArgumentException> { SubsystemUnits.motorMeasurementScale(triple.first, triple.second, triple.third) }
        }
        for (invalid in listOf(0.0, -0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows<IllegalArgumentException> { SubsystemUnits.motorMeasurementScale(invalid, 1.0, 1.0) }
            assertThrows<IllegalArgumentException> { SubsystemUnits.motorMeasurementScale(1.0, invalid, 1.0) }
            assertThrows<IllegalArgumentException> { SubsystemUnits.motorMeasurementScale(1.0, 1.0, invalid) }
        }
    }

    @Test
    fun `linkage rejects non-radian and shared joint measurements`() {
        val base = SubsystemBoundaryFixtures.linkage()
        assertTrue(SubsystemSchema.validate(base).isEmpty(), SubsystemSchema.validate(base).toString())
        for (unit in listOf("deg", "m", null)) {
            val wrong = base.copy(stateFields = base.stateFields.map { if (it.fieldId == "angle") it.copy(unit = unit) else it })
            assertTrue(SubsystemSchema.validate(wrong).any { it.path == "linkage.joint1AngleFieldId" }, "Unit $unit")
        }
        val duplicate = base.copy(linkage = base.linkage.copy(joint2AngleFieldId = "angle"))
        assertTrue(SubsystemSchema.validate(duplicate).any { it.path.startsWith("linkage") })
    }

    @Test
    fun `simulator interaction rejects nonfinite dimensions rates and trigger`() {
        val base = SubsystemBoundaryFixtures.motor()
        val good = SubsystemSimInteractionDocument(role = SimInteractionRole.PROJECTILE_LAUNCHER, triggerActuatorId = "motor")
        fun issues(value: SubsystemSimInteractionDocument) = SubsystemSchema.validate(base.copy(implementation = base.implementation.copy(simulation = base.implementation.simulation.copy(interaction = value))))
        assertTrue(issues(good).isEmpty())
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (bad in listOf(good.copy(triggerThreshold = invalid), good.copy(intakeDistanceMeters = invalid), good.copy(captureRadiusMeters = invalid), good.copy(launchSpeedMps = invalid))) {
                assertTrue(issues(bad).any { it.path.startsWith("implementation.simulation.interaction") }, bad.toString())
            }
        }
    }

    @Test
    fun `absolute homing evidence cannot use a negative magnitude threshold`() {
        val base = SubsystemBoundaryFixtures.motor()
        for (comparison in listOf(SubsystemHomingComparison.ABS_AT_OR_ABOVE, SubsystemHomingComparison.ABS_AT_OR_BELOW)) {
            val homing = SubsystemHomingDocument(
                method = SubsystemHomingMethod.VELOCITY_STALL, actuatorId = "motor", searchOutput = -1.0,
                evidence = listOf(SubsystemHomingEvidenceDocument("velocity", comparison, 0.5)),
            )
            val valid = base.copy(safety = base.safety.copy(homing = homing))
            assertTrue(SubsystemSchema.validate(valid).isEmpty())
            val invalid = valid.copy(safety = valid.safety.copy(homing = homing.copy(evidence = listOf(homing.evidence.single().copy(threshold = -0.5)))))
            assertTrue(SubsystemSchema.validate(invalid).any { it.path == "safety.homing.evidence[0].threshold" })
        }
    }
}
