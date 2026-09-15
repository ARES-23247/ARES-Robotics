"""Controller math and sampling regressions for the shared XRP mechanism runtime."""

import copy
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ares_micro.subsystem import GeneratedXrpSubsystem, MockXrpDevice


def descriptor(**changes):
    loop = dict(loopId="position", strategy="POSITION_PID", actuatorId="motor",
                targetFieldId="target", measurementFieldId="position", kP=0, kI=0, kD=0,
                derivativeFilterTimeConstantSeconds=0, minimumOutput=-100, maximumOutput=100)
    loop.update(changes)
    return dict(documentId="arm", stateFields=[
        dict(fieldId="target", type="DOUBLE", role="TARGET", defaultNumber=0),
        dict(fieldId="position", type="DOUBLE", role="MEASUREMENT", defaultNumber=0)],
        hardware=[dict(hardwareId="motor", kind="MOTOR", safeOutput=0,
            measurements=[dict(fieldId="position", source="position")])], controlLoops=[loop])


class CountingDevice(MockXrpDevice):
    def __init__(self):
        super().__init__({"position": 0})
        self.read_count = 0
        self.writes = []
    def read(self, source):
        self.read_count += 1
        return super().read(source)
    def write(self, value):
        self.writes.append(value)
        super().write(value)


class SubsystemControlAuditTest(unittest.TestCase):
    def make(self, **changes):
        self.device = CountingDevice()
        self.system = GeneratedXrpSubsystem(descriptor(**changes), lambda _: self.device)
        return self.system

    def output(self, target, dt=0.02):
        self.system.set_target("target", target)
        self.system.periodic(dt)
        return self.device.last_output

    def test_stop_and_recovery_reset_integral_and_derivative_history(self):
        for reset in ("stop", "recover_neutral"):
            with self.subTest(reset=reset):
                self.make(kI=1, kD=1)
                self.output(1, 0.1)
                getattr(self.system, reset)()
                self.assertEqual(0, self.output(0, 0.1))

    def test_positive_and_negative_integral_gains_do_not_wind_up_at_saturation(self):
        for gain in (-1, 1):
            with self.subTest(gain=gain):
                self.make(kP=gain, kI=gain, minimumOutput=-1, maximumOutput=1)
                for _ in range(100): self.output(10, 0.1)
                self.assertEqual(0, self.output(0, 0.1))

    def test_unsaturated_integral_accumulates_and_unwinds(self):
        self.make(kI=1)
        self.assertAlmostEqual(0.2, self.output(2, 0.1))
        self.assertAlmostEqual(0.4, self.output(2, 0.1))
        self.assertAlmostEqual(0.3, self.output(-1, 0.1))

    def test_continuous_derivative_uses_the_short_angular_delta(self):
        self.make(kD=1, continuousInput=dict(enabled=True, minimumInput=-math.pi, maximumInput=math.pi))
        self.assertEqual(0, self.output(math.pi - 0.01, 0.02))
        self.assertAlmostEqual(1, self.output(-math.pi + 0.01, 0.02))

    def test_derivative_filter_matches_first_order_response(self):
        self.make(kD=1, derivativeFilterTimeConstantSeconds=0.02)
        self.assertEqual(0, self.output(0, 0.02))
        self.assertAlmostEqual(25, self.output(1, 0.02))
        self.assertAlmostEqual(12.5, self.output(1, 0.02))

    def test_bang_bang_reversal_passes_through_neutral(self):
        self.make(strategy="BANG_BANG", tolerance=0.1, hysteresis=0.2,
                  minimumOutput=-0.7, maximumOutput=0.8)
        self.assertEqual(0.8, self.output(1))
        self.assertEqual(0, self.output(-1))
        self.assertEqual(-0.7, self.output(-1))
        self.assertEqual(0, self.output(1))
        self.assertEqual(0.8, self.output(1))

    def test_bang_bang_hysteresis_and_stop_reset(self):
        self.make(strategy="BANG_BANG", tolerance=0.1, hysteresis=0.2)
        self.assertEqual(0, self.output(0.2))
        self.assertEqual(100, self.output(0.4))
        self.assertEqual(100, self.output(0.2))
        self.assertEqual(0, self.output(0.1))
        self.assertEqual(0, self.output(0.2))
        self.output(0.4)
        self.system.stop()
        self.assertEqual(0, self.output(0.2))

    def test_one_sided_bang_bang_limits_never_command_the_wrong_direction(self):
        self.make(strategy="BANG_BANG", minimumOutput=0.2, maximumOutput=0.8)
        self.assertEqual(0, self.output(-1))
        self.assertEqual(0.8, self.output(1))
        self.make(strategy="BANG_BANG", minimumOutput=-0.8, maximumOutput=-0.2)
        self.assertEqual(0, self.output(1))
        self.assertEqual(-0.8, self.output(-1))

    def test_duplicate_measurements_share_one_raw_read_each_cycle(self):
        doc = descriptor()
        doc["stateFields"].append(dict(fieldId="scaled", type="DOUBLE", role="MEASUREMENT", defaultNumber=0))
        doc["hardware"][0]["measurements"].append(dict(fieldId="scaled", source="position", scale=2, offset=1))
        device = CountingDevice()
        system = GeneratedXrpSubsystem(doc, lambda _: device)
        for reading in (3, 4):
            device.readings["position"] = reading
            system.periodic()
            self.assertEqual(reading, system.state["position"])
            self.assertEqual(2 * reading + 1, system.state["scaled"])
        self.assertEqual(2, device.read_count)

    def test_invalid_period_cannot_command_direct_or_pid_outputs(self):
        for strategy in ("DIRECT", "POSITION_PID"):
            for dt in (0, -1, math.nan, math.inf):
                with self.subTest(strategy=strategy, dt=dt):
                    self.make(strategy=strategy, kP=1)
                    self.output(1, dt)
                    self.assertTrue(self.system.faulted)
                    self.assertEqual(0, self.device.last_output)

    def test_nonfinite_controller_math_is_not_masked_by_clamping(self):
        for changes in (dict(kP=math.nan), dict(kI=math.inf), dict(kD=math.nan),
                        dict(minimumOutput=math.nan), dict(maximumOutput=math.inf),
                        dict(derivativeFilterTimeConstantSeconds=-1)):
            with self.subTest(changes=changes):
                self.make(**changes)
                self.output(1)
                self.assertTrue(self.system.faulted)
                self.assertEqual(0, self.device.last_output)

    def test_targets_obey_declared_types_and_bounds(self):
        for kind, default, good, bad in (
            ("DOUBLE", "defaultNumber", 0.5, (True, "1", math.nan)),
            ("INT", "defaultInt", 1, (True, 1.5, "1")),
            ("BOOLEAN", "defaultBoolean", True, (1, "true")),
            ("STRING", "defaultText", "ready", (1, False))):
            with self.subTest(kind=kind):
                doc = descriptor()
                doc["controlLoops"] = []
                doc["stateFields"][0] = dict(fieldId="target", type=kind, role="TARGET", **{default: good})
                system = GeneratedXrpSubsystem(doc, lambda _: CountingDevice())
                system.set_target("target", good)
                for value in bad:
                    with self.subTest(value=value), self.assertRaises(ValueError):
                        system.set_target("target", value)
                self.assertEqual(good, system.state["target"])

    def test_invalid_later_loop_cannot_briefly_command_an_earlier_actuator(self):
        doc = descriptor(strategy="DIRECT")
        second = copy.deepcopy(doc["hardware"][0])
        second["hardwareId"] = "second"
        second["measurements"] = []
        doc["hardware"].append(second)
        loop = dict(doc["controlLoops"][0], loopId="bad", actuatorId="second",
                    strategy="POSITION_PID", kP=math.nan)
        doc["controlLoops"].append(loop)
        devices = {name: CountingDevice() for name in ("motor", "second")}
        system = GeneratedXrpSubsystem(doc, lambda d: devices[d["hardwareId"]])
        system.set_target("target", 1)
        system.periodic()
        self.assertTrue(system.faulted)
        self.assertTrue(all(value == 0 for device in devices.values() for value in device.writes))

    def test_required_factory_returning_none_is_not_healthy_configuration(self):
        system = GeneratedXrpSubsystem(descriptor(), lambda _: None)
        self.assertFalse(system.configured)
        self.assertTrue(system.faulted)

    def test_missing_optional_feedback_cannot_fall_back_to_default_state(self):
        doc = descriptor(kP=1)
        doc["hardware"][0]["measurements"] = []
        doc["hardware"].append(dict(hardwareId="sensor", required=False,
            measurements=[dict(fieldId="position", source="position")]))
        device = CountingDevice()
        system = GeneratedXrpSubsystem(doc, lambda d: device if d["hardwareId"] == "motor" else None)
        self.assertFalse(system.configured)
        system.set_target("target", 1)
        system.periodic()
        self.assertEqual(0, device.last_output)
        self.assertFalse(system.recover_neutral())

    def test_omitted_controller_defaults_match_the_canonical_descriptor(self):
        doc = descriptor(strategy="DIRECT")
        loop = doc["controlLoops"][0]
        del loop["minimumOutput"], loop["maximumOutput"]
        device = CountingDevice()
        system = GeneratedXrpSubsystem(doc, lambda _: device)
        system.set_target("target", 20)
        system.periodic()
        self.assertEqual(12, device.last_output)
        doc = descriptor(kD=1, continuousInput=dict(enabled=True))
        del doc["controlLoops"][0]["derivativeFilterTimeConstantSeconds"]
        system = GeneratedXrpSubsystem(doc, lambda _: device)
        system.set_target("target", math.pi - 0.01)
        system.periodic()
        system.set_target("target", -math.pi + 0.01)
        system.periodic()
        self.assertFalse(system.faulted)
        self.assertAlmostEqual(0.5, device.last_output)


if __name__ == "__main__":
    unittest.main()
