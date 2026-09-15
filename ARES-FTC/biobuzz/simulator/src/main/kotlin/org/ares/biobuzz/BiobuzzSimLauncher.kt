package org.ares.biobuzz

import com.areslib.sim.DesktopSimLauncher
import com.areslib.sim.SimInteractionModel
import com.areslib.state.RobotFieldManager
import com.areslib.state.RobotFieldConfig
import com.areslib.networktables.NT4Server
import com.areslib.ftc.FtcBaseRobot
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SimInteractionRole
import com.areslib.telemetry.SimInputBridge
import java.nio.file.Files
import java.nio.file.Path
import org.dyn4j.dynamics.Body
import org.dyn4j.world.World

/** Normal Dashboard Driver Station server; no additional window or physics runner. */
object BiobuzzSimLauncher {
    @JvmStatic fun main(args: Array<String>) = DesktopSimLauncher.launch(args + "--headless", BiobuzzInteractionModel())
}

internal class BiobuzzInteractionModel : SimInteractionModel {
    private val outputs = BiobuzzRobotOutputs.load(Path.of("."))
    private var game: BiobuzzSimulation? = null
    private var config: RobotFieldConfig? = null
    private var resetPending = true
    private var frame = 0L
    private var redReleaseHeld = false
    private var blueReleaseHeld = false
    override fun reset() { resetPending = true }

    override fun update(world: World<Body>, robotBody: Body, gamePieces: MutableList<Body>,
        intakeApplied: Boolean, flywheelApplied: Boolean, transferApplied: Boolean,
        currentInventoryCount: Int, robotHeading: Double, robotX: Double, robotY: Double): Int {
        val active = RobotFieldManager.activeConfig
        if (active !== config || resetPending) {
            game?.close()
            gamePieces.forEach(world::removeBody)
            gamePieces.clear()
            config = active
            resetPending = false
            redReleaseHeld = false
            blueReleaseHeld = false
            game = if (active.id == "ftc-2026-2027-biobuzz") BiobuzzSimulation(world, robotBody, active, robotConfiguration = outputs.configuration) else null
        }
        val simulation = game ?: run { NT4Server.publishTopic(BiobuzzTelemetry.TOPIC, "{}"); return 0 }
        outputs.sample(FtcBaseRobot.activeInstance)
        val command = SimInputBridge.currentFrame()
        val enabled = FtcBaseRobot.activeInstance != null && command.sessionNonce != 0L && command.isTeleopMode
        simulation.command(BiobuzzControl(enabled = enabled,
            intake = outputs.intakeActive, shoot = outputs.flywheelActive && outputs.feederActive,
            speed = outputs.shotSpeed, elevation = outputs.elevation))
        // Human loading is a field action, separate from the generated robot's gamepad bindings.
        if (enabled && command.isIntaking && !redReleaseHeld) simulation.releaseNectar(true)
        if (enabled && command.isFlywheelOn && !blueReleaseHeld) simulation.releaseNectar(false)
        redReleaseHeld = enabled && command.isIntaking
        blueReleaseHeld = enabled && command.isFlywheelOn
        simulation.step(0.02)
        if (++frame % 5L == 0L) {
            NT4Server.publishTopic(BiobuzzTelemetry.TOPIC, BiobuzzTelemetry.encode(
                BiobuzzFrame(1, frame, active.id, active.revision.toInt(), simulation.snapshot())))
        }
        return simulation.inventoryCount
    }
}

/** Read-only bridge from Robot Builder's generated FTC IO over simulated motors, after its controller and safety gates. */
internal class BiobuzzRobotOutputs(
    intake: com.areslib.subsystem.SubsystemDocument,
    shooter: com.areslib.subsystem.SubsystemDocument,
) {
    private val collector = intake.implementation.simulation.interaction
    private val launcher = shooter.implementation.simulation.interaction
    private val intakeMotorName = intake.hardware.single { it.hardwareId == collector.triggerActuatorId }.connection.hardwareMapName!!
    private val feederMotorName = shooter.hardware.single { it.hardwareId == launcher.triggerActuatorId }.connection.hardwareMapName!!
    private val flywheelMotorName = shooter.hardware.single { it.hardwareId == "flywheel" }.connection.hardwareMapName!!
    private var robot: FtcBaseRobot? = null
    private var intakeMotor: DcMotorEx? = null
    private var feederMotor: DcMotorEx? = null
    private var flywheelMotor: DcMotorEx? = null
    private var intakeVoltage = 0.0
    private var feederVoltage = 0.0
    private var flywheelVoltage = 0.0

    /** FTC's normal generated registry uses Ftc IO over the simulator's cached motor doubles. */
    fun sample(activeRobot: FtcBaseRobot?) {
        check(!FtcBaseRobot.isAndroid)
        if (robot !== activeRobot) {
            robot = activeRobot
            intakeMotor = activeRobot?.hardwareMap?.get(DcMotorEx::class.java, intakeMotorName)
            feederMotor = activeRobot?.hardwareMap?.get(DcMotorEx::class.java, feederMotorName)
            flywheelMotor = activeRobot?.hardwareMap?.get(DcMotorEx::class.java, flywheelMotorName)
        }
        intakeVoltage = (intakeMotor?.power ?: 0.0) * 12.0
        feederVoltage = (feederMotor?.power ?: 0.0) * 12.0
        flywheelVoltage = (flywheelMotor?.power ?: 0.0) * 12.0
    }
    val configuration = BiobuzzSimulation.RobotConfiguration(collector.storageCapacity,
        collector.intakeDistanceMeters, collector.captureRadiusMeters)
    val elevation = Math.toRadians(launcher.launchElevationDeg)
    val intakeActive get() = intakeVoltage > collector.triggerThreshold
    val feederActive get() = feederVoltage > launcher.triggerThreshold
    val flywheelActive get() = flywheelVoltage > 1.0
    val shotSpeed get() = launcher.launchSpeedMps * (flywheelVoltage / 12.0).coerceIn(0.0, 1.0)

    companion object {
        fun load(project: Path): BiobuzzRobotOutputs {
            val documents = Files.list(project.resolve(".ares/subsystems")).use { paths ->
                paths.filter { it.toString().endsWith(".aressubsystem") }.map {
                    SubsystemDocumentCodec.decode(Files.readString(it))
                }.toList()
            }
            return BiobuzzRobotOutputs(
                documents.single { it.implementation.simulation.interaction.role == SimInteractionRole.INTAKE_COLLECTOR },
                documents.single { it.implementation.simulation.interaction.role == SimInteractionRole.PROJECTILE_LAUNCHER })
        }
    }
}
