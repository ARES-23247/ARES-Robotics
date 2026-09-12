"""
ARES Micro - Kinematics Module for MicroPython (Differential & Mecanum)
Zero-dependency, pure-Python forward and inverse kinematics calculations.
"""

import math

def wrap_angle(angle_rad):
    """Normalizes a finite angle to [-pi, pi], retaining signed endpoints."""
    if not math.isfinite(angle_rad):
        raise ValueError("Angle must be finite")
    if -math.pi <= angle_rad <= math.pi:
        return float(angle_rad)
    wrapped = math.fmod(angle_rad, 2.0 * math.pi)
    if wrapped > math.pi:
        wrapped -= 2.0 * math.pi
    elif wrapped < -math.pi:
        wrapped += 2.0 * math.pi
    return wrapped


def arc_chord_scale(delta_heading):
    """SE(2) arc-to-chord factor for a midpoint rotation, stable at zero."""
    if not math.isfinite(delta_heading):
        raise ValueError("Heading delta must be finite")
    half = delta_heading * 0.5
    return 1.0 - half * half / 6.0 if abs(half) < 1e-6 else math.sin(half) / half


def _mean(a, b):
    total = a + b
    # Sum first to preserve subnormal inputs; scale first only on overflow.
    return total * 0.5 if math.isfinite(total) else a * 0.5 + b * 0.5


def _quarter_sum(a, b, c, d):
    total = a + b + c + d
    return total * 0.25 if math.isfinite(total) else a * 0.25 + b * 0.25 + c * 0.25 + d * 0.25

class DifferentialDriveKinematics:
    """
    Forward and Inverse Kinematics for 2-wheel differential drive robots.
    Standard math CCW-positive:
    - Forward = +X
    - Left = +Y
    - Counter-clockwise rotation = +Omega
    """
    def __init__(self, track_width_meters=0.155):
        if not math.isfinite(track_width_meters) or track_width_meters <= 0.0:
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
        rotation = omega * self._half_track if self._half_track * 2.0 == self.track_width else (omega * self.track_width) * 0.5
        left = vx - rotation
        right = vx + rotation
        return (left, right)

    def to_chassis_speeds(self, left_mps, right_mps):
        """
        Calculates chassis speeds (vx, omega) from measured wheel speeds.
        :param left_mps: Left wheel surface velocity (m/s)
        :param right_mps: Right wheel surface velocity (m/s)
        :return: Tuple of (vx_mps, omega_rad_per_sec)
        """
        vx = _mean(right_mps, left_mps)
        difference = right_mps - left_mps
        omega = difference / self.track_width if math.isfinite(difference) else right_mps / self.track_width - left_mps / self.track_width
        return (vx, omega)


class MecanumKinematics:
    """
    Forward and Inverse Kinematics for 4-wheel mecanum drivetrains.
    Converts chassis velocities (vx, vy, omega) to/from 4 wheel speeds (FL, FR, BL, BR).
    """
    def __init__(self, track_width_meters=0.155, wheel_base_meters=0.140):
        if (not math.isfinite(track_width_meters) or not math.isfinite(wheel_base_meters)
                or track_width_meters <= 0.0 or wheel_base_meters <= 0.0):
            raise ValueError("Dimensions must be positive")
        self.track_width = float(track_width_meters)
        self.wheel_base = float(wheel_base_meters)
        self.k = _mean(self.track_width, self.wheel_base)

    def to_wheel_speeds(self, vx, vy, omega):
        """
        Calculates (FL, FR, BL, BR) wheel surface speeds (m/s).
        """
        rotation = omega * self.k
        minus_lateral = vx - vy
        plus_lateral = vx + vy
        fl = minus_lateral - rotation
        fr = plus_lateral + rotation
        bl = plus_lateral - rotation
        br = minus_lateral + rotation
        return (fl, fr, bl, br)

    def to_chassis_speeds(self, fl, fr, bl, br):
        """
        Calculates (vx, vy, omega) from 4 wheel speeds (m/s).
        """
        vx = _quarter_sum(fl, fr, bl, br)
        vy = _quarter_sum(-fl, fr, bl, -br)
        turn_sum = -fl + fr - bl + br
        if math.isfinite(turn_sum):
            quarter_turn = turn_sum * 0.25
            # Tiny wheel deltas can still imply a representable angle for tiny geometry.
            omega = ((turn_sum / self.k) * 0.25 if quarter_turn * 4.0 != turn_sum
                     else quarter_turn / self.k)
        else:
            omega = _quarter_sum(-fl, fr, -bl, br) / self.k
        return (vx, vy, omega)
