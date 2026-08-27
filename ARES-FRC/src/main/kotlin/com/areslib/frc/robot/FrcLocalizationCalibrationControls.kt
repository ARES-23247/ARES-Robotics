package com.areslib.frc.robot

import com.areslib.frc.vision.FrcLocalizationCalibrationSession
import com.areslib.frc.vision.FrcVisionTracker
import com.areslib.math.estimation.LocalizationCalibrationTestType
import edu.wpi.first.wpilibj.XboxController

/**
 * Driver-station button edge detection and telemetry for one [FrcLocalizationCalibrationSession].
 *
 * Extracted from `ARESRobot.testPeriodic` so the robot shell stays orchestration-only. The
 * control map is fixed: A toggles recording, B cycles the test type, X/Y mark start/end,
 * Back/zero and Start/seed (suppressed while the dual-operator homing combo is held), bumpers
 * adjust truth heading ±5°, and the D-pad nudges truth X/Y by 5 cm. Odometry test types
 * disable vision fusion for the duration of the session.
 */
class FrcLocalizationCalibrationControls(
    private val session: FrcLocalizationCalibrationSession,
    private val timestampMs: () -> Long,
) {
    private val buttonEdges = BooleanArray(BUTTON_COUNT)

    /** Applies one Test-mode control frame; call from testPeriodic after drive handling. */
    fun update(
        controller: XboxController,
        tracker: FrcVisionTracker?,
        telemetry: com.areslib.telemetry.ITelemetry,
        homingComboPressed: Boolean,
    ) {
        val pov = controller.pov

        if (rising(0, controller.aButton)) session.toggleContinuousRecording()
        if (rising(1, controller.bButton)) session.cycleTestType()
        tracker?.fusionEnabled = when (session.testType) {
            LocalizationCalibrationTestType.ODOMETRY_TRANSLATION,
            LocalizationCalibrationTestType.ODOMETRY_ROTATION -> false
            else -> true
        }
        if (rising(2, controller.xButton)) session.markStart(timestampMs())
        if (rising(3, controller.yButton)) session.markEnd(timestampMs())
        if (rising(4, controller.backButton && !homingComboPressed)) session.zeroTruth()
        if (rising(5, controller.startButton && !homingComboPressed)) session.seedPoseToTruth(timestampMs())
        if (rising(6, controller.leftBumperButton)) session.adjustTruth(deltaHeading = -Math.toRadians(5.0))
        if (rising(7, controller.rightBumperButton)) session.adjustTruth(deltaHeading = Math.toRadians(5.0))
        if (rising(8, pov == 0)) session.adjustTruth(deltaY = 0.05)
        if (rising(9, pov == 180)) session.adjustTruth(deltaY = -0.05)
        if (rising(10, pov == 270)) session.adjustTruth(deltaX = -0.05)
        if (rising(11, pov == 90)) session.adjustTruth(deltaX = 0.05)

        session.periodic(timestampMs())

        telemetry.putString("Calibration/Localization/TestType", session.testType.name)
        telemetry.putNumber("Calibration/Localization/RunId", session.runId.toDouble())
        telemetry.putBoolean("Calibration/Localization/Recording", session.continuousRecording)
        telemetry.putNumber("Calibration/Localization/TruthX", session.truthX)
        telemetry.putNumber("Calibration/Localization/TruthY", session.truthY)
        telemetry.putNumber("Calibration/Localization/TruthHeadingRad", session.truthHeading)
        telemetry.putNumber("Calibration/Localization/DroppedSamples", session.droppedSampleCount.toDouble())
    }

    /** Clears every tracked edge so a re-entered Test mode does not fire stale releases. */
    fun reset() {
        buttonEdges.fill(false)
    }

    private fun rising(index: Int, pressed: Boolean): Boolean {
        val rising = pressed && !buttonEdges[index]
        buttonEdges[index] = pressed
        return rising
    }

    private companion object {
        const val BUTTON_COUNT = 12
    }
}
