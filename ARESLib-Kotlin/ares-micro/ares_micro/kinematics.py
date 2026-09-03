"""
ARES Micro - Kinematics Module for MicroPython (Differential & Mecanum)
Zero-dependency, pure-Python forward and inverse kinematics calculations.
"""

import math

def wrap_angle(angle_rad):
    """Normalizes an angle to [-pi, pi]."""
    while angle_rad > math.pi:
        angle_rad -= 2.0 * math.pi
    while angle_rad < -math.pi:
        angle_rad += 2.0 * math.pi
    return angle_rad

class DifferentialDriveKinematics:
    """
    Forward and Inverse Kinematics for 2-wheel differential drive robots.
    Standard math CCW-positive:
    - Forward = +X
    - Left = +Y
    - Counter-clockwise rotation = +Omega
    """
    def __init__(self, track_width_meters=0.155):
        if track_width_meters <= 0.0:
            raise ValueError("track_width_meters must be positive")
        self.track_width = float(track_width_meters)
        self._half_track = self.track_width / 2.0

    def to_wheel_speeds(self, vx, omega):
        """
        Calculates left and right wheel surface speeds (m/s).
        :param vx: Forward chassis velocity (m/s)
        :param omega: Angular velocity (rad/s, CCW positive)
        :return: Tuple of (left_mps, right_mps)
        """
        left = vx - (omega * self._half_track)
        right = vx + (omega * self._half_track)
        return (left, right)

    def to_chassis_speeds(self, left_mps, right_mps):
        """
        Calculates chassis speeds (vx, omega) from measured wheel speeds.
        :param left_mps: Left wheel surface velocity (m/s)
        :param right_mps: Right wheel surface velocity (m/s)
        :return: Tuple of (vx_mps, omega_rad_per_sec)
        """
        vx = (right_mps + left_mps) / 2.0
        omega = (right_mps - left_mps) / self.track_width
        return (vx, omega)


class MecanumKinematics:
    """
    Forward and Inverse Kinematics for 4-wheel mecanum drivetrains.
    Converts chassis velocities (vx, vy, omega) to/from 4 wheel speeds (FL, FR, BL, BR).
    """
    def __init__(self, track_width_meters=0.155, wheel_base_meters=0.140):
        if track_width_meters <= 0.0 or wheel_base_meters <= 0.0:
            raise ValueError("Dimensions must be positive")
        self.track_width = float(track_width_meters)
        self.wheel_base = float(wheel_base_meters)
        self.k = (self.track_width / 2.0) + (self.wheel_base / 2.0)
        self._inv_4k = 1.0 / (4.0 * self.k)

    def to_wheel_speeds(self, vx, vy, omega):
        """
        Calculates (FL, FR, BL, BR) wheel surface speeds (m/s).
        """
        fl = vx - vy - (omega * self.k)
        fr = vx + vy + (omega * self.k)
        bl = vx + vy - (omega * self.k)
        br = vx - vy + (omega * self.k)
        return (fl, fr, bl, br)

    def to_chassis_speeds(self, fl, fr, bl, br):
        """
        Calculates (vx, vy, omega) from 4 wheel speeds (m/s).
        """
        vx = (fl + fr + bl + br) * 0.25
        vy = (-fl + fr + bl - br) * 0.25
        omega = (-fl + fr - bl + br) * self._inv_4k
        return (vx, vy, omega)
