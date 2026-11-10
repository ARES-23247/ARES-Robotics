# FTC SDK 12 migration

ARES 20 uses FTC SDK 12.0.0. FTC applications and starters use the official Gradle 9.1 / AGP
8.13.2 toolchain and require Android Studio Narwhal 3 Feature Drop or later. Keep the Robot
Controller and Driver Station on compatible official versions. Controller validation remains
separate from desktop tests.

## Compilation and desktop mocks

`ftc-hardware` compiles against the actual RobotCore, Hardware, FtcCommon, and Vision artifacts.
Those compile-only SDK artifacts and Android stubs are not packaged or published as runtime
dependencies. Android consumers supply the real SDK; desktop tests and simulators supply
`ftc-mocks`. `:ftc-hardware:test` includes a bytecode check of referenced SDK fields, methods,
descriptors, static members, and interface/class shape against the mocks. Do not compile robot
adapters against mocks as a workaround for an SDK error.

SDK 12 makes `AprilTagDetection` a common base. Use `AprilTagSingleDetection.id` for singletons
and `AprilTagClusterDetection.metadata` for clusters. SDK fields remain Java fields in the mocks;
the processor's fresh-detections getter returns `ArrayList`, matching its real JVM descriptor.
The mock API changes and explicit localization setup are intentional breaking changes from ARES 19.

## Stationary field localization

`FtcVisionPortalIO` requires an explicit set of reviewed stationary tag IDs and one measured
robot-to-camera pose before publishing any absolute position. Its defaults are safe for a
targeting-only camera. Do not copy moving game targets into the stationary field map.

```kotlin
RobotFieldManager.setActiveConfig(reviewedField)
val processor = FtcAprilTagLibraryFactory.configure(
    AprilTagProcessor.Builder(), reviewedField,
).build()
val vision = FtcVisionPortalIO(
    processor,
    cameraPoses = listOf(measuredRobotToCamera),
    localizationTagIds = reviewedStationaryTagIds,
    sourceId = "localization-camera",
)
```

Field and camera-mount translations use meters and the ARES +X forward/+Y left/+Z up body
convention. `RobotFieldAprilTag.roll/pitch/yaw` are stored in degrees; camera-mount `Rotation3d`
angles and internal robot headings use radians. The factory and adapter convert field angles at
the SDK boundary; do not preconvert the field document to radians.
Maintained FTC field tag rotations use the SDK optical basis (+X right/+Y down/+Z into the face).
`FtcTagPoseConvention.FTC_OPTICAL` preserves those existing layouts. For WPILib/PhotonVision tag
rotations (+X outward/+Y left/+Z up), select `WPILIB_NWU` on both the factory and adapter. Review
the import's tag orientation convention explicitly; numeric Euler values alone cannot identify it.
The SDK library factory converts that selected tag basis and expresses SDK metadata in inches,
including field position. The adapter reconstructs the field pose from that reviewed map and
camera mounting, rather than trusting an SDK robot pose configured with unrelated field metadata.

Each detection supplies its distance unit. The adapter's `outputAngleUnit` must match the SDK
builder's output angle setting (default degrees). Capture time is converted once from SDK
nanoseconds into RobotClock milliseconds; future, missing, and older-than-500ms observations are
discarded. Missing SDK ambiguity is marked unavailable instead of reported as perfect confidence.

## Moving targets and native clusters

BIOBUZZ tags move. Use the official game cluster library for aiming on the controller, then
pass its processor to `FtcVisionPortalIO` without a localization whitelist. The adapter publishes
`clusterTargets`, never absolute field poses, from SDK cluster detections. Seeing a cluster latches
the camera into targeting-only operation for its lifetime, including mixed singleton/cluster
frames. Recreate the adapter after deliberately reconfiguring the camera for another purpose.
Use a separate, calibrated camera with stationary landmarks for localization.

Target coordinates use camera optical +X right/+Y down/+Z forward, meters. The SDK cluster's
short name identifies the target (full name is the fallback). The SDK exposes a percentage, not
the number of observed members; counts conservatively report one and no inter-tag spread is
inferred. These fields do not establish multi-tag accuracy or covariance. Consumers must check
freshness, alliance/goal suitability, their own actuator calibration, and explicit enable.

Outputs are borrowed until the next poll. Use `ownedCopy()` for field measurements and
`snapshot()` for targeting observations before retaining them. Native camera processing remains
a controller capability; desktop tests inject `VisionIO`/processor fixtures.

See FIRST's [SDK 12 release notes](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/v12.0/README.md#version-120-20260907-090034)
and [AprilTag cluster guide](https://ftc-docs.firstinspires.org/apriltag-clusters).
