# Physical commissioning

Simulation success is not permission to run a robot. Before deploying to a Control Hub:

1. Put the robot on blocks, remove game pieces, and keep the Driver Station disabled.
2. Replace every placeholder dimension and tuning value with reviewed project data.
3. Configure the Robot Controller with the exact names shown by Hardware Setup: `fl`, `fr`, `rl`,
   `rr`, and `imu`, unless the canonical descriptor was deliberately changed.
4. Confirm physical motor corner and inversion with the hold-to-run drivetrain diagnostic.
5. Record a Hardware Review after checking neutral mode, safe outputs, limits, wiring, and addresses.
6. Import and review the current season's AprilTag map, then measure any physical camera transform.
7. Calibrate encoder scale and controls with restrained tests, preserving measurement evidence.
8. Re-run generation, verification, unit tests, the simulator, and APK assembly.

Never copy values from another team's robot merely because its project builds. The default profile is
deliberately conservative and uncalibrated.
