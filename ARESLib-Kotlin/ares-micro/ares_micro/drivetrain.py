"""
ARES Micro - Drivetrain Subsystem for XRP MicroPython
Controls motors for 2-wheel Differential and 4-wheel Mecanum drivetrains.
Integrates with wheel encoders and SparkFun OTOS sensor.
"""

import math
from .kinematics import DifferentialDriveKinematics, MecanumKinematics, wrap_angle

class DifferentialDrivetrain:
    """
    Standard XRP 2-wheel Differential Drivetrain.
    Controls Left & Right motors and tracks dead-reckoned odometry.
    """
    def __init__(self, left_motor=None, right_motor=None, track_width=0.155, wheel_radius=0.030, otos=None):
        self.left_motor = left_motor
        self.right_motor = right_motor
        self.track_width = track_width
        self.wheel_radius = wheel_radius
        self.otos = otos
        self.kinematics = DifferentialDriveKinematics(track_width)

        # Odometry pose (m, m, rad)
        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0
        self.vx = 0.0
        self.vy = 0.0
        self.omega = 0.0

        self.last_left_pos = 0.0
        self.last_right_pos = 0.0

    def set_powers(self, left_power, right_power):
        """Sets motor powers in [-1.0, 1.0]."""
        lp = max(-1.0, min(1.0, float(left_power)))
        rp = max(-1.0, min(1.0, float(right_power)))
        if self.left_motor:
            try:
                self.left_motor.set_effort(lp)
            except Exception:
                pass
        if self.right_motor:
            try:
                self.right_motor.set_effort(rp)
            except Exception:
                pass

    def drive(self, vx_mps, omega_rad_per_sec, max_speed=0.85):
        """Drives robot with linear velocity (m/s) and angular rate (rad/s)."""
        left_mps, right_mps = self.kinematics.to_wheel_speeds(vx_mps, omega_rad_per_sec)
        lp = left_mps / max_speed if max_speed > 0 else 0.0
        rp = right_mps / max_speed if max_speed > 0 else 0.0
        self.set_powers(lp, rp)

    def stop(self):
        self.set_powers(0.0, 0.0)

    def update_odometry(self, dt=0.02):
        """Updates pose using OTOS (if attached) or wheel encoder integration."""
        if self.otos:
            x, y, h, vx, vy, omega = self.otos.update()
            self.x = x
            self.y = y
            self.heading = h
            self.vx = vx
            self.vy = vy
            self.omega = omega
            return

        # Encoder-based odometry integration fallback
        cur_left = self._get_motor_distance(self.left_motor)
        cur_right = self._get_motor_distance(self.right_motor)
        d_left = cur_left - self.last_left_pos
        d_right = cur_right - self.last_right_pos
        self.last_left_pos = cur_left
        self.last_right_pos = cur_right

        d_center = (d_left + d_right) / 2.0
        d_theta = (d_right - d_left) / self.track_width

        # Runge-Kutta / midpoint heading integration
        mid_heading = self.heading + (d_theta / 2.0)
        self.x += d_center * math.cos(mid_heading)
        self.y += d_center * math.sin(mid_heading)
        self.heading = wrap_angle(self.heading + d_theta)

        if dt > 0:
            self.vx = d_center / dt
            self.vy = 0.0
            self.omega = d_theta / dt

    def reset_pose(self, x=0.0, y=0.0, heading_rad=0.0):
        self.x = float(x)
        self.y = float(y)
        self.heading = float(heading_rad)
        if self.otos:
            self.otos.reset_tracking()

    def _get_motor_distance(self, motor):
        if motor and hasattr(motor, "get_position"):
            try:
                # get_position returns ticks or rotations
                return motor.get_position() * (2.0 * math.pi * self.wheel_radius)
            except Exception:
                pass
        return 0.0


class MecanumDrivetrain:
    """
    4-Wheel Mecanum Drivetrain for XRP.
    """
    def __init__(self, fl_motor=None, fr_motor=None, bl_motor=None, br_motor=None,
                 track_width=0.155, wheel_base=0.140, wheel_radius=0.030, otos=None):
        self.fl = fl_motor
        self.fr = fr_motor
        self.bl = bl_motor
        self.br = br_motor
        self.otos = otos
        self.kinematics = MecanumKinematics(track_width, wheel_base)

        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0
        self.vx = 0.0
        self.vy = 0.0
        self.omega = 0.0

    def set_powers(self, fl_p, fr_p, bl_p, br_p):
        for m, p in [(self.fl, fl_p), (self.fr, fr_p), (self.bl, bl_p), (self.br, br_p)]:
            if m:
                try:
                    m.set_effort(max(-1.0, min(1.0, float(p))))
                except Exception:
                    pass

    def drive(self, vx_mps, vy_mps, omega_rad_per_sec, max_speed=0.85):
        fl, fr, bl, br = self.kinematics.to_wheel_speeds(vx_mps, vy_mps, omega_rad_per_sec)
        fl_p = fl / max_speed if max_speed > 0 else 0.0
        fr_p = fr / max_speed if max_speed > 0 else 0.0
        bl_p = bl / max_speed if max_speed > 0 else 0.0
        br_p = br / max_speed if max_speed > 0 else 0.0
        self.set_powers(fl_p, fr_p, bl_p, br_p)

    def stop(self):
        self.set_powers(0.0, 0.0, 0.0, 0.0)

    def update_odometry(self, dt=0.02):
        if self.otos:
            x, y, h, vx, vy, omega = self.otos.update()
            self.x = x
            self.y = y
            self.heading = h
            self.vx = vx
            self.vy = vy
            self.omega = omega

    def reset_pose(self, x=0.0, y=0.0, heading_rad=0.0):
        self.x = float(x)
        self.y = float(y)
        self.heading = float(heading_rad)
        if self.otos:
            self.otos.reset_tracking()
