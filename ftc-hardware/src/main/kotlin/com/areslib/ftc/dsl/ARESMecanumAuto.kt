package com.areslib.ftc.dsl

import com.areslib.action.RobotAction
import com.areslib.auto.AresAutoFileLoader
import com.areslib.auto.AutoPose
import com.areslib.auto.AutoRoutineCompiler
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.math.coordinate.AllianceMirroring
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.DriveModel
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.state.Alliance
import com.areslib.telemetry.RobotStatusTracker
import com.areslib.util.PoseStorage
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import java.io.File

/** Immutable, validated autonomous definition produced by [FtcAutoBuilder]. */
data class FtcAutoDefinition internal constructor(
    val documentId: String,
    val alliance: Alliance,
    val maximumRuntimeSeconds: Double
)

/**
 * Student-facing FTC autonomous definition.
 *
 * Both [aresAuto] and [alliance] are required so a copied OpMode cannot silently run the
 * default path or wrong side of the field.
 */
@AresOpModeDsl
class FtcAutoBuilder {
    private var documentId: String? = null
    private var alliance: Alliance? = null
    private var maximumRuntimeSeconds = 29.5

    /** Selects a native `.aresauto` document by its stable lowercase ID. */
    fun aresAuto(id: String) {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "ARES auto ID must be a lowercase filesystem-safe identifier"
        }
        check(documentId == null) { "aresAuto(...) may only be declared once" }
        documentId = id
    }

    /** Declares the alliance for pose seeding and path mirroring. */
    fun alliance(value: Alliance) {
        check(alliance == null) { "alliance(...) may only be declared once" }
        alliance = value
    }

    /** Sets a hard runtime ceiling in seconds. FTC routines may not exceed 30 seconds. */
    fun maximumRuntime(seconds: Double) {
        require(seconds.isFinite() && seconds > 0.0 && seconds <= 30.0) {
            "Autonomous maximum runtime must be finite and in (0, 30] seconds"
        }
        maximumRuntimeSeconds = seconds
    }

    internal fun build(): FtcAutoDefinition = FtcAutoDefinition(
        documentId = requireNotNull(documentId) {
            "Auto definition is missing aresAuto(\"document-id\")"
        },
        alliance = requireNotNull(alliance) {
            "Auto definition is missing alliance(Alliance.RED or Alliance.BLUE)"
        },
        maximumRuntimeSeconds = maximumRuntimeSeconds
    )
}

/** Creates a validated autonomous definition. */
fun ftcAuto(block: FtcAutoBuilder.() -> Unit): FtcAutoDefinition = FtcAutoBuilder().apply(block).build()

/**
 * Generic, fail-closed FTC mecanum autonomous lifecycle.
 *
 * The runner preflights the native `.aresauto` and every referenced capability before START, seeds
 * localization from its explicit pose, refreshes hardware throughout INIT, enforces a match
 * runtime ceiling, and persists pose only when localization remained usable.
 */
abstract class FtcMecanumAutoBase<R> : OpMode() {
    private companion object {
        const val OVERRUN_THRESHOLD_MS = 30L
    }

    abstract fun defineAuto(): FtcAutoDefinition
    abstract fun buildRobot(): R
    abstract fun getMecanumRobot(robot: R): FtcMecanumRobot
    abstract fun updateRobot(robot: R)
    abstract fun closeRobot(robot: R)

    /** Stops shared drive hardware and every registered season subsystem. */
    open fun safeRobot(robot: R) {
        val mecanumRobot = getMecanumRobot(robot)
        mecanumRobot.safeAll()
        mecanumRobot.safeHardware()
    }

    private var definition: FtcAutoDefinition? = null
    private var wrapper: R? = null
    private var robot: FtcMecanumRobot? = null
    private var autoTask: Task? = null
    private var executor: TaskExecutor? = null
    private var configurationError: String? = null
    private var preflightWarnings: List<String> = emptyList()
    private var hardwareInitError: String? = null
    private var deadlineMs = 0L
    private var loopCount = 0L
    private var overrunCount = 0L
    private var started = false
    private var finished = false
    private var poseIsUsable = true
    private var closed = false

    /** Builds hardware, parses the entire auto, and seeds localization before the mode can be armed. */
    final override fun init() {
        val builtDefinition = defineAuto()
        val builtWrapper = buildRobot()
        val builtRobot = getMecanumRobot(builtWrapper)
        definition = builtDefinition
        wrapper = builtWrapper
        robot = builtRobot

        builtRobot.mecanumIO.kS = builtRobot.driveFeedforward.kS.takeIf { it > 0.0 } ?: 0.05
        builtRobot.store.dispatch(RobotAction.SetAlliance(builtDefinition.alliance))
        try {
            val routine = AresAutoFileLoader.load(
                documentId = builtDefinition.documentId,
                directories = autoDirectories(),
                openResource = { resourcePath ->
                    runCatching { hardwareMap.appContext.assets.open(resourcePath) }.getOrNull()
                }
            )
            val transform = allianceTransform(builtDefinition.alliance)
            val startingPose = transform(routine.startingPose)
            builtRobot.resetPose(startingPose, resetHardware = true)
            val compilation = AutoRoutineCompiler(
                trajectoryPlanner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider)),
                follower = builtRobot.pathFollower,
                driveModel = DriveModel.MECANUM,
                limitsForPreset = { preset -> trajectoryLimits(builtRobot, preset) },
                poseTransform = transform
            ).compile(routine)
            preflightWarnings = compilation.issues
                .filter { it.severity != com.areslib.auto.AutoValidationSeverity.ERROR }
                .map { it.message }
            autoTask = compilation.task
            require(compilation.isSuccess) {
                compilation.issues.joinToString(separator = "; ") { it.message }
            }
        } catch (e: Exception) {
            configurationError = e.message ?: e::class.java.simpleName
        }
        publishPreflightStatus()
    }

    /** Keeps sensor caches and diagnostics live while the Driver Station remains in INIT. */
    final override fun init_loop() {
        val activeWrapper = wrapper ?: return
        hardwareInitError = try {
            updateRobot(activeWrapper)
            null
        } catch (e: Exception) {
            "Robot initialization failed: ${e.message ?: e::class.java.simpleName}"
        }
        publishPreflightStatus()
    }

    /** Arms the prebuilt task tree or immediately fails closed when preflight is incomplete. */
    final override fun start() {
        started = true
        RobotStatusTracker.activeOpMode = "Auto"
        val activeWrapper = wrapper ?: return
        val activeRobot = robot ?: return
        val task = autoTask
        val error = blockingError()
        if (task == null || error != null) {
            poseIsUsable = false
            safeRobot(activeWrapper)
            telemetry.addData("AUTO BLOCKED", error ?: "No executable task was produced")
            telemetry.update()
            finished = true
            requestOpModeStop()
            return
        }

        executor = TaskExecutor().apply { addTask(task) }
        deadlineMs = RobotClock.currentTimeMillis() +
            (requireNotNull(definition).maximumRuntimeSeconds * 1_000.0).toLong()
        activeRobot.visionTracker.hasInitializedPoseWithVision = true
    }

    /** Advances the autonomous task graph once per SDK loop. */
    final override fun loop() {
        if (finished) return
        val activeWrapper = wrapper ?: return
        val activeRobot = robot ?: return
        val activeExecutor = executor ?: return
        val loopStartMs = RobotClock.currentTimeMillis()
        if (loopStartMs >= deadlineMs) {
            finishActiveRun("Runtime limit reached; outputs stopped")
            return
        }

        try {
            updateRobot(activeWrapper)
            if (activeExecutor.size == 0) {
                finishActiveRun("Complete")
                return
            }
            val actions = activeExecutor.update(activeRobot.store.state, loopStartMs)
            for (action in actions) activeRobot.store.dispatch(action)
            if (activeExecutor.size == 0) {
                finishActiveRun("Complete")
                return
            }
        } catch (e: Exception) {
            poseIsUsable = false
            telemetry.addData("AUTO ABORTED", e.message ?: e::class.java.simpleName)
            telemetry.update()
            finishActiveRun("Aborted")
            return
        }

        val loopElapsedMs = RobotClock.currentTimeMillis() - loopStartMs
        loopCount++
        if (loopElapsedMs > OVERRUN_THRESHOLD_MS) overrunCount++
        telemetry.addData(
            "Pose",
            "x=%.2f y=%.2f h=%.1f°".format(
                activeRobot.drive.odometryPose.x,
                activeRobot.drive.odometryPose.y,
                Math.toDegrees(activeRobot.drive.odometryPose.heading.radians)
            )
        )
        telemetry.addData("Loop", "${loopElapsedMs}ms; overruns $overrunCount/$loopCount")
        telemetry.update()
    }

    /** Persists a usable final pose, stops outputs, and closes every owned resource exactly once. */
    final override fun stop() {
        if (closed) return
        closed = true
        val activeWrapper = wrapper
        val activeRobot = robot
        val activeDefinition = definition
        try {
            if (started && poseIsUsable && blockingError() == null && activeRobot != null && activeDefinition != null) {
                PoseStorage.currentPose = activeRobot.drive.odometryPose
                PoseStorage.alliance = activeDefinition.alliance
                PoseStorage.hasValidPose = true
            } else if (!poseIsUsable || blockingError() != null) {
                PoseStorage.hasValidPose = false
            }
            if (activeWrapper != null) safeRobot(activeWrapper)
        } finally {
            try {
                if (activeWrapper != null) closeRobot(activeWrapper)
            } finally {
                wrapper = null
                robot = null
                try {
                    com.areslib.ftc.photon.AresPhotonCore.disable()
                } catch (_: Exception) {
                    // Photon is optional; lifecycle cleanup remains best-effort.
                }
            }
        }
    }

    private fun finishActiveRun(status: String) {
        if (finished) return
        finished = true
        val activeWrapper = wrapper
        val activeRobot = robot
        if (activeRobot != null) executor?.clear(activeRobot.store.state)
        if (activeWrapper != null) safeRobot(activeWrapper)
        telemetry.addData("Auto", status)
        telemetry.update()
        requestOpModeStop()
    }

    private fun blockingError(): String? = configurationError ?: hardwareInitError

    private fun allianceTransform(alliance: Alliance): (AutoPose) -> Pose2d = { pose ->
        AllianceMirroring.mirror(
            Pose2d(pose.xMeters, pose.yMeters, Rotation2d(pose.headingRadians)),
            alliance,
            FieldSymmetry.ROTATIONAL
        )
    }

    private fun trajectoryLimits(robot: FtcMecanumRobot, preset: TrajectoryPreset): TrajectoryLimits {
        val scale = when (preset) {
            TrajectoryPreset.SAFE -> 0.45
            TrajectoryPreset.BALANCED -> 0.70
            TrajectoryPreset.FAST -> 0.90
            TrajectoryPreset.ADAPTIVE -> 0.60
        }
        val maximumVelocity = robot.mecanumIO.maxWheelSpeedMetersPerSecond * scale
        val maximumAcceleration = robot.store.state.tuning.pathAccelerationLimit * scale
        val driveRadius = (robot.trackWidthMeters + robot.wheelBaseMeters) * 0.5
        return TrajectoryLimits(
            maxVelocityMps = maximumVelocity,
            maxAccelerationMps2 = maximumAcceleration,
            maxJerkMps3 = maximumAcceleration * 4.0,
            maxCentripetalAccelerationMps2 = maximumAcceleration * 0.75,
            maxAngularVelocityRps = maximumVelocity / driveRadius,
            maxAngularAccelerationRps2 = maximumAcceleration / driveRadius
        )
    }

    private fun autoDirectories(): List<File> = listOf(
        File("/sdcard/FIRST/ares/autos"),
        File("TeamCode/src/main/assets/ares/autos"),
        File("src/main/assets/ares/autos"),
        File("../TeamCode/src/main/assets/ares/autos")
    )

    private fun publishPreflightStatus() {
        val activeDefinition = definition ?: return
        val error = blockingError()
        telemetry.addData("Auto", activeDefinition.documentId)
        telemetry.addData("Alliance", activeDefinition.alliance)
        if (error == null) {
            telemetry.addData("Status", "READY - press START")
            preflightWarnings.take(3).forEachIndexed { index, warning ->
                telemetry.addData("Warning ${index + 1}", warning)
            }
        } else {
            telemetry.addData("Status", "BLOCKED")
            telemetry.addData("Fix", error)
        }
        telemetry.update()
    }
}
