# ARES FRC Starter

This is the hardware-neutral, simulation-first starter used by ARES Robotics Studio when a student creates
a new FRC robot. Canonical `.ares` documents are the source of truth. Generated plumbing is verified
on every build and user-owned source is never silently replaced.

## First run

1. Open the project in ARES Robotics Studio.
2. Use **Field Studio** to import the current season's official WPILib AprilTag JSON.
3. Use **Robot Studio** to configure the drivetrain, controllers, mechanisms, safety, and simulation.
4. Choose **Local Sim** and launch the simulator. Select **Start driving** for TeleOp, or choose a
   compiled routine in the simulator strip and select **Run auto**. ARES Studio safely enables the
   simulation-only FRC Driver Station and establishes a leased control connection; no separate
   WPILib window is required. For autonomous, confirm the robot locks the exact requested routine
   ID before motion begins.
5. Complete **Hardware Review** before adding a physical adapter or deploying to a RoboRIO.

ARES Studio discovers a compatible JDK 17 installation, including the WPILib JDK, for project
verification and simulation. Advanced users can still run `./gradlew simulateJava` directly. A
different JDK can bundle an older Microsoft C++ runtime and cause WPILib to fail before robot code
starts; ARES reports that as a workstation setup problem instead of treating it as a robot defect.

The simulator still uses WPILib HAL underneath Studio. This preserves RoboRIO timing, mode
transitions, and WPILib device behavior. The separate HALSim control GUI is an opt-in mentor
diagnostic; it is not needed and does not open in the normal student workflow.

The Studio control bridge exists only in desktop simulation. It requires a neutral first frame,
rejects stale or out-of-order commands, expires after 500 ms of receiver time, publishes an atomic
acknowledgement, and disables the simulated Driver Station if the desktop control lease is lost.
It cannot enable or control a physical RoboRIO.

The starter includes the Phoenix 6 API required by the current GUI-generated TalonFX mechanism
adapter, so a saved FRC subsystem compiles in the same project that created it. That dependency is
hardware capability, not hardware authorization. The checked-in composition keeps both drivetrain
and generated mechanism adapters simulated on a real RoboRIO until a reviewed project explicitly
installs physical adapters. A generic project must not guess CAN IDs, inversions, gearing, current
limits, neutral modes, encoder relationships, or approval state.

The starter's CTRE swerve descriptor therefore uses CAN IDs 1–13 only as a complete, unique
simulation address set. Those values let a new student build and drive the simulator immediately;
they are not evidence about a real robot. Robot Studio keeps physical deployment blocked until the
team imports its own CTRE Tuner output and completes the hardware review.

## Build sources

Normal builds resolve immutable ARES artifacts from Maven Central or the ARES GitHub Maven channel.
Library contributors may use `-ParesUseSiblingLib=true`, or an isolated release-validation repository:

```powershell
.\gradlew.bat test -ParesVersion=<candidate> `
  -ParesRepository=file:///C:/absolute/path/ARESLib-Kotlin/build/release-repository
```

Do not use `mavenLocal()` for validation.

See [Hardware Review](docs/HARDWARE_REVIEW.md) and [AprilTag fields](docs/APRILTAG_FIELDS.md).
