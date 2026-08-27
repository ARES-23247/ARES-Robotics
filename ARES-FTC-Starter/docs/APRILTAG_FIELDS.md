# AprilTag fields and camera setup

`TeamCode/src/main/assets/paths/field.json` is the one canonical field document used by generated
autonomous code, the desktop simulator, and FTC AprilTag localization. The generic starter is not
tied to a season, so its initial tag list is intentionally empty.

In ARES Analytics, open **Field Studio** and choose **Import AprilTag map**. ARES accepts:

- an official or team-authored FTC/ARES field JSON;
- official WPILib AprilTag JSON; or
- a Limelight `.fmap`.

ARES shows a preview before replacing or merging anything. Review every tag ID, family, physical
size, 3D position, roll, pitch, yaw, conflict, and omitted-source warning. FTC AprilTag localization
requires a declared tag family and physical size; ARES rejects a map that cannot satisfy that
contract instead of silently inventing values.

The robot and simulator load their accepted tag poses from this document at startup. ARESLib can
also build the FTC `AprilTagLibrary` from it when a reviewed VisionPortal camera is added; the generic
starter does not invent or open a camera that the project has not configured. A tag that is absent
from the canonical file is not accepted as field localization evidence. A camera that is healthy but
currently sees no target is different from a disconnected, stale, or invalid camera.

Before using a physical camera:

1. Add the webcam or VisionPortal device in the Robot Controller configuration using the name shown
   by **Hardware Setup**.
2. Measure the camera pose on the robot and record it in Robot Studio; do not copy another robot's
   transform.
3. Confirm the season tag IDs, sizes, and poses against the official field source.
4. Test while Disabled or on blocks, then verify the reported pose from several known locations.
5. Save the reviewed canonical field and regenerate before deployment.

Simulation verifies coordinate and state-flow behavior. It does not prove camera calibration,
mounting rigidity, exposure, focus, or physical field placement.
