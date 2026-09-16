# BIOBUZZ Robot Builder example

Create a BIOBUZZ example from Studio's workspace selector. Open Robot Builder to edit the generated mecanum drivetrain, BIOBUZZ Intake, and BIOBUZZ Shooter. Their canonical `.ares` documents own Redux state, actions, cached motor IO, controllers, safe output handling, and controller bindings. Build outputs contain the generated Kotlin; edit the builder documents instead of generated files.

Open Dashboard and start Local Sim. Select BIOBUZZ TeleOp, INIT, START, and arm the Dashboard drive controls. Use the existing Field 2D widget; Field Editor edits the same field document and Push updates the runtime.

| Keyboard | Gamepad | Action while held |
| --- | --- | --- |
| W/S, A/D | Left stick | Field-centric translation |
| Space | Analog stick movement | Slow keyboard positioning (10% speed) |
| Left/Right | Right stick | Rotation |
| J | A | Intake, up to four balls |
| L | B | Full flywheel voltage for hive shots |
| U | X | Lower flywheel voltage for flower shots |
| Shift | Right trigger | Feed one ball per press; hold L or U at the same time |
| Q / E | Dashboard only | Release one red / blue human-player nectar |

Use one flywheel preset at a time. The Controls editor owns the gamepad actions. Simulation reads generated FTC IO's **applied motor outputs** (the normal simulator motor doubles), not desired key states. The intake interaction settings control reach and storage (the game maximum is four); the shooter interaction settings control launch speed and elevation. The lower voltage preset scales launch speed. These are simple simulation approximations, not a measured flywheel model.

Pollen is 2.8 inches and 0.055 pounds; nectar is 3.6 inches and 0.091 pounds. Flowers display P/N separately in bottom-to-top order. Pollen can leave the bottom until nectar blocks the aperture. Each hive has two cells and two stable positions. A nominal 0.440-pound detent load tips with eight pollen, five nectar, or the equivalent mixed mass. The cell spills and the other cell becomes available. The threshold matches the supplied counts; exact pivot geometry, friction, and torque are not measured.

The project ships its CAD-derived static background and canonical field document. Moving cells and balls are live overlays. One existing simulator world owns robot motion and contacts; the season interaction model owns balls, flower storage, and hive motion. Complete NT4 snapshots feed the Dashboard and replay.

This is a simulation-only reference until its hardware is reviewed and validated. Lightbot is a separate example. A score widget, 3D, web hosting, and multiplayer are deferred.

The example also configures an optional `limelight` camera for the four official BioBuzz AprilTag
clusters. Its preset uses 3.25-inch tags (82.55 mm) with IDs 30–45 and the SDK 12.0 cell-opening
offsets. Set the camera to a Classic 36h11, Full 3D pipeline. The runtime reconstructs the shared
aiming point from whichever members remain visible and publishes camera-relative targets in
`robot.base.store.state.vision.clusterTargets`. Configured targeting cameras cannot feed moving
tags into field localization. Automatic aiming/firing is not bound to any button by this change.
Check freshness and enable, select a cluster/camera, and apply measured shooter mounting offsets
before using the targets in a controller. The simulator integration test injects optical poses;
real camera detection, calibration, range, and motion latency remain hardware checkpoints.
