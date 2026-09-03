"""
ARES Micro - SparkFun Optical Tracking Odometry Sensor (OTOS) MicroPython Driver
Communicates over I2C to read 2D position (x, y, heading), linear velocity, and angular rate.
Compatible with standard MicroPython `machine.I2C` and mock I2C interfaces for desktop testing.
"""

import struct
import math

class SparkFunOTOS:
    DEFAULT_I2C_ADDR = 0x17

    # Registers
    REG_PRODUCT_ID = 0x00
    REG_HW_VERSION = 0x01
    REG_FW_VERSION = 0x02
    REG_RESET = 0x03
    REG_LINEAR_SCALAR = 0x04
    REG_ANGULAR_SCALAR = 0x05
    REG_OFFSET_X = 0x06
    REG_OFFSET_Y = 0x08
    REG_OFFSET_H = 0x0A
    REG_POS_X = 0x20
    REG_POS_Y = 0x22
    REG_POS_H = 0x24
    REG_VEL_X = 0x26
    REG_VEL_Y = 0x28
    REG_VEL_H = 0x2A
    REG_ACC_X = 0x2C
    REG_ACC_Y = 0x2E
    REG_ACC_H = 0x30

    # Conversion constants: raw 16-bit signed ticks to SI meters and radians
    # OTOS default raw tick resolution: 0.0001 meters (0.1 mm) for position, 0.001 rad for heading
    POS_SCALING_METERS = 0.0001
    VEL_SCALING_METERS_PER_SEC = 0.0001
    HEADING_SCALING_RADIANS = 0.001

    def __init__(self, i2c=None, address=DEFAULT_I2C_ADDR, is_heading_ccw_positive=True):
        self.i2c = i2c
        self.address = address
        self.is_ccw = is_heading_ccw_positive
        self.offset_x = 0.0
        self.offset_y = 0.0
        self.offset_heading = 0.0
        self.linear_scalar = 1.0
        self.angular_scalar = 1.0

        # Cached last readings
        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0
        self.vx = 0.0
        self.vy = 0.0
        self.omega = 0.0

    def begin(self):
        """Initializes sensor and verifies communication."""
        if self.i2c is None:
            return False
        try:
            prod_id = self._read_byte(self.REG_PRODUCT_ID)
            return prod_id == 0x5C or prod_id > 0
        except Exception:
            return False

    def reset_tracking(self):
        """Resets the tracking accumulator on the physical sensor."""
        self._write_byte(self.REG_RESET, 0x01)
        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0

    def calibrate_imu(self):
        """Commands the OTOS to run onboard IMU zero calibration."""
        self._write_byte(self.REG_RESET, 0x02)

    def set_linear_scalar(self, scalar):
        """Sets linear scalar calibration multiplier."""
        self.linear_scalar = float(scalar)

    def set_angular_scalar(self, scalar):
        """Sets angular scalar calibration multiplier."""
        self.angular_scalar = float(scalar)

    def set_offsets(self, offset_x_m, offset_y_m, offset_h_rad):
        """Sets sensor mounting offset relative to robot center."""
        self.offset_x = float(offset_x_m)
        self.offset_y = float(offset_y_m)
        self.offset_heading = float(offset_h_rad)

    def update(self):
        """
        Polls position and velocity from OTOS registers.
        Returns (x, y, heading_rad, vx, vy, omega).
        """
        if self.i2c is None:
            return (self.x, self.y, self.heading, self.vx, self.vy, self.omega)

        try:
            # Read 12 bytes starting at REG_POS_X:
            # posX(2), posY(2), posH(2), velX(2), velY(2), velH(2)
            data = self._read_mem(self.REG_POS_X, 12)
            raw_x, raw_y, raw_h, raw_vx, raw_vy, raw_vh = struct.unpack('<hhhhhh', data)

            mult = 1.0 if self.is_ccw else -1.0

            raw_pos_x = raw_x * self.POS_SCALING_METERS * self.linear_scalar
            raw_pos_y = raw_y * self.POS_SCALING_METERS * self.linear_scalar
            raw_pos_h = raw_h * self.HEADING_SCALING_RADIANS * self.angular_scalar * mult

            raw_vel_x = raw_vx * self.VEL_SCALING_METERS_PER_SEC * self.linear_scalar
            raw_vel_y = raw_vy * self.VEL_SCALING_METERS_PER_SEC * self.linear_scalar
            raw_vel_h = raw_vh * self.HEADING_SCALING_RADIANS * self.angular_scalar * mult

            # Apply robot center offset transformation
            cos_h = math.cos(raw_pos_h)
            sin_h = math.sin(raw_pos_h)

            self.x = raw_pos_x - (self.offset_x * cos_h - self.offset_y * sin_h)
            self.y = raw_pos_y - (self.offset_x * sin_h + self.offset_y * cos_h)
            self.heading = raw_pos_h
            self.vx = raw_vel_x
            self.vy = raw_vel_y
            self.omega = raw_vel_h

        except Exception:
            pass

        return (self.x, self.y, self.heading, self.vx, self.vy, self.omega)

    def _read_byte(self, reg):
        buf = self._read_mem(reg, 1)
        return buf[0] if len(buf) > 0 else 0

    def _write_byte(self, reg, val):
        if self.i2c is not None:
            try:
                self.i2c.writeto_mem(self.address, reg, bytes([val]))
            except Exception:
                pass

    def _read_mem(self, reg, nbytes):
        if self.i2c is not None:
            try:
                return self.i2c.readfrom_mem(self.address, reg, nbytes)
            except Exception:
                pass
        return bytes(nbytes)
