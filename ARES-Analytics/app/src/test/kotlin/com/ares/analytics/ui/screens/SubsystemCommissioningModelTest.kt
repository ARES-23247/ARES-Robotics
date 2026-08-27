package com.ares.analytics.ui.screens

import com.ares.analytics.service.commissioning.SubsystemCommissioningPlant
import com.ares.analytics.service.commissioning.SubsystemCommissioningScenario
import com.ares.analytics.service.commissioning.simulateSubsystemCommissioning
import com.areslib.subsystem.SubsystemContinuousInputDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubsystemCommissioningModelTest {
    @Test
    fun `every selectable generated strategy has a bounded deterministic preview`() {
        SubsystemControlStrategy.entries.forEach { strategy ->
            val (loop, plant) = loopAndPlant(strategy)
            val first = simulateSubsystemCommissioning(loop, plant, SubsystemCommissioningScenario.NOMINAL)
            val second = simulateSubsystemCommissioning(loop, plant, SubsystemCommissioningScenario.NOMINAL)

            assertTrue(first.samples.isNotEmpty(), "$strategy should produce samples")
            assertTrue(first.metrics.bounded, "$strategy should remain bounded")
            assertEquals(first, second, "$strategy preview must be deterministic")
            assertTrue(first.samples.all { sample -> sample.command.isFinite() && sample.measurement.isFinite() })
            assertTrue(first.samples.all { sample ->
                sample.command in loop.minimumOutput..loop.maximumOutput || strategy == SubsystemControlStrategy.SERVO_POSITION
            })
        }
    }

    @Test
    fun `feedback controllers neutralize stale and invalid cached input`() {
        listOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID,
            SubsystemControlStrategy.BANG_BANG,
        ).forEach { strategy ->
            val (loop, plant) = loopAndPlant(strategy)
            listOf(
                SubsystemCommissioningScenario.STALE_FEEDBACK,
                SubsystemCommissioningScenario.FROZEN_HEARTBEAT,
                SubsystemCommissioningScenario.INVALID_FEEDBACK,
            ).forEach { scenario ->
                val result = simulateSubsystemCommissioning(loop, plant, scenario)
                val faultSamples = result.samples.filterNot { it.feedbackUsable }
                assertTrue(faultSamples.isNotEmpty(), "$strategy $scenario should inject a fault")
                assertTrue(faultSamples.all { it.command == 0.0 }, "$strategy $scenario must fail closed")
                assertEquals(true, result.metrics.neutralizedOnFault)
            }
        }
    }

    @Test
    fun `write and overcurrent faults latch until explicit successful neutral recovery`() {
        val (loop, plant) = loopAndPlant(SubsystemControlStrategy.VELOCITY_PID)
        listOf(
            SubsystemCommissioningScenario.FAILED_WRITE_RECOVERY,
            SubsystemCommissioningScenario.EXCESS_CURRENT_RECOVERY,
        ).forEach { scenario ->
            val result = simulateSubsystemCommissioning(loop, plant, scenario)
            val faultSamples = result.samples.filter { it.faultActive || it.faultLatched }

            assertTrue(faultSamples.isNotEmpty(), "$scenario should expose a latched fault interval")
            assertTrue(faultSamples.all { it.command == 0.0 }, "$scenario must remain neutral while faulted")
            assertEquals(true, result.metrics.faultLatched)
            assertEquals(true, result.metrics.neutralRecoverySucceeded)
            assertTrue(result.samples.last().safetyPermit, "$scenario should recover only after neutral succeeds")
        }
    }

    @Test
    fun `brownout configuration and homing gates fail closed without inventing a latch`() {
        val (loop, plant) = loopAndPlant(SubsystemControlStrategy.POSITION_PID)
        listOf(
            SubsystemCommissioningScenario.BROWNOUT_RECOVERY,
            SubsystemCommissioningScenario.UNCONFIGURED,
            SubsystemCommissioningScenario.UNHOMED,
        ).forEach { scenario ->
            val result = simulateSubsystemCommissioning(loop, plant, scenario)
            assertTrue(result.samples.filter { it.faultActive }.all { it.command == 0.0 })
            assertEquals(null, result.metrics.faultLatched)
            assertEquals(true, result.metrics.neutralizedOnFault)
        }
    }

    @Test
    fun `continuous position preview follows the short direction across signed angle boundary`() {
        val (base, plant) = loopAndPlant(SubsystemControlStrategy.POSITION_PID)
        val loop = base.copy(
            kP = 2.0,
            kI = 0.0,
            kD = 0.0,
            feedforward = SubsystemFeedforwardDocument(),
            continuousInput = SubsystemContinuousInputDocument(
                enabled = true,
                minimumInput = -Math.PI,
                maximumInput = Math.PI,
            ),
        )
        val result = simulateSubsystemCommissioning(
            loop,
            plant,
            SubsystemCommissioningScenario.ANGLE_BOUNDARY,
            durationSeconds = 0.4,
        )

        assertTrue(result.samples.first().command > 0.0, "-179° from +179° is a +2° shortest-path move")
        assertTrue(result.metrics.bounded)
    }

    @Test
    fun `hysteretic direction changes pass through a neutral sample`() {
        val (base, plant) = loopAndPlant(SubsystemControlStrategy.BANG_BANG)
        val loop = base.copy(tolerance = 0.05, hysteresis = 0.10, minimumOutput = -3.0, maximumOutput = 3.0)
        val result = simulateSubsystemCommissioning(loop, plant, SubsystemCommissioningScenario.NOMINAL)
        val commands = result.samples.map { it.command }

        assertFalse(commands.zipWithNext().any { (before, after) -> before > 0.0 && after < 0.0 })
        assertFalse(commands.zipWithNext().any { (before, after) -> before < 0.0 && after > 0.0 })
        assertTrue(commands.any { it == 0.0 })
    }

    @Test
    fun `open loop fault scenarios are presented as not applicable rather than fake feedback evidence`() {
        val (loop, plant) = loopAndPlant(SubsystemControlStrategy.DIRECT)
        val result = simulateSubsystemCommissioning(loop, plant, SubsystemCommissioningScenario.STALE_FEEDBACK)

        assertTrue(result.samples.all { it.feedbackUsable })
        assertEquals(null, result.metrics.neutralizedOnFault)
        assertTrue(result.metrics.statusMessage.contains("cannot guarantee"))
    }

    private fun loopAndPlant(strategy: SubsystemControlStrategy) = when (strategy) {
        SubsystemControlStrategy.DIRECT -> templateLoop(SubsystemTemplate.SIMPLE_ACTUATOR) to SubsystemCommissioningPlant.FLYWHEEL
        SubsystemControlStrategy.POSITION_PID -> templateLoop(SubsystemTemplate.POSITION_CONTROLLED_MECHANISM) to SubsystemCommissioningPlant.ROTARY_ARM
        SubsystemControlStrategy.PROFILED_POSITION_PID -> templateLoop(SubsystemTemplate.ARM_PIVOT) to SubsystemCommissioningPlant.ROTARY_ARM
        SubsystemControlStrategy.VELOCITY_PID -> templateLoop(SubsystemTemplate.FLYWHEEL_SHOOTER) to SubsystemCommissioningPlant.FLYWHEEL
        SubsystemControlStrategy.BANG_BANG -> templateLoop(SubsystemTemplate.POSITION_CONTROLLED_MECHANISM)
            .copy(strategy = SubsystemControlStrategy.BANG_BANG, kP = 0.0, kI = 0.0, kD = 0.0, feedforward = SubsystemFeedforwardDocument()) to
            SubsystemCommissioningPlant.ROTARY_ARM
        SubsystemControlStrategy.SERVO_POSITION -> templateLoop(SubsystemTemplate.POSITIONAL_SERVO) to SubsystemCommissioningPlant.POSITIONAL_SERVO
    }

    private fun templateLoop(template: SubsystemTemplate) = SubsystemTemplates.create(
        template = template,
        documentId = "commissioning-${template.name.lowercase()}",
        kotlinTypeName = "Commissioning${template.name.lowercase().replaceFirstChar(Char::uppercaseChar)}",
        platform = SubsystemPlatform.FTC,
    ).controlLoops.single()
}
