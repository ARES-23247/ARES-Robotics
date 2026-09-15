# Limelight 3A moving-target clusters

ARES supports rigid AprilTag clusters as camera-relative aiming points. Each visible member's
3D pose is transformed to the same configured point. Agreeing members are averaged; missing
members do not move the aiming origin. A single visible member is sufficient. Conflicting
two-tag results are rejected; larger sets require a mutually consistent strict majority.
This is point reconstruction from Limelight's individual poses, **not** the FTC SDK's joint
corner/PnP 6DOF cluster solver. There is no claim that tag count removes correlated camera error.

## Camera setup

1. Use a fiducial pipeline, the correct AprilTag family, and **Full 3D**. For the BioBuzz preset,
   use Classic 36h11, tag size **82.55 mm (3.25 inches)**, and IDs **30 through 45**.
2. Calibrate the camera at its operating resolution. Tune exposure with the target moving.
3. The adapter reads `FiducialResult.getTargetPoseCameraSpace()` from the FTC SDK (available in
   11.1.0). A field map, MegaTag pose, robot heading seed, Python pipeline, and SDK 12 migration
   are not required for this path. Raw poses are used; do not apply a POI offset a second time.
4. Configure clusters once during robot setup, before the first sensor update:

   ```kotlin
   robot.base.limelightIO?.configureTargetClusters(BiobuzzTagClusters.clusters)
   ```

   `BiobuzzTagClusters` is season-owned code in the BioBuzz example, not a generic library default.
   For another rigid object, provide `AprilTagCluster` and `AprilTagClusterMember` definitions.
   Member offsets are **tag center to aiming point** in the raw optical tag object frame, meters.
   Configuration requires distinct cluster IDs and distinct member tag IDs across that camera.

Configured cluster cameras publish **no field-localization measurements**, even when a static
tag or an existing field map produces a MegaTag pose. Use a separately configured camera for
fixed-field localization. `CompositeVisionIO` preserves separate target sources and supports
individually configured children; configuring the composite applies clusters to every child.

## Consume through Redux

Read `robot.base.store.state.vision.clusterTargets` after the normal robot update. Each immutable
snapshot identifies its cluster, camera, frame, capture timestamp, contributing/visible tag count,
and agreement spread. Coordinates are camera optical **X right, Y down, Z forward**, in meters.
`bearingRadians` is positive left of the optical axis; `elevationRadians` is positive up.
`rangeMeters` is the 3D lens-to-point distance. These are not robot-center coordinates.

A controller must select an explicit cluster and camera, require `target.isFresh(nowMs)`, require
normal robot enable/arm, and account for camera-to-shooter mounting before applying an aim command.
Tag visibility is not permission to drive or shoot. The new observation action never updates the EKF
or reseeds odometry. Missing/stale/future frames and disconnection clear the current target list.
Capture latency is subtracted once; repeatedly polling a cached frame never renews its timestamp.
Close clears borrowed camera outputs; retained immutable snapshots still require freshness and enable.

The default age limit is 250 ms and the default disagreement bound is 0.15 m. These are validity
limits, not guaranteed aiming accuracy. Tune them against measured shot error and camera latency.
The geometry engine reuses its buffers; immutable snapshots allocate only when publishing a new
observation to Redux, following the existing vision ownership boundary.

## BioBuzz integration and validation

The BioBuzz overlay declares an optional `limelight` device in its canonical drivetrain document
and configures the four official clusters in TeleOp setup. The existing simulation-only hardware
gate remains. Generated plumbing stays in build outputs. Existing intake, flywheel, and feed
bindings retain their behavior; adding cluster observations does not enable automatic aiming/firing.

The geometry comes from FIRST's published `Vision:12.0.0` source archive,
`AprilTagGameDatabase.getBioBuzzTagLibrary()` and `getBioBuzzCluster()`. IDs 30/34/38/42 start the
red scoring/red audience/blue audience/blue scoring four-tag groups. Tag-center X positions are
[-6.5, -2.75, 2.75, 6.5] inches, Y=7.1874 inches, Z=-5.622 inches in the SDK raw object frame.
Negate these translations and convert inches to meters for tag-to-opening offsets.

JVM tests cover independent SciPy rotation-vector reference trajectories, all nonempty visibility
subsets, outliers, units, clock/latency failures, multiple cameras, pooled snapshot ownership, replay,
and exclusion from EKF localization. The exported BioBuzz robot test uses actual generated code and
simulated IO with injected optical poses, including simultaneous mechanism commands and Stop.
It does not test image detection, rolling shutter, camera calibration, or physical field motion.

Before hardware use: confirm the selected cluster identity and sign of each axis with one tag
visible at a time; check the reported opening against a measured point at several ranges and cell
angles; measure latency and jitter under drive motion; verify feedback-loss and Stop behavior with
the real aim controller. Until these pass, this is software validation, not camera or robot readiness.

Sources: [FIRST SDK release notes](https://github.com/FIRST-Tech-Challenge/FtcRobotController/blob/master/README.md),
[FIRST Vision source archive](https://repo.maven.apache.org/maven2/org/firstinspires/ftc/Vision/12.0.0/Vision-12.0.0-sources.jar),
[Limelight FTC API](https://docs.limelightvision.io/docs/docs-limelight/apis/ftc-programming).
