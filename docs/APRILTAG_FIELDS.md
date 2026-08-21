# AprilTag field maps

`src/main/deploy/paths/field.json` is the canonical map loaded by the robot runtime and desktop
simulation. It also produces the WPILib `AprilTagFieldLayout` consumed when a reviewed camera adapter
is added; the hardware-neutral starter does not invent a camera. The initial file is intentionally
empty because an FRC starter is not tied to one season.

In ARES Analytics, open **Field Studio**, choose **Import AprilTag map**, and select either:

- official WPILib AprilTag JSON;
- a Limelight `.fmap`; or
- an existing ARES field JSON.

Review tag IDs, 3D positions, roll/pitch/yaw, field dimensions, and import warnings before applying.
Export keeps ARES canonical JSON as the source of truth while allowing WPILib or Limelight copies for
other tools. A healthy camera with no target is distinct from a disconnected or stale camera.
