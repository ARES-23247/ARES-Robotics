"""SparkFun Qwiic OTOS driver for the ARES MicroPython runtime.

The register map and SI conversion factors mirror SparkFun's published OTOS
driver. Communication errors deliberately propagate so the robot lifecycle can
neutralize outputs and latch a fault instead of reusing stale localization.
"""

import math
import struct
import time


class SparkFunOTOS:
    DEFAULT_I2C_ADDR = 0x17
    PRODUCT_ID = 0x5F

    REG_PRODUCT_ID = 0x00
    REG_HW_VERSION = 0x01
    REG_FW_VERSION = 0x02
    REG_LINEAR_SCALAR = 0x04
    REG_ANGULAR_SCALAR = 0x05
    REG_IMU_CALIBRATION = 0x06
    REG_RESET = 0x07
    REG_OFFSET_X = 0x10
    REG_STATUS = 0x1F
    REG_POS_X = 0x20
    REG_VEL_X = 0x26

    INT16_TO_METERS = 10.0 / 32768.0
    INT16_TO_METERS_PER_SECOND = 5.0 / 32768.0
    INT16_TO_RADIANS = math.pi / 32768.0
    INT16_TO_RADIANS_PER_SECOND = math.radians(2000.0) / 32768.0
    METERS_TO_INT16 = 1.0 / INT16_TO_METERS
    RADIANS_TO_INT16 = 1.0 / INT16_TO_RADIANS
    MIN_SCALAR = 0.872
    MAX_SCALAR = 1.127

    def __init__(self, i2c=None, address=DEFAULT_I2C_ADDR, is_heading_ccw_positive=True):
        self.i2c = i2c
        self.address = int(address)
        self.is_ccw = bool(is_heading_ccw_positive)
        self.x = 0.0
        self.y = 0.0
        self.heading = 0.0
        self.vx = 0.0
        self.vy = 0.0
        self.omega = 0.0

    def begin(self):
        """Return true only when the exact OTOS product ID answers."""
        if self.i2c is None:
            return False
        try:
            return self._read_byte(self.REG_PRODUCT_ID) == self.PRODUCT_ID
        except Exception:
            return False

    def reset_tracking(self):
        """Reset the onboard tracking accumulator to the origin."""
        self._write_byte(self.REG_RESET, 0x01)
        self.x = self.y = self.heading = 0.0
        self.vx = self.vy = self.omega = 0.0

    def calibrate_imu(self, num_samples=255, wait_until_done=True):
        """Calibrate the onboard IMU; return false if completion times out."""
        samples = int(num_samples)
        if samples < 1 or samples > 255:
            raise ValueError("OTOS IMU calibration samples must be in 1..255")
        self._write_byte(self.REG_IMU_CALIBRATION, samples)
        time.sleep(0.003)
        if not wait_until_done:
            return True
        for _ in range(samples):
            if self._read_byte(self.REG_IMU_CALIBRATION) == 0:
                return True
            time.sleep(0.003)
        return False

    def set_linear_scalar(self, scalar):
        self._write_scalar(self.REG_LINEAR_SCALAR, scalar)

    def set_angular_scalar(self, scalar):
        self._write_scalar(self.REG_ANGULAR_SCALAR, scalar)

    def set_offsets(self, offset_x_m, offset_y_m, offset_h_rad):
        """Store the sensor-to-robot-center mounting transform on the OTOS."""
        self._write_pose(
            self.REG_OFFSET_X,
            float(offset_x_m),
            float(offset_y_m),
            self._device_heading(float(offset_h_rad)),
        )

    def set_pose(self, x_m=0.0, y_m=0.0, heading_rad=0.0):
        """Set the tracked robot pose without losing it on the next sensor poll."""
        x = float(x_m)
        y = float(y_m)
        heading = float(heading_rad)
        self._write_pose(self.REG_POS_X, x, y, self._device_heading(heading))
        self.x, self.y, self.heading = x, y, heading
        self.vx = self.vy = self.omega = 0.0

    def update(self):
        """Read a coherent position and velocity burst in SI units."""
        data = self._read_mem(self.REG_POS_X, 12)
        if len(data) != 12:
            raise OSError("OTOS returned an incomplete pose/velocity frame")
        raw_x, raw_y, raw_h, raw_vx, raw_vy, raw_vh = struct.unpack("<hhhhhh", data)
        heading_sign = 1.0 if self.is_ccw else -1.0
        self.x = raw_x * self.INT16_TO_METERS
        self.y = raw_y * self.INT16_TO_METERS
        self.heading = raw_h * self.INT16_TO_RADIANS * heading_sign
        self.vx = raw_vx * self.INT16_TO_METERS_PER_SECOND
        self.vy = raw_vy * self.INT16_TO_METERS_PER_SECOND
        self.omega = raw_vh * self.INT16_TO_RADIANS_PER_SECOND * heading_sign
        return self.x, self.y, self.heading, self.vx, self.vy, self.omega

    def _device_heading(self, heading_rad):
        return heading_rad if self.is_ccw else -heading_rad

    def _write_scalar(self, register, scalar):
        value = float(scalar)
        if not math.isfinite(value) or value < self.MIN_SCALAR or value > self.MAX_SCALAR:
            raise ValueError("OTOS scalar must be in 0.872..1.127")
        self._write_byte(register, int(round((value - 1.0) * 1000.0)) & 0xFF)

    def _write_pose(self, register, x, y, heading):
        values = (
            self._scaled_int16(x, self.METERS_TO_INT16),
            self._scaled_int16(y, self.METERS_TO_INT16),
            self._scaled_int16(heading, self.RADIANS_TO_INT16),
        )
        self._write_mem(register, struct.pack("<hhh", *values))

    @staticmethod
    def _scaled_int16(value, scale):
        raw = int(round(float(value) * scale))
        if raw < -32768 or raw > 32767:
            raise ValueError("OTOS pose value is outside the signed 16-bit register range")
        return raw

    def _read_byte(self, register):
        data = self._read_mem(register, 1)
        if len(data) != 1:
            raise OSError("OTOS returned an incomplete register value")
        return data[0]

    def _write_byte(self, register, value):
        self._write_mem(register, bytes((int(value) & 0xFF,)))

    def _read_mem(self, register, length):
        if self.i2c is None:
            raise OSError("OTOS I2C bus is not configured")
        return self.i2c.readfrom_mem(self.address, register, length)

    def _write_mem(self, register, data):
        if self.i2c is None:
            raise OSError("OTOS I2C bus is not configured")
        self.i2c.writeto_mem(self.address, register, data)
