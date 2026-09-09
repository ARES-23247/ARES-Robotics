"""
ARES Micro - OpMode & Autonomous Path Following for MicroPython
Provides waypoint navigation and autonomous routine execution on Pico W.
"""

import math
from .kinematics import wrap_angle


def _finite(value, name):
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(name + " must be a finite number")
    return float(value)


class Waypoint:
    def __init__(self, x, y, heading_rad=0.0, speed=0.5, tolerance=0.04,
                 heading_tolerance_rad=0.05):
        self.x = _finite(x, "waypoint x")
        self.y = _finite(y, "waypoint y")
        self.heading = _finite(heading_rad, "waypoint heading")
        self.speed = _finite(speed, "waypoint speed")
        self.tolerance = _finite(tolerance, "waypoint position tolerance")
        self.heading_tolerance = _finite(heading_tolerance_rad, "waypoint heading tolerance")
        if self.speed <= 0 or self.tolerance <= 0 or not 0 < self.heading_tolerance <= math.pi:
            raise ValueError("Waypoint speed/tolerances must be positive; heading tolerance cannot exceed pi")


class PidPoseFollower:
    """
    Proportional pose follower using differential-style forward/reverse and turning.
    Mecanum robots can use this path without lateral motion. Outputs are (vx, omega, reached).
    """
    def __init__(self, kp_linear=2.5, kp_angular=3.0, max_speed=0.80, max_omega=4.0):
        self.kp_linear = _finite(kp_linear, "linear gain")
        self.kp_angular = _finite(kp_angular, "angular gain")
        self.max_speed = _finite(max_speed, "maximum speed")
        self.max_omega = _finite(max_omega, "maximum angular speed")
        if self.kp_linear < 0 or self.kp_angular < 0 or self.max_speed <= 0 or self.max_omega <= 0:
            raise ValueError("Follower gains must be nonnegative and speed limits positive")

    def calculate_differential(self, current_x, current_y, current_heading, target_waypoint):
        """
        Calculates (vx, omega) for 2-wheel differential drive.
        """
        current_x = _finite(current_x, "current x")
        current_y = _finite(current_y, "current y")
        current_heading = _finite(current_heading, "current heading")
        dx = target_waypoint.x - current_x
        dy = target_waypoint.y - current_y
        dist = math.sqrt(dx * dx + dy * dy)
        if not math.isfinite(dist):
            raise ValueError("Waypoint displacement must be finite")

        if dist <= target_waypoint.tolerance:
            # Reached target position, align heading
            h_err = wrap_angle(target_waypoint.heading - current_heading)
            if abs(h_err) <= target_waypoint.heading_tolerance:
                return (0.0, 0.0, True)
            omega = max(-self.max_omega, min(self.max_omega, self.kp_angular * h_err))
            return (0.0, omega, False)

        # Drive angle in field frame
        target_angle = math.atan2(dy, dx)
        angle_err = wrap_angle(target_angle - current_heading)

        # If heading error is large, prioritize turning
        if abs(angle_err) > (math.pi / 2.0):
            # Reverse direction
            drive_speed = -min(self.max_speed, target_waypoint.speed, self.kp_linear * dist)
            angle_err = wrap_angle(target_angle - current_heading + math.pi)
        else:
            drive_speed = min(self.max_speed, target_waypoint.speed, self.kp_linear * dist)

        # Reduce translation while turning instead of driving across the target's
        # bearing at full speed. After reversal angle_err is within +/- pi/2.
        drive_speed *= max(0.0, math.cos(angle_err))
        omega = max(-self.max_omega, min(self.max_omega, self.kp_angular * angle_err))
        return (drive_speed, omega, False)


class AutonomousRoutine:
    """Deterministic DRIVE_TO, WAIT, and ACTION sequence used by generated XRP code."""

    def __init__(self, name="Autonomous", waypoints=None, steps=None, action_handler=None):
        self.name = name
        self.waypoints = waypoints or []
        self.steps = list(steps) if steps is not None else [
            {"kind": "DRIVE_TO", "waypoint": waypoint}
            for waypoint in self.waypoints
        ]
        self.action_handler = action_handler
        self.current_idx = 0
        self.step_elapsed_seconds = 0.0
        self.follower = PidPoseFollower()
        self.is_finished = False
        for step in self.steps:
            if step["kind"] == "WAIT":
                duration = _finite(step["duration_seconds"], "wait duration")
                if duration < 0:
                    raise ValueError("Wait duration cannot be negative")
            elif step["kind"] == "DRIVE_TO":
                if not isinstance(step.get("waypoint"), Waypoint):
                    raise ValueError("DRIVE_TO requires a waypoint")
            elif step["kind"] == "ACTION":
                if not isinstance(step.get("action_key"), str) or not step["action_key"]:
                    raise ValueError("ACTION requires an action key")
            else:
                raise ValueError("Unsupported generated XRP routine step: " + str(step["kind"]))

    def update(self, current_x, current_y, current_heading, dt=0.02):
        """
        Advances at most one step and returns (vx, omega, finished).
        dt is a finite positive control period in seconds.
        """
        dt = _finite(dt, "routine period")
        if dt <= 0:
            raise ValueError("Routine period must be positive")
        if self.current_idx >= len(self.steps):
            self.is_finished = True
            return (0.0, 0.0, True)

        step = self.steps[self.current_idx]
        kind = step["kind"]
        if kind == "WAIT":
            self.step_elapsed_seconds += dt
            if self.step_elapsed_seconds >= float(step["duration_seconds"]):
                self._advance()
            return (0.0, 0.0, self.is_finished)
        if kind == "ACTION":
            if self.action_handler is None:
                raise ValueError("XRP autonomous ACTION requires a registered action handler")
            self.action_handler(step["action_key"], step.get("arguments", {}))
            self._advance()
            return (0.0, 0.0, self.is_finished)
        if kind != "DRIVE_TO":
            raise ValueError("Unsupported generated XRP routine step: " + str(kind))

        target = step["waypoint"]
        vx, omega, reached = self.follower.calculate_differential(current_x, current_y, current_heading, target)

        if reached:
            self._advance()
            if self.is_finished:
                return (0.0, 0.0, True)

        return (vx, omega, False)

    def reset(self):
        self.current_idx = 0
        self.step_elapsed_seconds = 0.0
        self.is_finished = False

    def _advance(self):
        self.current_idx += 1
        self.step_elapsed_seconds = 0.0
        self.is_finished = self.current_idx >= len(self.steps)
