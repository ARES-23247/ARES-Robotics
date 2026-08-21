# ARES FRC Starter

This is the hardware-neutral, simulation-first starter used by ARES Analytics when a student creates
a new FRC robot. Canonical `.ares` documents are the source of truth. Generated plumbing is verified
on every build and user-owned source is never silently replaced.

## First run

1. Open the project in ARES Analytics.
2. Use **Field Studio** to import the current season's official WPILib AprilTag JSON.
3. Use **Robot Studio** to configure the drivetrain, controllers, mechanisms, safety, and simulation.
4. Run `./gradlew simulateJava` to practice with the WPILib desktop Driver Station.
5. Complete **Hardware Review** before adding a physical adapter or deploying to a RoboRIO.

On Windows, launch simulation from the WPILib terminal or configure Gradle to use
`C:\Users\Public\wpilib\2026\jdk`. A different JDK can bundle an older Microsoft C++ runtime and
WPILib will fail before robot code starts; ARES should report that as a workstation setup problem.

The checked-in starter has no vendor motor-controller dependency and cannot energize physical drive
hardware. This is deliberate: a generic project must not guess CAN IDs, inversions, gearing, current
limits, neutral modes, or encoder relationships.

## Build sources

Normal builds resolve immutable ARES artifacts from Maven Central or the ARES GitHub Maven channel.
Library contributors may use `-ParesUseSiblingLib=true`, or an isolated release-validation repository:

```powershell
.\gradlew.bat test -ParesVersion=<candidate> `
  -ParesRepository=file:///C:/absolute/path/ARESLib-Kotlin/build/release-repository
```

Do not use `mavenLocal()` for validation.

See [Hardware Review](docs/HARDWARE_REVIEW.md) and [AprilTag fields](docs/APRILTAG_FIELDS.md).
