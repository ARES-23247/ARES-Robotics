# Localization Q/R Calibration and Recovery

ARES records the same calibration schema on FTC and FRC, then fits it offline. The fitter never changes robot constants automatically: a human reviews the results, applies them, and repeats the validation run.

## What is being calibrated

- **Q (process noise)** describes uncertainty accumulated by wheel/Pinpoint odometry. It is measured with repeated, surveyed translation and rotation routes while vision is disabled or ignored for the route endpoints.
- **R (measurement noise)** describes the camera observation error. It is measured while the robot is motionless at surveyed poses, with independent truth rather than another output from the localization pipeline.
- **NIS** checks whether accepted vision residuals agree with the filter's predicted innovation covariance. For a 3-DOF update, roughly 95% should fall below 7.815.
- **NEES** checks estimated-pose error against covariance using independent ground truth. For 3 DOF, roughly 95% should also fall below 7.815.

Use a tape/laser and field marks at minimum. A total station, motion-capture system, or carefully surveyed AprilTag fixture is better. Never use Limelight output as the truth used to calibrate Limelight noise.

## Data collection plan

Collect at least:

1. Ten stationary poses distributed across the field, including near/far, single-tag, multi-tag, and different headings. Record 100 or more fresh frames at each pose for both MegaTag1 and MegaTag2.
2. Six or more straight surveyed routes in both field axes. Use several distances and repeat each direction.
3. Six or more surveyed in-place rotations in both directions, including full turns.
4. Several combined driving runs with independently measured endpoint truth for final NIS/NEES validation.

For route tests, enter the surveyed start pose, mark **START**, drive the route, enter the surveyed end pose, then mark **END**. Do not enter the odometry result as truth.

## FTC workflow

Run the `ARES Localization Calibration` TeleOp.

- **B** cycles the test type.
- **D-pad** changes surveyed X/Y in 0.05 m steps.
- **Bumpers** change surveyed heading in 5 degree steps.
- **Back** zeros surveyed truth; **Start** seeds localization to that truth.
- **A** toggles recording of fresh camera frames.
- **X** records a route START and seeds the robot pose; **Y** records the route END.

Completed files are written under `/sdcard/FIRST/telemetry_logs/` and can be pulled through the existing local log server.

## FRC workflow

Select Driver Station **Test** mode. The calibration session starts automatically and publishes status under `Calibration/Localization/*`.

The driver-controller mapping is the same as FTC: normal sticks drive, **B** selects the test type, **D-pad** edits surveyed X/Y, **bumpers** edit heading, **Back** zeros truth, **Start** seeds CTRE localization, **A** toggles fresh-frame recording, and **X/Y** mark route START/END. **X** also seeds the entered surveyed start pose. The drivetrain enters X-brake at Test initialization and leaves it when a drive command is applied. Completed files are stored in the robot process `./logs/` directory (normally beneath `/home/lvuser`).

FRC's CTRE estimator remains authoritative. Camera fusion is automatically paused for `ODOMETRY_TRANSLATION` and `ODOMETRY_ROTATION`, while camera frames remain available for logging, then restored for the vision/combined tests and on Test exit. The process-noise fit is evidence for CTRE state-standard-deviation and drivetrain-geometry tuning, not a second ARES EKF layered on top. Camera R is passed per observation through `addVisionMeasurement`; MegaTag2 heading remains effectively ignored during normal fusion.

## Fit a report

Copy the CSV files to the development machine and run:

```powershell
cd ARESLib-Kotlin
.\gradlew.bat :core:fitLocalizationCalibration `
  '-PcalibrationFiles=C:\cal\run1.csv|C:\cal\run2.csv' `
  '-PcalibrationOutput=C:\cal\localization-report.json'
```

The report contains MegaTag1/MegaTag2 bias and standard deviations, normalized odometry process-noise estimates, sample counts, NIS/NEES summaries, and warnings when the dataset is too small. Correct repeatable bias (camera extrinsics, wheel radius, Pinpoint offsets, module geometry) before inflating Q or R to hide it.

## Stolen/kidnapped robot behavior

- FTC and FRC use MegaTag2 for normal translation fusion and do not trust its yaw.
- Both preserve an independent MegaTag1 full pose for recovery.
- FRC recovery is allowed only while disabled or after the chassis has been measured stationary for 0.5 s. FTC uses its corresponding stationary/rejection gate.
- The recovery pose must be field-plausible, materially different from the current estimate, and consistent across repeated fresh frames. Single-tag recovery requires twice as many confirmations.
- On confirmation, FTC resets its pose pipeline; FRC calls CTRE `seedPose`. Normal moving vision never performs a hard snap.

Physically moving a disabled robot is therefore recoverable, but one bad frame cannot relocate it.
