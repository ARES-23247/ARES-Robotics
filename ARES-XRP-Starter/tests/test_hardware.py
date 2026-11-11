import unittest

from hardware import create_otos_i2c


class _Pin:
    def __init__(self, number):
        self.number = number


class _I2C:
    def __init__(self, **kwargs):
        self.arguments = kwargs


class XrpPhysicalBusTest(unittest.TestCase):
    def assert_bus(self, identity, bus, sda, scl):
        actual = create_otos_i2c(identity, _I2C, _Pin).arguments
        self.assertEqual(actual["id"], bus)
        self.assertEqual(actual["sda"].number, sda)
        self.assertEqual(actual["scl"].number, scl)
        self.assertEqual(actual["freq"], 400000)

    def test_verified_board_specific_qwiic_mappings(self):
        self.assert_bus("XRP Controller with RP2350", 0, 4, 5)
        self.assert_bus("XRP Controller Beta with RP2040", 1, 18, 19)

    def test_unknown_board_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "No verified OTOS"):
            create_otos_i2c("generic board", _I2C, _Pin)

    def test_motor_adapter_inverts_encoder_feedback_when_inverted(self):
        from hardware import _MotorAdapter

        class RawMotor:
            def __init__(self):
                self.effort = 0.0

            def set_effort(self, effort):
                self.effort = effort

            def get_position(self):
                return 12.5

            def get_speed(self):
                return 120.0

        raw = RawMotor()
        adapter = _MotorAdapter(raw, inverted=True)
        self.assertAlmostEqual(adapter.read("MOTOR_POSITION_NATIVE"), -12.5, places=6)
        self.assertAlmostEqual(adapter.read("MOTOR_VELOCITY_NATIVE_PER_SECOND"), -2.0, places=6)


if __name__ == "__main__":
    unittest.main()
