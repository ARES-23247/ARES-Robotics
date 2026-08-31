package org.aresfirst.marvin

import com.areslib.frc.FRCSwerveHardwareIO
import com.areslib.frc.FrcLimelightIO
import com.areslib.drivetrain.SwerveOffsetManager
import org.aresfirst.marvin.config.CanonicalDrivebaseConfig
import org.aresfirst.marvin.hardware.FRCClimberHardwareIO
import org.aresfirst.marvin.hardware.FRCCowlHardwareIO
import org.aresfirst.marvin.hardware.FRCFeederHardwareIO
import org.aresfirst.marvin.hardware.FRCFloorHardwareIO
import org.aresfirst.marvin.hardware.FRCFlywheelHardwareIO
import org.aresfirst.marvin.hardware.FRCIntakeHardwareIO
import org.aresfirst.marvin.sim.FrcDashboardDriveInput
import com.areslib.hardware.actuator.ClimberIO
import com.areslib.hardware.actuator.CowlIO
import com.areslib.hardware.actuator.FeederIO
import com.areslib.hardware.actuator.FloorIO
import com.areslib.hardware.actuator.FlywheelIO
import com.areslib.hardware.actuator.IntakeIO
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.hardware.vision.CompositeVisionIO
import com.areslib.hardware.vision.VisionIO
import com.areslib.state.RobotFieldAprilTag
import com.ctre.phoenix6.CANBus
import com.ctre.phoenix6.hardware.TalonFX
import edu.wpi.first.math.VecBuilder
import edu.wpi.first.units.Units
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.PowerDistribution

/** One complete set of season IO selected by the FRC composition root. */
internal data class FrcSeasonHardware(
    val swerveIO: SwerveHardwareIO?,
    val visionIO: VisionIO?,
    val flywheelIO: FlywheelIO,
    val cowlIO: CowlIO,
    val intakeIO: IntakeIO,
    val feederIO: FeederIO,
    val floorIO: FloorIO,
    val climberIO: ClimberIO,
    val simulation: Dyn4jSimulation?,
    val dashboardDriveInput: FrcDashboardDriveInput?,
    val powerDistribution: PowerDistribution?,
)

/** Selects real Marvin IO or the dedicated FRC physics adapters without leaking either into lifecycle code. */
internal object FrcSeasonHardwareFactory {
    fun create(
        isReal: Boolean,
        fieldContract: FrcFieldContract?,
        canBus: CANBus,
    ): FrcSeasonHardware = if (isReal) {
        createReal(fieldContract, canBus)
    } else {
        createSimulation(fieldContract)
    }

    private fun createReal(fieldContract: FrcFieldContract?, canBus: CANBus): FrcSeasonHardware {
        val powerDistribution = try {
            PowerDistribution()
        } catch (error: Exception) {
            DriverStation.reportError(
                "ARES: PowerDistribution initialization failed; current monitoring will fail closed: ${error.message}",
                false,
            )
            null
        }
        val leftMasterFX = TalonFX(9, canBus)
        val leftFollowerFX = TalonFX(10, canBus)
        val rightMasterFX = TalonFX(11, canBus)
        val rightFollowerFX = TalonFX(12, canBus)
        val cowlFX = TalonFX(13, canBus)
        val pivotFX = TalonFX(14, canBus)
        val rollerFX = TalonFX(15, canBus)
        val floorFX = TalonFX(16, canBus)
        val climberFX = TalonFX(19, canBus)
        val feederFX = TalonFX(20, canBus)

        val activeOffsets = SwerveOffsetManager.loadOffsets(CanonicalDrivebaseConfig.profiledOffsets())
        val ctreDrivetrain = frc.robot.generated.TunerConstants.TunerSwerveDrivetrain(
            frc.robot.generated.TunerConstants.DrivetrainConstants,
            0.0,
            VecBuilder.fill(0.1, 0.1, 0.1),
            VecBuilder.fill(0.9, 0.9, 0.9),
            frc.robot.generated.TunerConstants.createFrontLeft(Units.Rotations.of(activeOffsets.frontLeft)),
            frc.robot.generated.TunerConstants.createFrontRight(Units.Rotations.of(activeOffsets.frontRight)),
            frc.robot.generated.TunerConstants.createBackLeft(Units.Rotations.of(activeOffsets.backLeft)),
            frc.robot.generated.TunerConstants.createBackRight(Units.Rotations.of(activeOffsets.backRight)),
        )

        // Each camera retains its independently surveyed robot-space transform from its web UI.
        val validTagIds = fieldContract?.config?.apriltags
            ?.sortedBy(RobotFieldAprilTag::id)
            ?.map(RobotFieldAprilTag::id)
            ?.toIntArray()
            ?: IntArray(0)
        val visionIO = if (validTagIds.isEmpty()) {
            null
        } else {
            CompositeVisionIO(
                listOf(
                    FrcLimelightIO("limelight-shooter", validFiducialIds = validTagIds),
                    FrcLimelightIO("limelight-back", validFiducialIds = validTagIds),
                )
            )
        }

        return FrcSeasonHardware(
            swerveIO = FRCSwerveHardwareIO(ctreDrivetrain),
            visionIO = visionIO,
            flywheelIO = FRCFlywheelHardwareIO(leftMasterFX, leftFollowerFX, rightMasterFX, rightFollowerFX),
            cowlIO = FRCCowlHardwareIO(cowlFX),
            intakeIO = FRCIntakeHardwareIO(pivotFX, rollerFX),
            feederIO = FRCFeederHardwareIO(feederFX),
            floorIO = FRCFloorHardwareIO(floorFX),
            climberIO = FRCClimberHardwareIO(climberFX),
            simulation = null,
            dashboardDriveInput = null,
            powerDistribution = powerDistribution,
        )
    }

    private fun createSimulation(fieldContract: FrcFieldContract?): FrcSeasonHardware {
        val simulation = fieldContract?.config?.let { config ->
            Dyn4jSimulation(config = config, seed = 42L)
        } ?: Dyn4jSimulation(seed = 42L)
        return FrcSeasonHardware(
            swerveIO = null,
            visionIO = null,
            flywheelIO = simulation.flywheelIO,
            cowlIO = simulation.cowlIO,
            intakeIO = simulation.intakeIO,
            feederIO = simulation.feederIO,
            floorIO = simulation.floorIO,
            climberIO = simulation.climberIO,
            simulation = simulation,
            dashboardDriveInput = FrcDashboardDriveInput(),
            powerDistribution = null,
        )
    }
}
