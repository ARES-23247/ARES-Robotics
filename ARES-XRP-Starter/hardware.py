"""Official XRPLib adapters used only on the physical XRP controller."""

import math


def create_otos_i2c(machine_name, i2c_type, pin_type):
    """Create the board's externally exposed Qwiic bus for an OTOS."""
    identity = str(machine_name)
    if "NanoXRP" in identity:
        # Cytron NanoXRP exposes I2C1 on GPIO14/15.
        bus, sda, scl = 1, 14, 15
    elif "RP2350" in identity:
        # SparkFun XRP Controller Qwiic 0.
        bus, sda, scl = 0, 4, 5
    elif "RP2040" in identity:
        # SparkFun XRP Controller Beta Qwiic/IMU bus.
        bus, sda, scl = 1, 18, 19
    else:
        raise RuntimeError("No verified OTOS Qwiic mapping exists for XRP board: " + identity)
    return i2c_type(id=bus, scl=pin_type(scl), sda=pin_type(sda), freq=400000)


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
        if source == "IMU_PITCH_RADIANS":
            return math.radians(self.sensor.get_pitch())
        if source == "IMU_ROLL_RADIANS":
            return math.radians(self.sensor.get_roll())
        if source == "IMU_GYRO_X_RADIANS_PER_SECOND":
            return math.radians(self.sensor.get_gyro_x_rate() / 1000.0)
        if source == "IMU_GYRO_Y_RADIANS_PER_SECOND":
            return math.radians(self.sensor.get_gyro_y_rate() / 1000.0)
        if source == "IMU_ACCEL_X_METERS_PER_SECOND_SQUARED":
            return self.sensor.get_acc_x() * 0.00980665
        if source == "IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED":
            return self.sensor.get_acc_y() * 0.00980665
        if source == "IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED":
            return self.sensor.get_acc_z() * 0.00980665
        raise ValueError("Unsupported XRP IMU measurement")


class _DigitalInputAdapter:
    def __init__(self, supplier):
        self.supplier = supplier

    def write(self, value):
        raise ValueError("Digital input is input-only")

    def read(self, source):
        if source != "DIGITAL_STATE":
            raise ValueError("Unsupported XRP digital measurement")
        return bool(self.supplier())


class _AnalogInputAdapter:
    def __init__(self, supplier, source="ANALOG_VOLTAGE"):
        self.supplier = supplier
        self.source = source

    def write(self, value):
        raise ValueError("Analog input is input-only")

    def read(self, source):
        if source != self.source:
            raise ValueError("Unsupported XRP analog measurement")
        return float(self.supplier())


class _DigitalOutputAdapter:
    def __init__(self, pin):
        self.pin = pin

    def write(self, value):
        self.pin.value(1 if float(value) >= 0.5 else 0)

    def read(self, source):
        raise ValueError("Digital output is output-only")


class _PwmOutputAdapter:
    def __init__(self, pwm):
        self.pwm = pwm

    def write(self, value):
        duty = max(0.0, min(1.0, float(value)))
        self.pwm.duty_u16(int(round(duty * 65535.0)))

    def read(self, source):
        raise ValueError("PWM output is output-only")


class _BuzzerAdapter:
    _NOTE_NAMES = ("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    def __init__(self, buzzer):
        self.buzzer = buzzer
        self.last_note = None

    def write(self, value):
        midi_note = max(0, min(127, int(round(float(value)))))
        if midi_note == self.last_note:
            return
        self.last_note = midi_note
        if midi_note == 0:
            self.buzzer.reset_buzzer()
            return
        note = self._NOTE_NAMES[midi_note % 12] + str(midi_note // 12 - 1)
        self.buzzer.play_note(note, "quarter", blocking=False)

    def read(self, source):
        raise ValueError("Buzzer is output-only")


class _IndicatorLightAdapter:
    _rgb = [0, 0, 0]

    def __init__(self, board, component=None):
        self.board = board
        self.component = component

    def write(self, value):
        level = max(0.0, min(1.0, float(value)))
        if self.component is None:
            self.board.led_on() if level >= 0.5 else self.board.led_off()
            return
        self._rgb[self.component] = int(round(level * 255.0))
        self.board.set_rgb_led(*self._rgb)

    def read(self, source):
        raise ValueError("Indicator light is output-only")


def create_xrp_hardware(device):
    from XRPLib.defaults import board, imu, motor_three, rangefinder, reflectance, servo_one, servo_two
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
    if kind == "DIGITAL_INPUT":
        if channel is None:
            return _DigitalInputAdapter(board.is_button_pressed)
        from machine import Pin
        pin = Pin(channel, Pin.IN, Pin.PULL_UP)
        return _DigitalInputAdapter(lambda: bool(pin.value()))
    if kind == "DIGITAL_OUTPUT":
        from machine import Pin
        return _DigitalOutputAdapter(Pin(channel, Pin.OUT, value=0))
    if kind == "ANALOG_INPUT":
        sources = {str(item.get("source", "")) for item in device.get("measurements", ())}
        if "REFLECTANCE_NORMALIZED" in sources:
            getter = {0: reflectance.get_left, 1: reflectance.get_middle, 2: reflectance.get_right}.get(channel)
            if getter is None:
                raise ValueError("Built-in reflectance channels are 0=left, 1=middle, 2=right")
            return _AnalogInputAdapter(getter, "REFLECTANCE_NORMALIZED")
        from machine import ADC, Pin
        adc = ADC(Pin(channel))
        return _AnalogInputAdapter(lambda: adc.read_u16() * (3.3 / 65535.0))
    if kind == "PWM_OUTPUT":
        from machine import PWM, Pin
        pwm = PWM(Pin(channel))
        pwm.freq(1000)
        return _PwmOutputAdapter(pwm)
    if kind == "INDICATOR_LIGHT":
        return _IndicatorLightAdapter(board, channel)
    if kind == "BUZZER":
        try:
            from XRPLib.defaults import buzzer
        except ImportError as error:
            raise ValueError("This XRP board does not provide a built-in buzzer") from error
        return _BuzzerAdapter(buzzer)
    raise ValueError("Unsupported generated XRP hardware kind: " + str(kind))
