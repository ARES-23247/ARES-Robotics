"""Independent waypoint and routine boundary checks."""

import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ares_micro.opmode import AutonomousRoutine, PidPoseFollower, Waypoint


class AutonomousAuditTest(unittest.TestCase):
    def test_position_alone_does_not_complete_final_heading(self):
        routine = AutonomousRoutine(waypoints=[Waypoint(0, 0, math.pi / 2)])
        vx, omega, done = routine.update(0, 0, 0)
        self.assertEqual(0, vx)
        self.assertGreater(omega, 0)
        self.assertFalse(done)
        self.assertEqual((0, 0, True), routine.update(0, 0, math.pi / 2))

    def test_waypoint_speed_bounds_forward_and_reverse_commands(self):
        follower = PidPoseFollower()
        for x in (-5, 5):
            with self.subTest(x=x):
                vx, omega, done = follower.calculate_differential(0, 0, 0, Waypoint(x, 0, speed=0.12))
                self.assertAlmostEqual(math.copysign(0.12, x), vx)
                self.assertAlmostEqual(0, omega)
                self.assertFalse(done)

    def test_perpendicular_target_turns_without_full_sideways_approach(self):
        vx, omega, done = PidPoseFollower().calculate_differential(0, 0, 0, Waypoint(0, 1))
        self.assertAlmostEqual(0, vx, places=12)
        self.assertGreater(omega, 0)
        self.assertFalse(done)

    def test_heading_wrap_and_explicit_heading_tolerance(self):
        target = Waypoint(0, 0, -math.pi + 0.02, heading_tolerance_rad=0.05)
        self.assertEqual((0, 0, True), PidPoseFollower().calculate_differential(0, 0, math.pi - 0.02, target))
        target.heading_tolerance = 0.01
        vx, omega, done = PidPoseFollower().calculate_differential(0, 0, math.pi - 0.02, target)
        self.assertEqual(0, vx)
        self.assertGreater(omega, 0)
        self.assertFalse(done)

    def test_invalid_waypoint_and_follower_configuration(self):
        for kwargs in (dict(x=float("nan")), dict(y=float("inf")), dict(heading_rad=float("nan")),
                       dict(speed=0), dict(speed=-1), dict(tolerance=0), dict(tolerance=float("nan")),
                       dict(heading_tolerance_rad=0)):
            values = dict(x=0, y=0)
            values.update(kwargs)
            with self.subTest(values=values), self.assertRaises(ValueError):
                Waypoint(**values)
        for kwargs in (dict(kp_linear=float("nan")), dict(kp_angular=-1), dict(max_speed=0),
                       dict(max_omega=float("inf"))):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                PidPoseFollower(**kwargs)

    def test_nonfinite_pose_cannot_become_a_saturated_output(self):
        for pose in ((float("nan"), 0, 0), (0, float("inf"), 0), (0, 0, float("nan"))):
            with self.subTest(pose=pose), self.assertRaises(ValueError):
                PidPoseFollower().calculate_differential(*pose, Waypoint(1, 0))

    def test_invalid_timing_does_not_advance_wait_or_execute_action(self):
        for dt in (0, -0.02, float("nan"), float("inf")):
            for kind in ("WAIT", "ACTION"):
                calls = []
                routine = AutonomousRoutine(steps=[dict(kind=kind, duration_seconds=1, action_key="go")],
                                            action_handler=lambda *args: calls.append(args))
                with self.subTest(dt=dt, kind=kind), self.assertRaises(ValueError):
                    routine.update(0, 0, 0, dt)
                self.assertEqual(0, routine.current_idx)
                self.assertEqual(0, routine.step_elapsed_seconds)
                self.assertEqual([], calls)

    def test_invalid_wait_is_rejected_before_execution(self):
        for duration in (-1, float("nan"), float("inf")):
            with self.subTest(duration=duration), self.assertRaises(ValueError):
                AutonomousRoutine(steps=[dict(kind="WAIT", duration_seconds=duration)])

    def test_empty_explicit_steps_do_not_fall_back_to_waypoints(self):
        routine = AutonomousRoutine(waypoints=[Waypoint(1, 0)], steps=[])
        self.assertEqual((0, 0, True), routine.update(0, 0, 0))

    def test_invalid_steps_and_failed_actions_do_not_advance(self):
        for step in (dict(kind="UNKNOWN"), dict(kind="DRIVE_TO", waypoint=None),
                     dict(kind="ACTION", action_key="")):
            with self.subTest(step=step), self.assertRaises(ValueError):
                AutonomousRoutine(steps=[step])
        routine = AutonomousRoutine(steps=[dict(kind="ACTION", action_key="go")])
        with self.assertRaises(ValueError): routine.update(0, 0, 0)
        self.assertEqual(0, routine.current_idx)
        def failed_action(*args): raise OSError("actuator failure")
        routine.action_handler = failed_action
        with self.assertRaises(OSError): routine.update(0, 0, 0)
        self.assertEqual(0, routine.current_idx)

    def test_wait_action_and_reset_preserve_one_step_per_tick(self):
        calls = []
        routine = AutonomousRoutine(steps=[dict(kind="WAIT", duration_seconds=0.05),
            dict(kind="ACTION", action_key="go")], action_handler=lambda *args: calls.append(args))
        self.assertFalse(routine.update(0, 0, 0, 0.02)[2])
        self.assertFalse(routine.update(0, 0, 0, 0.03)[2])
        self.assertEqual([], calls)
        self.assertTrue(routine.update(0, 0, 0)[2])
        self.assertTrue(routine.update(0, 0, 0)[2])
        self.assertEqual(1, len(calls))
        routine.reset()
        self.assertFalse(routine.is_finished)
        self.assertEqual(0, routine.current_idx)
        self.assertEqual(0, routine.step_elapsed_seconds)

    def test_closed_loop_paths_converge_with_position_and_heading_bounds(self):
        for target in (Waypoint(1, 0.5, math.pi / 2, speed=0.2),
                       Waypoint(-1, -0.5, -math.pi / 2, speed=0.3),
                       Waypoint(0, 1, math.pi, speed=0.4)):
            with self.subTest(target=(target.x, target.y, target.heading)):
                routine = AutonomousRoutine(waypoints=[target])
                x = y = heading = 0.0
                dt = 0.02
                for _ in range(3000):
                    vx, omega, done = routine.update(x, y, heading, dt)
                    self.assertLessEqual(abs(vx), target.speed)
                    self.assertLessEqual(abs(omega), routine.follower.max_omega)
                    if done:
                        break
                    # Independent exact integration of a constant chassis twist.
                    next_heading = heading + omega * dt
                    if abs(omega) > 1e-10:
                        x += vx / omega * (math.sin(next_heading) - math.sin(heading))
                        y += vx / omega * (math.cos(heading) - math.cos(next_heading))
                    else:
                        x += vx * dt * math.cos(heading)
                        y += vx * dt * math.sin(heading)
                    heading = next_heading
                self.assertTrue(done)
                self.assertLessEqual(math.hypot(target.x - x, target.y - y), target.tolerance)
                error = (target.heading - heading + math.pi) % (2 * math.pi) - math.pi
                self.assertLessEqual(abs(error), target.heading_tolerance)


if __name__ == "__main__":
    unittest.main()
