# Module 4: Pit Operations & Swerve Zeroing

In this module, you will learn pit procedures: swerve module zeroing, live PID slider tuning, and WPILib log exporting.

---

## 1. Swerve Zero Calibration

1. Physically align all 4 swerve modules facing forward (+X).
2. On ARES-Analytics, navigate to **Drivetrain Control $\rightarrow$ Calibrate Swerve**.
3. Click **Save Offsets**. The robot calculates module zero offsets, saves `swerve_offsets_runtime.json`, and creates a timestamped flash backup in `backups/`.

---

## 2. Live PID Tuning

1. Adjust $kP, kI, kD$ sliders under the **Tuning** tab.
2. Observe real-time step responses on the live graph canvas.
