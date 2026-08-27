package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import org.firstinspires.ftc.teamcode.config.AresRuntimePolicy
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresDrivebaseConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig

/** One descriptor-derived, hold-to-run direction check shown in Driver Station telemetry. */
internal data class MecanumDiagnosticMotorDefinition(
    val label: String,
    val hardwareMapName: String,
    val direction: DcMotorSimple.Direction,
    val control: String,
)

/** Exact generated names and directions used by both the robot runtime and this diagnostic. */
internal fun mecanumDiagnosticMotorDefinitions(): List<MecanumDiagnosticMotorDefinition> = listOf(
    MecanumDiagnosticMotorDefinition(
        label = "Front-left",
        hardwareMapName = GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FL.HARDWARE_ID,
        direction = GeneratedAresFtcMecanumRuntimeConfig.frontLeftDirection,
        control = "A / Cross",
    ),
    MecanumDiagnosticMotorDefinition(
        label = "Front-right",
        hardwareMapName = GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_FR.HARDWARE_ID,
        direction = GeneratedAresFtcMecanumRuntimeConfig.frontRightDirection,
        control = "B / Circle",
    ),
    MecanumDiagnosticMotorDefinition(
        label = "Rear-left",
        hardwareMapName = GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RL.HARDWARE_ID,
        direction = GeneratedAresFtcMecanumRuntimeConfig.rearLeftDirection,
        control = "X / Square",
    ),
    MecanumDiagnosticMotorDefinition(
        label = "Rear-right",
        hardwareMapName = GeneratedAresDrivebaseConfig.Components.FTC_MOTOR_RR.HARDWARE_ID,
        direction = GeneratedAresFtcMecanumRuntimeConfig.rearRightDirection,
        control = "Y / Triangle",
    ),
)

/**
 * Restrained-hardware drivetrain identity and direction diagnostic.
 *
 * Each face button commands exactly one configured motor at 40% output only while held. Names,
 * inversion, and neutral policy come from the generated canonical drivetrain configuration. Any
 * missing motor blocks all motion, and [finally] attempts to neutralize every discovered motor.
 */
@TeleOp(name = "ARES Drivetrain Diagnostic", group = "ARES")
class ARESMecanumDiagnostic : LinearOpMode(), AresFtcRuntimeOptionsProvider {
    override val aresFtcRuntimeOptions: AresFtcRuntimeOptions
        get() = AresRuntimePolicy.options

    private fun configureMotor(definition: MecanumDiagnosticMotorDefinition): DcMotorEx? = runCatching {
        hardwareMap.get(DcMotorEx::class.java, definition.hardwareMapName).also { motor ->
            motor.power = 0.0
            motor.direction = definition.direction
            motor.zeroPowerBehavior = GeneratedAresFtcMecanumRuntimeConfig.driveZeroPowerBehavior
            motor.mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
        }
    }.getOrNull()

    override fun runOpMode() {
        val definitions = mecanumDiagnosticMotorDefinitions()
        val motors = arrayOfNulls<DcMotorEx>(definitions.size)
        val powers = DoubleArray(definitions.size)
        try {
            telemetry.addData("Status", "Initializing generated drivetrain mapping…")
            var index = 0
            var allFound = true
            while (index < definitions.size) {
                val definition = definitions[index]
                motors[index] = configureMotor(definition)
                val found = motors[index] != null
                allFound = allFound && found
                telemetry.addData(
                    "${definition.label} [${definition.hardwareMapName}]",
                    if (found) "FOUND · ${definition.direction}" else "MISSING · MOTION BLOCKED",
                )
                index++
            }
            telemetry.addData(
                "Status",
                if (allFound) "Ready. Put wheels safely off the floor, then press Play."
                else "Fix Driver Station hardware names before pressing Play.",
            )
            telemetry.update()

            waitForStart()
            if (!allFound || isStopRequested) {
                telemetry.addData("Motion blocked", "All four generated motor names must be present.")
                telemetry.update()
                return
            }

            var lastTelemetryMs = 0L
            while (opModeIsActive()) {
                powers[0] = if (gamepad1.a) TEST_POWER else 0.0
                powers[1] = if (gamepad1.b) TEST_POWER else 0.0
                powers[2] = if (gamepad1.x) TEST_POWER else 0.0
                powers[3] = if (gamepad1.y) TEST_POWER else 0.0

                index = 0
                while (index < motors.size) {
                    motors[index]?.power = powers[index]
                    index++
                }

                val nowMs = RobotClock.currentTimeMillis()
                if (nowMs - lastTelemetryMs >= TELEMETRY_PERIOD_MS) {
                    lastTelemetryMs = nowMs
                    telemetry.addData("--- Hold one control; release to stop ---", "")
                    index = 0
                    while (index < definitions.size) {
                        val definition = definitions[index]
                        telemetry.addData(
                            "${definition.control} · ${definition.label} [${definition.hardwareMapName}]",
                            powers[index],
                        )
                        index++
                    }
                    telemetry.update()
                }
                sleep(20)
            }
        } finally {
            var index = 0
            while (index < motors.size) {
                runCatching { motors[index]?.power = 0.0 }
                index++
            }
        }
    }

    private companion object {
        const val TEST_POWER = 0.4
        const val TELEMETRY_PERIOD_MS = 100L
    }
}
