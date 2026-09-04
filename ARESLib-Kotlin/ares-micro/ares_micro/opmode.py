"""
ARES Micro - OpMode & Autonomous Path Following for MicroPython
Provides waypoint navigation and autonomous routine execution on Pico W.
"""

import math
from .kinematics import wrap_angle

class Waypoint:
    def __init__(self, x, y, heading_rad=0.0, speed=0.5, tolerance=0.04):
        self.x = float(x)
        self.y = float(y)
        self.heading = float(heading_rad)
        self.speed = float(speed)
        self.tolerance = float(tolerance)


class PidPoseFollower:
    """
    Closed-loop pose follower for differential and mecanum drivebases.
    Computes chassis velocities (vx, vy, omega) to drive toward a target waypoint.
    """
    def __init__(self, kp_linear=2.5, kp_angular=3.0, max_speed=0.80, max_omega=4.0):
        self.kp_linear = kp_linear
        self.kp_angular = kp_angular
        self.max_speed = max_speed
        self.max_omega = max_omega

    def calculate_differential(self, current_x, current_y, current_heading, target_waypoint):
        """
        Calculates (vx, omega) for 2-wheel differential drive.
        """
        dx = target_waypoint.x - current_x
        dy = target_waypoint.y - current_y
        dist = math.sqrt(dx * dx + dy * dy)

        if dist < target_waypoint.tolerance:
            # Reached target position, align heading
            h_err = wrap_angle(target_waypoint.heading - current_heading)
            omega = max(-self.max_omega, min(self.max_omega, self.kp_angular * h_err))
            return (0.0, omega, True)

        # Drive angle in field frame
        target_angle = math.atan2(dy, dx)
        angle_err = wrap_angle(target_angle - current_heading)

        # If heading error is large, prioritize turning
        if abs(angle_err) > (math.pi / 2.0):
            # Reverse direction
            drive_speed = -min(self.max_speed, self.kp_linear * dist)
            angle_err = wrap_angle(target_angle - current_heading + math.pi)
        else:
            drive_speed = min(self.max_speed, self.kp_linear * dist)

        omega = max(-self.max_omega, min(self.max_omega, self.kp_angular * angle_err))
        return (drive_speed, omega, False)


class AutonomousRoutine:
    """
    Sequences a list of waypoints and actions into a complete autonomous run.
    """
    def __init__(self, name="Autonomous", waypoints=None):
        self.name = name
        self.waypoints = waypoints or []
        self.current_idx = 0
        self.follower = PidPoseFollower()
        self.is_finished = False

    def update(self, current_x, current_y, current_heading):
        """
        Advances routine by computing next (vx, vy, omega).
        Returns (vx, omega, finished).
        """
        if self.current_idx >= len(self.waypoints):
            self.is_finished = True
            return (0.0, 0.0, True)

        target = self.waypoints[self.current_idx]
        vx, omega, reached = self.follower.calculate_differential(current_x, current_y, current_heading, target)

        if reached:
            self.current_idx += 1
            if self.current_idx >= len(self.waypoints):
                self.is_finished = True
                return (0.0, 0.0, True)

        return (vx, omega, False)

    def reset(self):
        self.current_idx = 0
        self.is_finished = False
