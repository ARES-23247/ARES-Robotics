"""Deterministic loop timing and cleanup tests; no physical devices or sockets."""

import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]


def load(relative, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TickClock:
    PERIOD = 1 << 20

    def __init__(self, start=0):
        self.now = start
        self.sleeps = []
        self.wall_reads = 0

    def ticks_us(self):
        return self.now % self.PERIOD

    def ticks_diff(self, now, then):
        return (now - then + self.PERIOD // 2) % self.PERIOD - self.PERIOD // 2

    def sleep_us(self, duration):
        self.sleeps.append(duration)
        self.now += duration

    def time(self):
        self.wall_reads += 1
        return self.now // 1000000

    def sleep(self, duration):
        self.sleep_us(round(duration * 1000000))


class PhysicalLifecycleAuditTest(unittest.TestCase):
    def setUp(self):
        self.module = load("main.py", "physical_main_audit")
        # Only explicit runtime identity introspection uses this global. Ordinary
        # imports still use Python's normal import machinery and the fake XRPLib.
        self.module.__dict__["__import__"] = lambda name: types.SimpleNamespace(
            implementation=("micropython", (1, 28, 0), "XRP RP2350"))
        defaults = types.ModuleType("XRPLib.defaults")
        defaults.board = mock.Mock()
        defaults.drivetrain = mock.Mock()
        defaults.imu = mock.Mock()
        self.enterContext(mock.patch.dict(sys.modules, {
            "XRPLib": types.ModuleType("XRPLib"), "XRPLib.defaults": defaults,
            "XRPLib.version": types.SimpleNamespace(__version__="2026.08.2")}))
        self.enterContext(mock.patch.object(self.module, "init_wifi"))
        self.enterContext(mock.patch.object(self.module, "create_subsystems", return_value=[]))
        self.enterContext(mock.patch.object(self.module, "create_autonomous_routines", return_value={}))
        self.robot = mock.Mock()
        self.robot.start_server.return_value = True
        self.enterContext(mock.patch.object(self.module, "XrpRobot", return_value=self.robot))
        self.enterContext(mock.patch.dict(self.module.PROJECT, {"use_otos": False, "drivetrain_type": "differential"}))

    def test_measured_period_sleep_budget_and_tick_wrap(self):
        for start in (0, TickClock.PERIOD - 3000):
            with self.subTest(start=start):
                clock = TickClock(start)
                periods = []

                def step(dt):
                    periods.append(dt)
                    if len(periods) == 3:
                        raise KeyboardInterrupt()
                    clock.now += 4000 if len(periods) == 1 else 35000

                self.robot.step.side_effect = step
                with mock.patch.object(self.module, "time", clock), self.assertRaises(KeyboardInterrupt):
                    self.module.main()
                self.assertEqual(periods, [0.02, 0.02, 0.035])
                self.assertEqual(clock.sleeps, [16000])
                self.assertEqual(clock.wall_reads, 0)
                self.robot.shutdown.assert_called()

    def test_failed_bind_aborts_before_registering_subsystems_and_closes_robot(self):
        self.robot.start_server.return_value = False
        self.robot.step.side_effect = RuntimeError("entered loop after failed bind")
        with self.assertRaisesRegex(RuntimeError, "bind"):
            self.module.main()
        self.robot.set_subsystems.assert_not_called()
        self.robot.shutdown.assert_called_once()

    def test_setup_exception_closes_robot(self):
        self.module.create_subsystems.side_effect = ValueError("bad subsystem")
        with self.assertRaisesRegex(ValueError, "bad subsystem"):
            self.module.main()
        self.robot.shutdown.assert_called_once()


class WifiLifecycleAuditTest(unittest.TestCase):
    def test_ap_startup_is_bounded(self):
        module = load("main.py", "wifi_main_audit")
        interface = mock.Mock()
        interface.active.return_value = False
        network = types.SimpleNamespace(AP_IF=1, STA_IF=2, WLAN=lambda _: interface)
        sleeps = []

        def sleep(duration):
            sleeps.append(duration)
            if len(sleeps) > 150:
                raise AssertionError("AP startup exceeded its deadline")

        with mock.patch.dict(sys.modules, {"network": network}), mock.patch.object(module.time, "sleep", side_effect=sleep):
            with self.assertRaisesRegex(RuntimeError, "Timed out"):
                module.init_wifi("AP", "test")
        self.assertLessEqual(len(sleeps), 150)


class SimulatorLifecycleAuditTest(unittest.TestCase):
    def setUp(self):
        self.module = load("simulator/xrp_simulator.py", "sim_lifecycle_audit")
        self.robot = mock.Mock()
        self.robot.start_server.return_value = True
        self.enterContext(mock.patch.object(self.module, "create_simulated_robot", return_value=(self.robot, ())))
        self.enterContext(mock.patch.object(self.module.signal, "signal"))

    def test_bind_failure_shuts_down_robot(self):
        self.robot.start_server.return_value = False
        with self.assertRaisesRegex(RuntimeError, "bind"):
            self.module.main()
        self.robot.shutdown.assert_called_once()

    def test_exception_and_keyboard_interrupt_shut_down_robot(self):
        for error in (RuntimeError("loop fault"), KeyboardInterrupt()):
            with self.subTest(error=type(error).__name__):
                self.robot.reset_mock()
                self.robot.step.side_effect = error
                with self.assertRaises(type(error)):
                    self.module.main()
                self.robot.shutdown.assert_called_once()

    def test_signal_exit_shuts_down_all_owned_resources(self):
        callbacks = {}
        self.module.signal.signal.side_effect = lambda signum, callback: callbacks.setdefault(signum, callback)
        self.robot.step.side_effect = lambda **_: callbacks[self.module.signal.SIGINT]()
        self.module.main()
        self.robot.shutdown.assert_called_once()

    def test_simulated_motor_invalid_effort_neutralizes(self):
        motor = self.module.SimMotor(1.0)
        for value in (float("nan"), float("inf"), -float("inf")):
            motor.set_effort(0.5)
            motor.set_effort(value)
            motor.advance(0.02)
            self.assertEqual(motor.effort, 0.0)
            self.assertEqual(motor.distance_meters, 0.0)

    def test_factory_failure_shuts_down_already_created_robot(self):
        module = load("simulator/xrp_simulator.py", "sim_factory_audit")
        with mock.patch.object(module, "XrpRobot", return_value=self.robot), mock.patch.object(
                module, "FieldCollisionConstraint", side_effect=ValueError("bad field")):
            with self.assertRaisesRegex(ValueError, "bad field"):
                module.create_simulated_robot()
        self.robot.shutdown.assert_called_once()


if __name__ == "__main__":
    unittest.main()
