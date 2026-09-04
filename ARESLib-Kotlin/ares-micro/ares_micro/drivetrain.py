"""
ARES Micro - Drivetrain Subsystem for XRP MicroPython
Controls motors for two-wheel differential and four-wheel expansion mecanum XRP drivetrains.
Integrates with wheel encoders and SparkFun OTOS sensor.
"""

import math
from .kinematics import DifferentialDriveKinematics, MecanumKinematics, wrap_angle

def _stop_motors(motors):
    failure = None
    for motor in motors:
        if motor is not None:
            try:
                motor.set_effort(0.0)
            except Exception as error:
                failure = error
    if failure is not None:
        raise failure

class DifferentialDrivetrain:
    """
    Standard XRP 2-wheel Differential Drivetrain.
    Controls Left & Right motors and tracks dead-reckoned odometry.
    """
    def __init__(self, left_motor=None, right_motor=None, drivetrain_io=None,
                 track_width=0.155, wheel_radius=0.030, max_speed=0.85, otos=None,
                 heading_supplier=None):
        self.left_motor = left_motor if left_motor is not None else getattr(drivetrain_io, "left_motor", None)
        self.right_motor = right_motor if right_motor is not None else getattr(drivetrain_io, "right_motor", None)
        self.drivetrain_io = drivetrain_io
        self.track_width = track_width
        self.wheel_radius = wheel_radius
        self.max_speed = float(max_speed)
        self.otos = otos
        self.heading_supplier = heading_supplier
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
        if self.drivetrain_io:
            self.drivetrain_io.set_effort(lp, rp)
            return
        if self.left_motor:
            self.left_motor.set_effort(lp)
        if self.right_motor:
            self.right_motor.set_effort(rp)

    def drive(self, vx_mps, omega_rad_per_sec):
        """Drives robot with linear velocity (m/s) and angular rate (rad/s)."""
        left_mps, right_mps = self.kinematics.to_wheel_speeds(vx_mps, omega_rad_per_sec)
        lp = left_mps / self.max_speed if self.max_speed > 0 else 0.0
        rp = right_mps / self.max_speed if self.max_speed > 0 else 0.0
        self.set_powers(lp, rp)

    def stop(self):
        if self.left_motor is not None and self.right_motor is not None:
            # XRPLib exposes these motors; its combined set_effort stops at the
            # first exception. Stop each independently during emergency cleanup.
            _stop_motors((self.left_motor, self.right_motor))
        elif self.drivetrain_io:
            self.drivetrain_io.set_effort(0.0, 0.0)

    def has_output(self):
        return self.drivetrain_io is not None or (
            self.left_motor is not None and self.right_motor is not None
        )

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
        if self.drivetrain_io and hasattr(self.drivetrain_io, "get_left_encoder_position"):
            # XRPLib exposes drivetrain distances in centimeters.
            cur_left = float(self.drivetrain_io.get_left_encoder_position()) / 100.0
            cur_right = float(self.drivetrain_io.get_right_encoder_position()) / 100.0
        else:
            cur_left = self._get_motor_distance(self.left_motor)
            cur_right = self._get_motor_distance(self.right_motor)
        d_left = cur_left - self.last_left_pos
        d_right = cur_right - self.last_right_pos
        self.last_left_pos = cur_left
        self.last_right_pos = cur_right

        d_center = (d_left + d_right) / 2.0
        wheel_d_theta = (d_right - d_left) / self.track_width
        next_heading = wrap_angle(float(self.heading_supplier())) if self.heading_supplier else wrap_angle(self.heading + wheel_d_theta)
        d_theta = wrap_angle(next_heading - self.heading)

        # Runge-Kutta / midpoint heading integration
        mid_heading = self.heading + (d_theta / 2.0)
        self.x += d_center * math.cos(mid_heading)
        self.y += d_center * math.sin(mid_heading)
        self.heading = next_heading

        if dt > 0:
            self.vx = d_center / dt
            self.vy = 0.0
            self.omega = d_theta / dt

    def reset_pose(self, x=0.0, y=0.0, heading_rad=0.0):
        self.x = float(x)
        self.y = float(y)
        self.heading = float(heading_rad)
        if self.otos:
            self.otos.set_pose(self.x, self.y, self.heading)

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
                 track_width=0.155, wheel_base=0.140, wheel_radius=0.030,
                 max_speed=0.85, otos=None, heading_supplier=None):
        self.fl = fl_motor
        self.fr = fr_motor
        self.bl = bl_motor
        self.br = br_motor
        self.otos = otos
        self.heading_supplier = heading_supplier
        self.wheel_radius = float(wheel_radius)
        self.max_speed = float(max_speed)
        self.kinematics = MecanumKinematics(track_width, wheel_base)

        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0
        self.vx = 0.0
        self.vy = 0.0
        self.omega = 0.0
        self.last_positions = [0.0, 0.0, 0.0, 0.0]

    def set_powers(self, fl_p, fr_p, bl_p, br_p):
        for m, p in [(self.fl, fl_p), (self.fr, fr_p), (self.bl, bl_p), (self.br, br_p)]:
            if m:
                m.set_effort(max(-1.0, min(1.0, float(p))))

    def drive(self, vx_mps, vy_mps, omega_rad_per_sec):
        fl, fr, bl, br = self.kinematics.to_wheel_speeds(vx_mps, vy_mps, omega_rad_per_sec)
        fl_p = fl / self.max_speed if self.max_speed > 0 else 0.0
        fr_p = fr / self.max_speed if self.max_speed > 0 else 0.0
        bl_p = bl / self.max_speed if self.max_speed > 0 else 0.0
        br_p = br / self.max_speed if self.max_speed > 0 else 0.0
        self.set_powers(fl_p, fr_p, bl_p, br_p)

    def stop(self):
        _stop_motors((self.fl, self.fr, self.bl, self.br))

    def has_output(self):
        return all(motor is not None for motor in (self.fl, self.fr, self.bl, self.br))

    def update_odometry(self, dt=0.02):
        if self.otos:
            x, y, h, vx, vy, omega = self.otos.update()
            self.x = x
            self.y = y
            self.heading = h
            self.vx = vx
            self.vy = vy
            self.omega = omega
            return
        positions = [self._get_motor_distance(motor) for motor in (self.fl, self.fr, self.bl, self.br)]
        deltas = [current - previous for current, previous in zip(positions, self.last_positions)]
        self.last_positions = positions
        dx_robot, dy_robot, wheel_d_heading = self.kinematics.to_chassis_speeds(*deltas)
        next_heading = wrap_angle(float(self.heading_supplier())) if self.heading_supplier else wrap_angle(self.heading + wheel_d_heading)
        d_heading = wrap_angle(next_heading - self.heading)
        mid_heading = self.heading + d_heading / 2.0
        self.x += dx_robot * math.cos(mid_heading) - dy_robot * math.sin(mid_heading)
        self.y += dx_robot * math.sin(mid_heading) + dy_robot * math.cos(mid_heading)
        self.heading = next_heading
        if dt > 0:
            self.vx = dx_robot / dt
            self.vy = dy_robot / dt
            self.omega = d_heading / dt

    def reset_pose(self, x=0.0, y=0.0, heading_rad=0.0):
        self.x = float(x)
        self.y = float(y)
        self.heading = float(heading_rad)
        if self.otos:
            self.otos.set_pose(self.x, self.y, self.heading)
        self.last_positions = [self._get_motor_distance(motor) for motor in (self.fl, self.fr, self.bl, self.br)]

    def _get_motor_distance(self, motor):
        if motor and hasattr(motor, "get_position"):
            return float(motor.get_position()) * (2.0 * math.pi * self.wheel_radius)
        return 0.0
