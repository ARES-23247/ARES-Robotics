"""Analytic boundaries for the reusable MicroPython profile implementation."""
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ares_micro.control_math import TrapezoidProfile, feedforward


class ControlMathTest(unittest.TestCase):
    def test_overspeed_brakes_and_uses_remaining_time(self):
        for direction in (-1, 1):
            profile = TrapezoidProfile()
            profile.reset(0, direction*2)
            profile.advance(0.02, 0, direction*100, 1, 1)
            self.assertAlmostEqual(direction*0.0398, profile.position)
            self.assertAlmostEqual(direction*1.98, profile.velocity)
            profile.reset(0, direction*2)
            profile.advance(1.25, 0, direction*100, 1, 1)
            self.assertAlmostEqual(direction*1.75, profile.position)
            self.assertAlmostEqual(direction, profile.velocity)

    def test_reversal_and_arrival_bound_acceleration_and_displacement(self):
        for velocity in (-2, 2):
            for goal in (-10, -0.1, 0, 0.1, 10):
                with self.subTest(velocity=velocity, goal=goal):
                    profile = TrapezoidProfile()
                    profile.reset(0, velocity)
                    for _ in range(1000):
                        previous_x, previous_v = profile.position, profile.velocity
                        profile.advance(0.02, 0, goal, 1, 1)
                        self.assertLessEqual(abs(profile.velocity-previous_v), 0.020000001)
                        self.assertLessEqual(abs(profile.position-previous_x-previous_v*0.02), 0.000200001)
                    self.assertAlmostEqual(goal, profile.position)
                    self.assertAlmostEqual(0, profile.velocity)

    def test_partitioning_time_preserves_trajectory(self):
        for goal in (-10, 0.1, 10):
            for duration in (0.1, 1, 1.25, 3, 20):
                whole, split = TrapezoidProfile(), TrapezoidProfile()
                whole.reset(0, 2)
                split.reset(0, 2)
                whole.advance(duration, 0, goal, 1, 1)
                for _ in range(100): split.advance(duration/100, 0, goal, 1, 1)
                self.assertAlmostEqual(whole.position, split.position)
                self.assertAlmostEqual(whole.velocity, split.velocity)

    def test_invalid_and_overflowing_profiles_do_not_commit_partial_state(self):
        profile = TrapezoidProfile()
        profile.reset(0, 0)
        for dt, goal, vmax, amax in ((0, 1, 1, 1), (0.02, math.nan, 1, 1),
                                   (0.02, 1, math.inf, 1), (0.02, 1, 1, -1)):
            with self.assertRaises(ValueError): profile.advance(dt, 0, goal, vmax, amax)
            self.assertEqual(0, profile.position)
            self.assertEqual(0, profile.velocity)
        profile.reset(sys.float_info.max, 1e308)
        with self.assertRaises(ValueError): profile.advance(0.02, 0, sys.float_info.max, 1e308, 1e308)
        self.assertEqual(sys.float_info.max, profile.position)

    def test_linkage_defaults_and_center_at_pivot_match_schema(self):
        loop = dict(feedforward=dict(kind="TWO_DOF_ARM", linkageJoint=1, kG=1))
        linkage = dict(enabled=True, joint1AngleFieldId="a", joint2AngleFieldId="b")
        self.assertAlmostEqual(0.23*9.80665, feedforward(loop, {"a": 0, "b": 0}, linkage))
        linkage.update(link1CenterOfMassMeters=0, link2CenterOfMassMeters=0)
        self.assertAlmostEqual(0.105*9.80665, feedforward(loop, {"a": 0, "b": 0}, linkage))


if __name__ == "__main__":
    unittest.main()
