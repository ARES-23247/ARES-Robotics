# FIRST Tech Challenge Sample OpModes Reference

ARES Robotics provides a clean, streamlined starter template for FTC teams. To keep the repository lightweight and free of third-party vendor clutter, stock sample OpModes from the FIRST FTC SDK are referenced externally rather than committed into the robot project tree.

## Accessing Official FIRST Samples
The complete catalog of official FIRST Tech Challenge sample OpModes (including AprilTag, color sensor, motor bulk read, and IMU tutorials) is maintained upstream:

- **FIRST FTC App Repository**: [github.com/FIRST-Tech-Challenge/FtcRobotController](https://github.com/FIRST-Tech-Challenge/FtcRobotController)
- **External Samples Directory**: FtcRobotController/src/main/java/org/firstinspires/ftc/robotcontroller/external/samples
- **FTC Online Documentation**: [ftc-docs.firstinspires.org](https://ftc-docs.firstinspires.org)

## Recommended ARES Starter Patterns
Instead of raw legacy samples, teams using the ARES Starter are encouraged to use:
- **AresRobot**: Zero-code composition root and hardware mapping (TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/AresRobot.kt).
- **AresAutoDSL & AresTeleOpDSL**: Declarative, type-safe autonomous and teleoperated action pipelines with zero GC allocations in control loops.
- **Field Studio & Subsystem Builder**: Interactive, visual subsystem configuration integrated into ARES Robotics Studio.
