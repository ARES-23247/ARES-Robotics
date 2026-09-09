import math
import sys
import types
import unittest
from unittest import mock

import hardware


class OutputAdapterAuditTest(unittest.TestCase):
    def test_failed_buzzer_neutral_is_retried(self):
        device = mock.Mock()
        adapter = hardware._BuzzerAdapter(device)
        adapter.write(60)
        device.reset_buzzer.side_effect = [OSError("write failed"), None]
        with self.assertRaises(OSError):
            adapter.write(0)
        adapter.write(0)
        self.assertEqual(device.reset_buzzer.call_count, 2)
        adapter.write(0)
        self.assertEqual(device.reset_buzzer.call_count, 2)

    def test_failed_buzzer_note_is_retried_and_success_is_cached(self):
        device = mock.Mock()
        adapter = hardware._BuzzerAdapter(device)
        device.play_note.side_effect = [OSError("write failed"), None]
        with self.assertRaises(OSError):
            adapter.write(60)
        adapter.write(60)
        adapter.write(60)
        self.assertEqual(device.play_note.call_count, 2)
        device.play_note.assert_called_with("C4", "quarter", blocking=False)

    def test_uncertain_note_write_invalidates_previous_neutral_cache(self):
        device = mock.Mock()
        adapter = hardware._BuzzerAdapter(device)
        adapter.write(0)
        device.play_note.side_effect = OSError("device may have accepted the note")
        with self.assertRaises(OSError):
            adapter.write(60)
        adapter.write(0)
        self.assertEqual(device.reset_buzzer.call_count, 2)

    def test_invalid_motor_requests_neutralize_and_finite_requests_are_bounded(self):
        device = mock.Mock()
        for adapter, method in ((hardware._MotorAdapter(device), "write"),
                                (hardware._DirectionalDriveMotor(device, True), "set_effort")):
            for value in (math.nan, math.inf, -math.inf):
                getattr(adapter, method)(value)
                device.set_effort.assert_called_with(0.0)
            getattr(adapter, method)(24.0)
            self.assertEqual(abs(device.set_effort.call_args.args[0]), 1.0)

    def test_invalid_servo_position_is_rejected_without_inventing_a_neutral(self):
        device = mock.Mock()
        adapter = hardware._ServoAdapter(device)
        for value in (math.nan, math.inf, -math.inf):
            with self.assertRaises(ValueError):
                adapter.write(value)
        device.set_angle.assert_not_called()
        adapter.write(0.5)
        device.set_angle.assert_called_once_with(90.0)
        with self.assertRaises(ValueError):
            adapter.read("POSITION")

    def test_invalid_pwm_and_digital_requests_neutralize(self):
        device = mock.Mock()
        for value in (math.nan, math.inf, -math.inf):
            hardware._PwmOutputAdapter(device).write(value)
            device.duty_u16.assert_called_with(0)
            hardware._DigitalOutputAdapter(device).write(value)
            device.value.assert_called_with(0)

    def test_invalid_indicator_request_turns_off_the_selected_output(self):
        board = mock.Mock()
        adapter = hardware._IndicatorLightAdapter(board)
        for value in (math.nan, math.inf, -math.inf):
            adapter.write(value)
        self.assertEqual(board.led_off.call_count, 3)
        board.led_on.assert_not_called()

    def test_rgb_components_share_only_their_own_board_state(self):
        first, second = mock.Mock(), mock.Mock()
        hardware._IndicatorLightAdapter(first, 0).write(1.0)
        hardware._IndicatorLightAdapter(second, 2).write(1.0)
        second.set_rgb_led.assert_called_once_with(0, 0, 255)
        hardware._IndicatorLightAdapter(first, 1).write(0.5)
        first.set_rgb_led.assert_called_with(255, 128, 0)

    def test_failed_rgb_write_does_not_leak_into_another_component_command(self):
        board = mock.Mock()
        red = hardware._IndicatorLightAdapter(board, 0)
        blue = hardware._IndicatorLightAdapter(board, 2)
        red.write(0.0)
        board.set_rgb_led.side_effect = [OSError("write failed"), None]
        with self.assertRaises(OSError):
            red.write(1.0)
        blue.write(0.5)
        board.set_rgb_led.assert_called_with(0, 0, 128)


class HardwareFactoryAuditTest(unittest.TestCase):
    def setUp(self):
        self.defaults = types.ModuleType("XRPLib.defaults")
        for name in ("board", "imu", "left_motor", "right_motor", "motor_three", "motor_four",
                     "rangefinder", "reflectance", "servo_one", "servo_two", "buzzer"):
            setattr(self.defaults, name, mock.Mock())
        self.machine = types.SimpleNamespace(Pin=mock.Mock(IN=0, OUT=1, PULL_UP=2), ADC=mock.Mock(), PWM=mock.Mock())
        self.enterContext(mock.patch.dict(sys.modules, {"XRPLib": types.ModuleType("XRPLib"),
            "XRPLib.defaults": self.defaults, "machine": self.machine}))

    def create(self, kind, channel=None, **extra):
        return hardware.create_xrp_hardware(dict(kind=kind, connection={"channel": channel}, **extra))

    def test_beta_motor_four_is_independent_of_optional_servo_ports(self):
        self.create("MOTOR", 4).write(6.0)
        self.defaults.motor_four.set_effort.assert_called_once_with(0.5)
        with self.assertRaisesRegex(ValueError, "servo channel"):
            self.create("POSITIONAL_SERVO", 3)

    def test_factory_motor_and_servo_channels_use_the_declared_devices(self):
        self.defaults.servo_three = mock.Mock()
        self.defaults.servo_four = mock.Mock()
        self.create("MOTOR", 3).write(-12.0)
        self.defaults.motor_three.set_effort.assert_called_once_with(-1.0)
        for channel, name in enumerate(("servo_one", "servo_two", "servo_three", "servo_four"), 1):
            self.create("POSITIONAL_SERVO", channel).write(0.25)
            getattr(self.defaults, name).set_angle.assert_called_once_with(45.0)
        del self.defaults.motor_four
        with self.assertRaisesRegex(ValueError, "motor channel 4"):
            self.create("MOTOR", 4)

    def test_factory_input_units_and_read_only_contracts(self):
        self.defaults.rangefinder.distance.return_value = 125.0
        self.defaults.board.is_button_pressed.return_value = True
        self.defaults.reflectance.get_middle.return_value = 0.75
        self.machine.ADC.return_value.read_u16.return_value = 65535
        cases = [
            (self.create("DISTANCE_SENSOR"), "DISTANCE_METERS", 1.25),
            (self.create("DIGITAL_INPUT"), "DIGITAL_STATE", True),
            (self.create("ANALOG_INPUT", 1, measurements=[{"source": "REFLECTANCE_NORMALIZED"}]), "REFLECTANCE_NORMALIZED", 0.75),
            (self.create("ANALOG_INPUT", 26), "ANALOG_VOLTAGE", 3.3),
        ]
        for adapter, source, expected in cases:
            self.assertEqual(adapter.read(source), expected)
            with self.assertRaises(ValueError):
                adapter.write(1)
            with self.assertRaises(ValueError):
                adapter.read("UNSUPPORTED")
        self.create("DIGITAL_INPUT", 5).read("DIGITAL_STATE")
        self.machine.Pin.assert_any_call(5, self.machine.Pin.IN, self.machine.Pin.PULL_UP)
        with self.assertRaises(ValueError):
            self.create("ANALOG_INPUT", 3, measurements=[{"source": "REFLECTANCE_NORMALIZED"}])

    def test_imu_adapter_converts_each_declared_measurement(self):
        adapter = self.create("IMU")
        cases = {
            "IMU_YAW_RADIANS": ("get_yaw", 180, math.pi),
            "IMU_PITCH_RADIANS": ("get_pitch", 90, math.pi / 2),
            "IMU_ROLL_RADIANS": ("get_roll", -90, -math.pi / 2),
            "IMU_YAW_RATE_RADIANS_PER_SECOND": ("get_gyro_z_rate", 180000, math.pi),
            "IMU_GYRO_X_RADIANS_PER_SECOND": ("get_gyro_x_rate", 90000, math.pi / 2),
            "IMU_GYRO_Y_RADIANS_PER_SECOND": ("get_gyro_y_rate", -90000, -math.pi / 2),
            "IMU_ACCEL_X_METERS_PER_SECOND_SQUARED": ("get_acc_x", 1000, 9.80665),
            "IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED": ("get_acc_y", -1000, -9.80665),
            "IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED": ("get_acc_z", 500, 4.903325),
        }
        for source, (getter, raw, expected) in cases.items():
            getattr(self.defaults.imu, getter).return_value = raw
            self.assertAlmostEqual(adapter.read(source), expected)
        with self.assertRaises(ValueError):
            adapter.read("UNSUPPORTED")
        with self.assertRaises(ValueError):
            adapter.write(0)

    def test_factory_output_routing_and_output_only_contracts(self):
        for kind, channel, value in (("DIGITAL_OUTPUT", 5, 1), ("PWM_OUTPUT", 6, 0.5),
                                      ("INDICATOR_LIGHT", None, 1), ("BUZZER", None, 60)):
            adapter = self.create(kind, channel)
            adapter.write(value)
            with self.assertRaises(ValueError):
                adapter.read("POSITION")
        self.machine.Pin.assert_any_call(5, self.machine.Pin.OUT, value=0)
        self.machine.PWM.return_value.freq.assert_called_once_with(1000)
        self.machine.PWM.return_value.duty_u16.assert_called_once_with(32768)
        self.defaults.board.led_on.assert_called_once()
        self.defaults.buzzer.play_note.assert_called_once_with("C4", "quarter", blocking=False)
        del self.defaults.buzzer
        with self.assertRaises(ValueError):
            self.create("BUZZER")
        with self.assertRaises(ValueError):
            self.create("UNKNOWN")

    def test_motor_feedback_conversion_and_mecanum_direction_order(self):
        motor = self.create("MOTOR", 3)
        self.defaults.motor_three.get_position.return_value = 2.0
        self.defaults.motor_three.get_speed.return_value = 120.0
        self.assertEqual(motor.read("MOTOR_POSITION_NATIVE"), 2.0)
        self.assertEqual(motor.read("MOTOR_VELOCITY_NATIVE_PER_SECOND"), 2.0)
        with self.assertRaises(ValueError):
            motor.read("CURRENT")
        declarations = [{"port": port, "inverted": port == 2} for port in (4, 2, 1, 3)]
        motors = hardware.create_xrp_mecanum_motors(declarations)
        for adapter, name, sign in zip(motors, ("left_motor", "right_motor", "motor_three", "motor_four"), (1, -1, 1, 1)):
            device = getattr(self.defaults, name)
            device.get_position.return_value = 3.0
            adapter.set_effort(0.25)
            device.set_effort.assert_called_with(0.25 * sign)
            self.assertEqual(adapter.get_position(), 3.0 * sign)
        with self.assertRaises(ValueError):
            hardware.create_xrp_mecanum_motors(declarations[:-1])
        del self.defaults.motor_four
        with self.assertRaises(RuntimeError):
            hardware.create_xrp_mecanum_motors(declarations)


if __name__ == "__main__":
    unittest.main()
