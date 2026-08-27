# Starter architecture

Canonical `.ares` documents define the robot. Gradle emits mechanical code into
`TeamCode/build/generated/ares`; students do not edit those outputs. The small lifecycle adapters in
`TeamCode/src/main` are marked `GENERATED STARTER` and exist only to connect generated project code to
the FTC SDK OpMode lifecycle.

`.ares/project.json` also owns the FTC runtime policy. Generated constants configure every TeleOp,
autonomous OpMode, and robot facade consistently before hardware construction. New starters choose
the standard FTC SDK command path and leave the Limelight HTTP proxy off; Robot Studio can propose a
reviewed change without adding hand-written marker interfaces or classpath auto-detection.

```text
gamepad → generated controller binding → Redux action/state → drive controller
        → cached IO contract → FTC hardware or desktop simulator adapter
```

The starter contains only a generic FTC mecanum declaration, four motors, the Control Hub IMU, one
drive-recovery action, and empty mechanism/routine catalogs. Robot Studio adds subsystems,
superstructures, actions, and autonomous routines without requiring a student to delete example
competition logic first.

`ARES-FTC` remains Team 23247's production season repository and an advanced worked example.
`ARESLib-Kotlin` owns reusable runtime/generator behavior. This repository is the independently
versioned novice starting point distributed by ARES Analytics.
