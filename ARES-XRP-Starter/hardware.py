"""Official XRPLib adapters used only on the physical XRP controller."""

import math


class _MotorAdapter:
    def __init__(self, motor):
        self.motor = motor

    def write(self, volts):
        self.motor.set_effort(max(-1.0, min(1.0, float(volts) / 12.0)))

    def read(self, source):
        if source == "MOTOR_POSITION_NATIVE":
            return self.motor.get_position()
        if source == "MOTOR_VELOCITY_NATIVE_PER_SECOND":
            return self.motor.get_speed() / 60.0
        raise ValueError("XRPLib does not expose motor current")


class _DirectionalDriveMotor:
    def __init__(self, motor, inverted=False):
        self.motor = motor
        self.sign = -1.0 if inverted else 1.0

    def set_effort(self, effort):
        self.motor.set_effort(self.sign * float(effort))

    def get_position(self):
        return self.sign * float(self.motor.get_position())


def create_xrp_mecanum_motors(declarations):
    from XRPLib.defaults import left_motor, motor_three, right_motor
    try:
        from XRPLib.defaults import motor_four
    except ImportError as error:
        raise RuntimeError("Four-motor XRP mecanum requires a controller with motor port 4") from error
    ports = {1: left_motor, 2: right_motor, 3: motor_three, 4: motor_four}
    by_port = {int(item["port"]): item for item in declarations}
    if set(by_port) != set(ports):
        raise ValueError("XRP mecanum must declare motor ports 1, 2, 3, and 4")
    return tuple(
        _DirectionalDriveMotor(ports[port], by_port[port].get("inverted", False))
        for port in (1, 2, 3, 4)
    )


class _ServoAdapter:
    def __init__(self, servo):
        self.servo = servo

    def write(self, position):
        self.servo.set_angle(max(0.0, min(1.0, float(position))) * 180.0)

    def read(self, source):
        raise ValueError("XRPLib servos do not expose feedback")


class _RangefinderAdapter:
    def __init__(self, sensor):
        self.sensor = sensor

    def write(self, value):
        raise ValueError("Rangefinder is input-only")

    def read(self, source):
        if source != "DISTANCE_METERS":
            raise ValueError("Unsupported XRP rangefinder measurement")
        return self.sensor.distance() / 100.0


class _ImuAdapter:
    def __init__(self, sensor):
        self.sensor = sensor

    def write(self, value):
        raise ValueError("IMU is input-only")

    def read(self, source):
        if source == "IMU_YAW_RADIANS":
            return math.radians(self.sensor.get_yaw())
        if source == "IMU_YAW_RATE_RADIANS_PER_SECOND":
            # XRPLib reports millidegrees/second for the raw gyro rate.
            return math.radians(self.sensor.get_gyro_z_rate() / 1000.0)
        raise ValueError("Unsupported XRP IMU measurement")


def create_xrp_hardware(device):
    from XRPLib.defaults import imu, motor_three, rangefinder, servo_one, servo_two
    try:
        from XRPLib.defaults import motor_four, servo_three, servo_four
    except ImportError:
        motor_four = servo_three = servo_four = None
    kind = device["kind"]
    channel = device.get("connection", {}).get("channel")
    if kind == "MOTOR" and channel in (3, 4):
        motor = motor_three if channel == 3 else motor_four
        if motor is None:
            raise ValueError("This XRP board does not provide motor channel 4")
        return _MotorAdapter(motor)
    if kind == "POSITIONAL_SERVO" and channel in (1, 2, 3, 4):
        servo = {1: servo_one, 2: servo_two, 3: servo_three, 4: servo_four}[channel]
        if servo is None:
            raise ValueError("This XRP board does not provide the selected servo channel")
        return _ServoAdapter(servo)
    if kind == "DISTANCE_SENSOR":
        return _RangefinderAdapter(rangefinder)
    if kind == "IMU":
        return _ImuAdapter(imu)
    raise ValueError("Unsupported generated XRP hardware kind: " + str(kind))
