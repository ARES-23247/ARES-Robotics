# ARES FRC Starter

This is the hardware-neutral, simulation-first starter used by ARES Robotics Studio when a student creates
a new FRC robot. Canonical `.ares` documents are the source of truth. Generated plumbing is verified
on every build and user-owned source is never silently replaced.

## First run

1. Open the project in ARES Robotics Studio.
2. Use **Field Studio** to import the current season's official WPILib AprilTag JSON.
3. Use **Robot Studio** to configure the drivetrain, controllers, mechanisms, safety, and simulation.
4. Choose **Local Sim**, launch the simulator, and select **Start driving**. ARES Studio safely
   enables the simulation-only FRC Driver Station and establishes a leased control connection; no
   separate WPILib window is required.
5. Complete **Hardware Review** before adding a physical adapter or deploying to a RoboRIO.

ARES Studio discovers a compatible JDK 17 installation, including the WPILib JDK, for project
verification and simulation. Advanced users can still run `./gradlew simulateJava` directly. A
different JDK can bundle an older Microsoft C++ runtime and cause WPILib to fail before robot code
starts; ARES reports that as a workstation setup problem instead of treating it as a robot defect.

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

## Build sources

Normal builds resolve immutable ARES artifacts from Maven Central or the ARES GitHub Maven channel.
Library contributors may use `-ParesUseSiblingLib=true`, or an isolated release-validation repository:

```powershell
.\gradlew.bat test -ParesVersion=<candidate> `
  -ParesRepository=file:///C:/absolute/path/ARESLib-Kotlin/build/release-repository
```

Do not use `mavenLocal()` for validation.

See [Hardware Review](docs/HARDWARE_REVIEW.md) and [AprilTag fields](docs/APRILTAG_FIELDS.md).
