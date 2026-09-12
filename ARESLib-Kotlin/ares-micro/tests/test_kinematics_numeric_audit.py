import decimal
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from ares_micro.kinematics import (
    DifferentialDriveKinematics, MecanumKinematics, arc_chord_scale, wrap_angle,
)


class KinematicsNumericAuditTest(unittest.TestCase):
    def assert_close(self, actual, expected):
        self.assertTrue(math.isfinite(actual), (actual, expected))
        self.assertTrue(math.isclose(actual, expected, rel_tol=2e-14, abs_tol=0.0), (actual, expected))

    def test_wrap_preserves_small_angles_and_signed_endpoints(self):
        for angle in (0.0, -0.0, 1e-20, -1e-20, math.pi, -math.pi):
            with self.subTest(angle=angle):
                actual = wrap_angle(angle)
                self.assertEqual(angle, actual)
                self.assertEqual(math.copysign(1.0, angle), math.copysign(1.0, actual))
        self.assertEqual(math.pi, wrap_angle(3 * math.pi))
        self.assertEqual(-math.pi, wrap_angle(-3 * math.pi))

    def test_wrap_matches_exact_remainder_of_finite_float_inputs(self):
        with decimal.localcontext() as context:
            context.prec = 1100
            pi = decimal.Decimal.from_float(math.pi)
            period = decimal.Decimal.from_float(2 * math.pi)
            for angle in (math.nextafter(math.pi, math.inf), math.nextafter(-math.pi, -math.inf), 1e16, -1e16, sys.float_info.max, -sys.float_info.max):
                remainder = decimal.Decimal.from_float(angle) % period
                if remainder > pi:
                    remainder -= period
                elif remainder < -pi:
                    remainder += period
                self.assertEqual(float(remainder), wrap_angle(angle), angle)

    def test_differential_recovers_finite_average_and_rotation_without_sum_overflow(self):
        kinematics = DifferentialDriveKinematics(2.0)
        with decimal.localcontext() as context:
            context.prec = 1100
            for left, right in ((1e308, 1e308), (-1e308, 1e308), (1e308, -1e308)):
                vx, omega = kinematics.to_chassis_speeds(left, right)
                a, b = decimal.Decimal.from_float(left), decimal.Decimal.from_float(right)
                self.assert_close(vx, float((a + b) / 2))
                self.assert_close(omega, float((b - a) / 2))

    def test_mecanum_large_geometry_retains_rotation_and_finite_averages(self):
        kinematics = MecanumKinematics(1e308, 1e308)
        vx, vy, omega = kinematics.to_chassis_speeds(1e308, 1e308, 1e308, 1e308)
        self.assert_close(vx, 1e308)
        self.assert_close(vy, 0.0)
        self.assert_close(omega, 0.0)
        vx, vy, omega = kinematics.to_chassis_speeds(-1.0, 1.0, -1.0, 1.0)
        self.assert_close(vx, 0.0)
        self.assert_close(vy, 0.0)
        self.assert_close(omega, 1e-308)

    def test_smallest_positive_geometry_does_not_erase_its_turn_lever(self):
        smallest = float.fromhex("0x0.0000000000001p-1022")
        differential = DifferentialDriveKinematics(smallest)
        self.assertEqual((-smallest, smallest), differential.to_wheel_speeds(0.0, 2.0))
        odd_track = DifferentialDriveKinematics(3 * smallest)
        self.assertEqual((-3 * smallest, 3 * smallest), odd_track.to_wheel_speeds(0.0, 2.0))
        mecanum = MecanumKinematics(smallest, smallest)
        self.assertEqual(smallest, mecanum.k)
        self.assertEqual((-smallest, smallest, -smallest, smallest), mecanum.to_wheel_speeds(0.0, 0.0, 1.0))
        self.assert_close(mecanum.to_chassis_speeds(0.0, smallest, 0.0, 0.0)[2], 0.25)
        self.assert_close(mecanum.to_chassis_speeds(0.0, smallest, 0.0, smallest * 2)[2], 0.75)

    def test_physical_basis_signs_and_mixed_motion_round_trip(self):
        mecanum = MecanumKinematics(0.4, 0.6)
        self.assertEqual((1.0, 1.0, 1.0, 1.0), mecanum.to_wheel_speeds(1.0, 0.0, 0.0))
        self.assertEqual((-1.0, 1.0, 1.0, -1.0), mecanum.to_wheel_speeds(0.0, 1.0, 0.0))
        self.assertEqual((-0.5, 0.5, -0.5, 0.5), mecanum.to_wheel_speeds(0.0, 0.0, 1.0))
        for vx, vy, omega in ((0.4, -0.6, 1.2), (-1.5, 0.5, -3.0), (0.125, 0.25, 0.5)):
            for actual, expected in zip(mecanum.to_chassis_speeds(*mecanum.to_wheel_speeds(vx, vy, omega)), (vx, vy, omega)):
                self.assert_close(actual, expected)
        differential = DifferentialDriveKinematics(0.4)
        self.assertEqual((-0.2, 0.2), differential.to_wheel_speeds(0.0, 1.0))
        for actual, expected in zip(differential.to_chassis_speeds(*differential.to_wheel_speeds(0.5, -2.0)), (0.5, -2.0)):
            self.assert_close(actual, expected)

    def test_arc_chord_is_even_stable_at_zero_and_rejects_nonfinite_angles(self):
        self.assertEqual(1.0, arc_chord_scale(0.0))
        self.assertEqual(1.0, arc_chord_scale(1e-20))
        self.assert_close(arc_chord_scale(math.pi), 2 / math.pi)
        for angle in (1e-8, 2e-6, 0.25, math.pi, 4.0):
            self.assertEqual(arc_chord_scale(angle), arc_chord_scale(-angle))
            self.assert_close(arc_chord_scale(angle), math.sin(angle / 2) / (angle / 2))
        for angle in (math.nan, math.inf, -math.inf):
            with self.assertRaises(ValueError):
                arc_chord_scale(angle)
            with self.assertRaises(ValueError):
                wrap_angle(angle)


if __name__ == "__main__":
    unittest.main()
