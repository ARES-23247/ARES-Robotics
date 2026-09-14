package org.aresfirst.starter.frc

import com.areslib.routine.*
import com.areslib.sequencer.*
import com.areslib.state.*
import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterNestedActionOwnershipAuditTest {
    private class Leaf : Task {
        override val name = "Nested action"
        var releases = 0
        override fun isCompleted(state: RobotState, elapsedMs: Long) = false
        override fun releaseRuntimeState() { releases++; super.releaseRuntimeState() }
    }
    private val telemetry = object : ITelemetry {
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
    private fun withCapabilities(factory: (String) -> Task?, block: (StarterGeneratedCapabilities) -> Unit) {
        val field = RobotFieldManager.activeConfig
        RobotFieldManager.setActiveConfig(RobotFieldConfig(fieldType = FieldType.FRC))
        val robot = StarterRobotRuntime(telemetry)
        try {
            val capabilities = StarterGeneratedCapabilities(robot, true, factory)
            capabilities.configureAutonomous(AutonomousCatalogEntry("drive", "Drive", routineId = "drive",
                startingPose = RoutinePose(1.0, 1.0, 0.0)), Alliance.BLUE)
            block(capabilities)
        } finally { try { robot.close() } finally { RobotFieldManager.setActiveConfig(field) } }
    }
    private fun rejectedStep() = RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0), duringActionKeys = listOf("first", "missing"))

    @Test fun `a later missing action releases every nested child acquired earlier`() {
        val first = Leaf()
        val second = Leaf()
        var callbacks = 0
        first.onComplete { callbacks++ }; second.onComplete { callbacks++ }
        val tree = ParallelTaskGroup(listOf(first, SequentialTaskGroup(listOf(second))))
        try {
            withCapabilities({ key -> if (key == "first") tree else null }) { capabilities ->
                assertThrows(IllegalArgumentException::class.java) { capabilities.createDriveTask(rejectedStep()) }
            }
            assertEquals(1, first.releases); assertEquals(1, second.releases)
            TaskCallbacks.invokeComplete(first); TaskCallbacks.invokeComplete(second)
            assertEquals(0, callbacks)
        } finally { first.reset(); second.reset(); tree.reset() }
    }

    @Test fun `an already compiled pending action is rejected without erasing its owners metadata`() {
        val foreign = Leaf()
        var callbacks = 0
        foreign.onComplete { callbacks++ }
        val document = routine("owner", "Owner") { action("foreign") }
        val owner = requireNotNull(RoutineCompiler(mapOf("owner" to document), RoutineRuntimeBindings(
            createActionTask = { _, _ -> foreign }, createCondition = { _, _ -> null })).compile("owner", 1L).task)
        try {
            withCapabilities({ foreign }) { capabilities ->
                assertThrows(IllegalStateException::class.java) {
                    capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0), duringActionKeys = listOf("foreign")))
                }
            }
            assertEquals(0, foreign.releases)
            TaskCallbacks.invokeComplete(foreign)
            assertEquals(1, callbacks)
        } finally { owner.releaseRuntimeState(); foreign.reset() }
    }
}
