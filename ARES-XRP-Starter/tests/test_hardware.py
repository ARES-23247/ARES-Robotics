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
        self.assert_bus("Cytron NanoXRP Controller with RP2040", 1, 14, 15)

    def test_unknown_board_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "No verified OTOS"):
            create_otos_i2c("generic board", _I2C, _Pin)


if __name__ == "__main__":
    unittest.main()
