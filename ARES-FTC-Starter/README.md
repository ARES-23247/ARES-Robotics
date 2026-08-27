# ARES FTC Zero-Code Starter

This is the generic, simulation-first FTC project created by ARES Analytics. It is deliberately
separate from Team 23247's `ARES-FTC` season robot so a new team starts without inherited mechanisms,
field assets, routines, hardware constants, or calibration values.

## First run

1. Open this folder in ARES Analytics.
2. Set your team, season, and robot name in **Project Identity**.
   Keep **Standard FTC SDK** for first bring-up. Experimental ARES Photon and the Limelight proxy
   are explicit reviewed project choices, never enabled merely because a dependency is present.
3. Review the four motors and IMU in **Drivebase Builder**.
4. Import or create the season field and AprilTags in **Field Studio**.
5. Add mechanisms in **Subsystem Builder** and map controls in **Controller Bindings**.
6. Select **Verify & build**, then practice with **Local Simulator**.
7. Complete **Hardware Setup** with a mentor before physical deployment.

The initial robot has four required motors named `fl`, `fr`, `rl`, and `rr`, plus the Control Hub IMU
named `imu`. Wheel encoders and the IMU provide the generic localization path. The tuning profile is
an uncalibrated simulation baseline, not a claim about a physical robot.

Normal driving starts in closed-loop chassis-velocity mode. All-zero custom motor PIDF values mean
“retain the FTC SDK motor-type defaults”; measured overrides, feedforward, rotation-lock gains, and
anti-push position-hold gains live in the reviewed Tuning profile. Rotation lock starts enabled.
Anti-push starts disabled and fails closed whenever pose feedback is stale or invalid. In **TeleOp
Controls**, choose the **Drive assists** category to bind explicit Enable, Disable, or Toggle actions
for either feature—no Kotlin edit is required.

The dashboard Control Hub Health card reports the generated command-path selection and, on real
hardware, whether Photon actually became active. **Selected** and **active** are intentionally
different states; a simulator cannot validate the experimental REV Hub interception path.

```powershell
# Focused local development against a sibling ARESLib checkout
.\gradlew.bat generateAresProject verifyAresProject :TeamCode:testDebugUnitTest :simulator:test :TeamCode:assembleDebug -ParesUseSiblingLib=true

# Normal released-artifact build
.\gradlew.bat generateAresProject verifyAresProject :TeamCode:testDebugUnitTest :simulator:test :TeamCode:assembleDebug
```

See [docs/STARTER_ARCHITECTURE.md](docs/STARTER_ARCHITECTURE.md) and
[docs/PHYSICAL_COMMISSIONING.md](docs/PHYSICAL_COMMISSIONING.md). Field and camera setup is covered
in [docs/APRILTAG_FIELDS.md](docs/APRILTAG_FIELDS.md). Teams that prefer normal Kotlin development
can close Studio and follow [Code-first and hybrid projects](docs/CODE_FIRST_AND_HYBRID.md).
