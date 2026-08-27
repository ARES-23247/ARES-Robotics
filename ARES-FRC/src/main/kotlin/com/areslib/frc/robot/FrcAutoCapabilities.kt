package com.areslib.frc.robot

import com.areslib.action.RobotAction
import com.areslib.frc.marvin.MarvinConfig
import com.areslib.frc.marvin.MarvinFeederController
import com.areslib.frc.marvin.marvin
import com.areslib.frc.marvin.SetFeederSpeed
import com.areslib.frc.marvin.SetCowlAngle
import com.areslib.frc.marvin.SetFloorSpeed
import com.areslib.frc.marvin.SetFlywheelActive
import com.areslib.frc.marvin.SetFlywheelSpeed
import com.areslib.frc.marvin.SetIntakePivot
import com.areslib.frc.marvin.SetIntakeRollers
import com.areslib.frc.marvin.CompleteTransfer
import com.areslib.frc.marvin.ResetTransferCycle
import com.areslib.frc.marvin.StartTransfer
import com.areslib.frc.generated.GeneratedAresProjectCapabilities
import com.areslib.pathing.CommandKey
import com.areslib.pathing.NamedCommandDescriptor
import com.areslib.pathing.NamedCommands
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskResources
import com.areslib.state.RobotState

/**
 * Marvin actions available to generated ARES routines.
 *
 * The checked-in `.ares/action-catalog.json` drives generated type safety. The generated runtime
 * resolves compiled action keys through this task registry. Every factory creates a fresh task
 * because task lifecycle state may never be shared between invocations or runs.
 */
object FrcAutoCapabilities : GeneratedAresProjectCapabilities {
    private val COWL_RESOURCE = TaskResources.season(0)

    val INTAKE_COLLECT = NamedCommandDescriptor(
        key = CommandKey("intake.collect"),
        displayName = "Collect note",
        description = "Deploys the intake and runs the intake and floor rollers.",
        category = "Intake",
        requiredResources = TaskResources.INTAKE or TaskResources.FLOOR
    )
    val INTAKE_STOP = NamedCommandDescriptor(
        key = CommandKey("intake.stop"),
        displayName = "Stop intake",
        description = "Stops the intake and floor rollers without moving the pivot.",
        category = "Intake",
        requiredResources = TaskResources.INTAKE or TaskResources.FLOOR
    )
    val INTAKE_STOW = NamedCommandDescriptor(
        key = CommandKey("intake.stow"),
        displayName = "Stow intake",
        description = "Stops the rollers and retracts the intake pivot.",
        category = "Intake",
        requiredResources = TaskResources.INTAKE or TaskResources.FLOOR
    )
    val SHOOTER_PREPARE = NamedCommandDescriptor(
        key = CommandKey("shooter.prepare"),
        displayName = "Prepare shooter",
        description = "Commands the flywheel and cowl to the autonomous shooting preset.",
        category = "Shooter",
        requiredResources = TaskResources.FLYWHEEL or COWL_RESOURCE
    )
    val SHOOTER_FEED_WHEN_READY = NamedCommandDescriptor(
        key = CommandKey("shooter.feedWhenReady"),
        displayName = "Shoot when ready",
        description = "Waits up to two seconds for fresh aligned flywheel RPM and cowl position, then runs one bounded transfer.",
        category = "Shooter",
        requiredResources = TaskResources.FEEDER or TaskResources.FLOOR
    )
    val SHOOTER_STOP = NamedCommandDescriptor(
        key = CommandKey("shooter.stop"),
        displayName = "Stop shooter",
        description = "Stops the flywheel, feeder, and floor roller and clears the transfer latch.",
        category = "Shooter",
        requiredResources = TaskResources.FLYWHEEL or TaskResources.FEEDER or TaskResources.FLOOR
    )

    val descriptors: List<NamedCommandDescriptor> = listOf(
        INTAKE_COLLECT,
        INTAKE_STOP,
        INTAKE_STOW,
        SHOOTER_PREPARE,
        SHOOTER_FEED_WHEN_READY,
        SHOOTER_STOP
    )

    /** Registers or replaces all FRC autonomous task factories. */
    fun register() {
        NamedCommands.register(INTAKE_COLLECT) { actionIntakeCollect() }
        NamedCommands.register(INTAKE_STOP) { actionIntakeStop() }
        NamedCommands.register(INTAKE_STOW) { actionIntakeStow() }
        NamedCommands.register(SHOOTER_PREPARE) { actionShooterPrepare() }
        NamedCommands.register(SHOOTER_FEED_WHEN_READY) { actionShooterFeedWhenReady() }
        NamedCommands.register(SHOOTER_STOP) { actionShooterStop() }
    }

    /** Stable generated-project boundary; catalog changes no longer require new Kotlin overrides. */
    override fun createActionTask(actionKey: String, arguments: Map<String, String>): Task? {
        require(arguments.isEmpty()) { "FRC named action '$actionKey' does not accept arguments" }
        return when (actionKey) {
            INTAKE_COLLECT.key.value -> actionIntakeCollect()
            INTAKE_STOP.key.value -> actionIntakeStop()
            INTAKE_STOW.key.value -> actionIntakeStow()
            SHOOTER_PREPARE.key.value -> actionShooterPrepare()
            SHOOTER_FEED_WHEN_READY.key.value -> actionShooterFeedWhenReady()
            SHOOTER_STOP.key.value -> actionShooterStop()
            else -> null
        }
    }

    override fun createCondition(
        conditionKey: String,
        arguments: Map<String, String>,
    ): ((RobotState) -> Boolean)? {
        require(arguments.isEmpty()) { "FRC condition '$conditionKey' does not accept arguments" }
        return when (conditionKey) {
            "shooter.ready" -> ::flywheelIsReady
            else -> null
        }
    }

    fun actionIntakeCollect(): Task = InstantAutoActionsTask(INTAKE_COLLECT) {
        listOf(
            SetIntakePivot(deployed = true),
            SetIntakeRollers(INTAKE_ROLLER_RPS),
            SetFloorSpeed(FLOOR_ROLLER_RPS)
        )
    }

    fun actionIntakeStop(): Task = InstantAutoActionsTask(INTAKE_STOP) {
        listOf(SetIntakeRollers(0.0), SetFloorSpeed(0.0))
    }

    fun actionIntakeStow(): Task = InstantAutoActionsTask(INTAKE_STOW) {
        listOf(SetIntakeRollers(0.0), SetFloorSpeed(0.0), SetIntakePivot(deployed = false))
    }

    fun actionShooterPrepare(): Task = InstantAutoActionsTask(SHOOTER_PREPARE) {
        listOf(
            SetFlywheelSpeed(AUTO_SHOT_RPM),
            SetCowlAngle(AUTO_SHOT_COWL_ROTATIONS),
            SetFlywheelActive(active = true)
        )
    }

    fun actionShooterFeedWhenReady(): Task = FeedWhenReadyTask()

    fun actionShooterStop(): Task =
        InstantAutoActionsTask(SHOOTER_STOP, ::shooterStopActions)

    fun conditionShooterReady(): (RobotState) -> Boolean = ::flywheelIsReady

    private fun shooterStopActions(): List<RobotAction> = listOf(
        SetFlywheelSpeed(0.0),
        SetFlywheelActive(active = false),
        SetFeederSpeed(0.0),
        SetFloorSpeed(0.0),
        // Fail-closed close of any in-flight transfer: consuming the trigger is safer than
        // SetTransferActive(false), which re-armed the one-shot latch mid-cycle.
        CompleteTransfer()
    )

    private class InstantAutoActionsTask(
        descriptor: NamedCommandDescriptor,
        private val actions: () -> List<RobotAction>
    ) : Task {
        override val name: String = descriptor.displayName
        override val requiredResources: Long = descriptor.requiredResources
        private var dispatched = false

        override fun initialize(state: RobotState): List<RobotAction> {
            super.initialize(state)
            dispatched = true
            return actions()
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = dispatched

        override fun releaseRuntimeState() {
            dispatched = false
            super.releaseRuntimeState()
        }
    }

    /** Bounded, fail-closed readiness gate for autonomous note transfer. */
    private class FeedWhenReadyTask : Task {
        override val name: String = SHOOTER_FEED_WHEN_READY.displayName
        override val requiredResources: Long = SHOOTER_FEED_WHEN_READY.requiredResources
        private var feedStartElapsedMs = NOT_STARTED

        override fun initialize(state: RobotState): List<RobotAction> {
            super.initialize(state)
            feedStartElapsedMs = NOT_STARTED
            // A previously consumed trigger (teleop cycle or an earlier auto task) must not
            // block this task's own bounded transfer; re-arm the one-shot latch.
            return listOf(ResetTransferCycle())
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = when {
            feedStartElapsedMs != NOT_STARTED ->
                elapsedMs < feedStartElapsedMs ||
                    elapsedMs - feedStartElapsedMs >= MarvinFeederController.TRANSFER_DURATION_MS
            else -> elapsedMs >= FEED_READY_TIMEOUT_MS
        }

        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            super.execute(state, elapsedMs)
            if (feedStartElapsedMs != NOT_STARTED || elapsedMs >= FEED_READY_TIMEOUT_MS || !flywheelIsReady(state)) {
                return emptyList()
            }
            feedStartElapsedMs = elapsedMs
            return listOf(
                StartTransfer(),
                SetFeederSpeed(MarvinConfig.FEEDER_SHOOT_SPEED_RPS),
                SetFloorSpeed(MarvinConfig.FEEDER_SHOOT_SPEED_RPS)
            )
        }

        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            // Close the transfer through the bounded lifecycle whether it finished or was
            // interrupted: consuming the trigger is the fail-safe outcome either way.
            val actions = mutableListOf<RobotAction>(SetFeederSpeed(0.0), SetFloorSpeed(0.0))
            if (state.superstructure.marvin.transferActive) {
                actions.add(0, CompleteTransfer())
            }
            super.end(state, interrupted)
            return actions
        }

        override fun releaseRuntimeState() {
            feedStartElapsedMs = NOT_STARTED
            super.releaseRuntimeState()
        }

    }

    internal fun flywheelIsReady(state: RobotState): Boolean {
        val flywheel = state.superstructure.marvin.flywheel
        val cowl = state.superstructure.marvin.cowl
        return flywheel.velocityValid && flywheel.allMotorsAtTarget &&
            flywheel.targetVelocityRpm > MINIMUM_READY_RPM &&
            kotlin.math.abs(flywheel.velocityRpm - flywheel.targetVelocityRpm) < RPM_TOLERANCE &&
            cowl.angleValid &&
            cowl.angleRotations.isFinite() &&
            cowl.targetAngleRotations.isFinite() &&
            kotlin.math.abs(cowl.angleRotations - cowl.targetAngleRotations) <= COWL_TOLERANCE_ROTATIONS
    }

    private const val AUTO_SHOT_RPM = 4_000.0
    private const val AUTO_SHOT_COWL_ROTATIONS = 1.55
    private const val INTAKE_ROLLER_RPS = 15.0
    private const val FLOOR_ROLLER_RPS = 10.0
    private const val MINIMUM_READY_RPM = 100.0
    private const val RPM_TOLERANCE = 150.0
    private const val COWL_TOLERANCE_ROTATIONS = 0.05
    private const val FEED_READY_TIMEOUT_MS = 2_000L
    private const val NOT_STARTED = -1L
}
