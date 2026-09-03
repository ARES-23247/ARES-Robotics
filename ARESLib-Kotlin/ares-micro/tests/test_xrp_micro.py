import unittest
import math
import sys
import os

# Add parent directory to path to import ares_micro
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from ares_micro.kinematics import DifferentialDriveKinematics, MecanumKinematics, wrap_angle
from ares_micro.sparkfun_otos import SparkFunOTOS
from ares_micro.drivetrain import DifferentialDrivetrain, MecanumDrivetrain
from ares_micro.opmode import Waypoint, PidPoseFollower, AutonomousRoutine
from ares_micro.telemetry import XrpTelemetryServer
from ares_micro.robot import XrpRobot

class TestKinematics(unittest.TestCase):
    def test_differential_kinematics(self):
        diff = DifferentialDriveKinematics(track_width_meters=0.155)
        # Straight forward at 0.5 m/s
        left, right = diff.to_wheel_speeds(0.5, 0.0)
        self.assertAlmostEqual(left, 0.5)
        self.assertAlmostEqual(right, 0.5)

        vx, omega = diff.to_chassis_speeds(left, right)
        self.assertAlmostEqual(vx, 0.5)
        self.assertAlmostEqual(omega, 0.0)

        # Pure rotation CCW at 2.0 rad/s
        left_rot, right_rot = diff.to_wheel_speeds(0.0, 2.0)
        self.assertTrue(left_rot < 0)
        self.assertTrue(right_rot > 0)
        vx_rot, omega_rot = diff.to_chassis_speeds(left_rot, right_rot)
        self.assertAlmostEqual(vx_rot, 0.0)
        self.assertAlmostEqual(omega_rot, 2.0)

    def test_mecanum_kinematics(self):
        mec = MecanumKinematics(track_width_meters=0.155, wheel_base_meters=0.140)
        # Forward
        fl, fr, bl, br = mec.to_wheel_speeds(1.0, 0.0, 0.0)
        self.assertAlmostEqual(fl, 1.0)
        self.assertAlmostEqual(fr, 1.0)
        self.assertAlmostEqual(bl, 1.0)
        self.assertAlmostEqual(br, 1.0)
        vx, vy, omega = mec.to_chassis_speeds(fl, fr, bl, br)
        self.assertAlmostEqual(vx, 1.0)
        self.assertAlmostEqual(vy, 0.0)
        self.assertAlmostEqual(omega, 0.0)

        # Lateral strafe left (+Y)
        fl, fr, bl, br = mec.to_wheel_speeds(0.0, 1.0, 0.0)
        self.assertAlmostEqual(fl, -1.0)
        self.assertAlmostEqual(fr, 1.0)
        self.assertAlmostEqual(bl, 1.0)
        self.assertAlmostEqual(br, -1.0)
        vx, vy, omega = mec.to_chassis_speeds(fl, fr, bl, br)
        self.assertAlmostEqual(vx, 0.0)
        self.assertAlmostEqual(vy, 1.0)
        self.assertAlmostEqual(omega, 0.0)

    def test_wrap_angle(self):
        self.assertAlmostEqual(wrap_angle(0.0), 0.0)
        self.assertAlmostEqual(wrap_angle(3.0 * math.pi), math.pi)
        self.assertAlmostEqual(wrap_angle(-3.0 * math.pi), -math.pi)


class MockI2C:
    def __init__(self):
        self.registers = {}
        # Preload product ID 0x5C
        self.registers[SparkFunOTOS.REG_PRODUCT_ID] = bytes([0x5C])
        # Preload 12 bytes of telemetry at REG_POS_X:
        # posX = 5000 (0.5m), posY = 2000 (0.2m), posH = 1570 (1.57 rad ~ pi/2)
        import struct
        self.registers[SparkFunOTOS.REG_POS_X] = struct.pack('<hhhhhh', 5000, 2000, 1570, 1000, 0, 500)

    def readfrom_mem(self, addr, reg, nbytes):
        if reg in self.registers:
            val = self.registers[reg]
            if len(val) >= nbytes:
                return val[:nbytes]
        return bytes(nbytes)

    def writeto_mem(self, addr, reg, data):
        self.registers[reg] = data


class TestSparkFunOTOS(unittest.TestCase):
    def test_otos_mock_read(self):
        mock_i2c = MockI2C()
        otos = SparkFunOTOS(i2c=mock_i2c)
        self.assertTrue(otos.begin())

        x, y, h, vx, vy, omega = otos.update()
        self.assertAlmostEqual(x, 0.5, places=3)
        self.assertAlmostEqual(y, 0.2, places=3)
        self.assertAlmostEqual(h, 1.57, places=2)
        self.assertAlmostEqual(vx, 0.1, places=3)


class TestAutonomousRoutine(unittest.TestCase):
    def test_pid_follower_and_routine(self):
        routine = AutonomousRoutine(
            name="Test Auto",
            waypoints=[
                Waypoint(x=1.0, y=0.0, heading_rad=0.0, speed=0.5, tolerance=0.05),
            ]
        )
        # Initial step far from target: should drive forward
        vx, omega, finished = routine.update(current_x=0.0, current_y=0.0, current_heading=0.0)
        self.assertTrue(vx > 0)
        self.assertFalse(finished)

        # Step at waypoint: should reach and finish
        vx, omega, finished = routine.update(current_x=1.0, current_y=0.0, current_heading=0.0)
        self.assertTrue(finished)


class TestXrpRobotLifecycle(unittest.TestCase):
    def test_robot_lifecycle_and_drive(self):
        robot = XrpRobot(drivetrain_type="differential", use_otos=False)
        self.assertEqual(robot.mode, XrpRobot.STATE_INIT)

        # Send START command via telemetry buffer simulation
        robot.telemetry.last_command = "START"
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_TELEOP)

        # Send leased drive frame: forward at 0.5 m/s
        robot.telemetry.last_drive_frame = [0.5, 0.0, 0.0, 0.0]
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_TELEOP)

        # Send STOP command
        robot.telemetry.last_command = "STOP"
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_DISABLED)

if __name__ == "__main__":
    unittest.main()
