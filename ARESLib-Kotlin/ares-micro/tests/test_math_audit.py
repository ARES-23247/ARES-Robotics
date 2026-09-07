import math
import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from ares_micro.drivetrain import DifferentialDrivetrain, MecanumDrivetrain
from ares_micro.kinematics import wrap_angle


class Motor:
    def __init__(self):
        self.position = 0.0
        self.effort = 0.0

    def get_position(self):
        return self.position

    def set_effort(self, value):
        self.effort = value


class MathAuditTest(unittest.TestCase):
    def test_saturation_preserves_differential_curvature(self):
        left, right = Motor(), Motor()
        drive = DifferentialDrivetrain(left, right, track_width=1.0, max_speed=1.0)
        drive.drive(1.0, 2.0)
        self.assertAlmostEqual(left.effort, 0.0)
        self.assertAlmostEqual(right.effort, 1.0)
        drive.drive(2.0, 2.0)  # Requested wheel speeds 1 and 3.
        self.assertAlmostEqual(left.effort, 1.0 / 3.0)
        self.assertAlmostEqual(right.effort, 1.0)

    def test_saturation_preserves_mecanum_translation_and_rotation_ratio(self):
        motors = [Motor() for _ in range(4)]
        drive = MecanumDrivetrain(*motors, track_width=1.0, wheel_base=1.0, max_speed=1.0)
        drive.drive(2.0, 1.0, 1.0)  # Requested wheel speeds 0, 4, 2, 2.
        for motor, expected in zip(motors, (0.0, 1.0, 0.5, 0.5)):
            self.assertAlmostEqual(motor.effort, expected)

    def test_quarter_circle_displacement_is_chord_not_arc_length(self):
        for mecanum in (False, True):
            motors = [Motor() for _ in range(4 if mecanum else 2)]
            heading = [0.0]
            cls = MecanumDrivetrain if mecanum else DifferentialDrivetrain
            drive = cls(*motors, wheel_radius=1.0, heading_supplier=lambda: heading[0])
            for motor in motors:
                motor.position = 1.0 / (2.0 * math.pi)
            heading[0] = math.pi / 2.0
            drive.update_odometry(1.0)
            self.assertAlmostEqual(drive.x, 2.0 / math.pi)
            self.assertAlmostEqual(drive.y, 2.0 / math.pi)

    def test_nonfinite_drive_input_stops_every_motor(self):
        motors = [Motor(), Motor()]
        drive = DifferentialDrivetrain(*motors)
        drive.drive(float("nan"), 0.0)
        self.assertTrue(all(motor.effort == 0.0 for motor in motors))


if __name__ == "__main__":
    unittest.main()
