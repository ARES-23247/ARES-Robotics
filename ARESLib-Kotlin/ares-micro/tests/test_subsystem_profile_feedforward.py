"""Executable profile/feedforward contracts for generated XRP mechanisms."""
import math
import unittest
from test_subsystem_control_audit import descriptor, CountingDevice
from ares_micro.subsystem import GeneratedXrpSubsystem


class ProfileFeedforwardTest(unittest.TestCase):
    def make(self, **changes):
        self.doc = descriptor(**changes)
        self.install(self.doc)

    def install(self, doc):
        self.device = CountingDevice()
        self.system = GeneratedXrpSubsystem(doc, lambda _: self.device)

    def step(self, target, dt=0.02):
        self.system.set_target("target", target)
        self.system.periodic(dt)
        return self.device.last_output

    def profile(self, **changes):
        self.make(strategy="PROFILED_POSITION_PID",
                  motionProfile=dict(maximumVelocity=1, maximumAcceleration=1), **changes)

    def test_profile_integrates_acceleration_and_canonical_defaults(self):
        self.make(strategy="PROFILED_POSITION_PID", kP=1)
        self.assertAlmostEqual(0.0004, self.step(1))
        self.profile(kP=1)
        self.assertAlmostEqual(0.0002, self.step(1))
        self.assertAlmostEqual(0.0008, self.step(1))

    def test_profile_arrives_without_acceleration_jump(self):
        self.profile(feedforward=dict(kind="SIMPLE_MOTOR", kV=1))
        previous = peak = 0.0
        for _ in range(100):
            velocity = self.step(0.1)
            self.assertLessEqual(abs(velocity-previous), 0.020000001)
            peak = max(peak, velocity)
            previous = velocity
        self.assertGreater(peak, 0.2)
        self.assertAlmostEqual(0, previous)

    def test_changed_nearby_goal_brakes_before_reversing(self):
        self.profile(feedforward=dict(kind="SIMPLE_MOTOR", kV=1))
        for _ in range(10): self.step(1)
        self.assertAlmostEqual(0.2, self.device.last_output)
        self.assertAlmostEqual(0.18, self.step(0.021))

    def test_profile_wraps_the_short_angular_goal(self):
        self.profile(kP=1, continuousInput=dict(enabled=True))
        self.device.readings["position"] = math.pi - 0.1
        self.assertAlmostEqual(0.0002, self.step(-math.pi + 0.1))

    def test_stop_and_fault_reset_profile_to_fresh_measurement(self):
        for failure in (False, True):
            with self.subTest(failure=failure):
                self.profile(feedforward=dict(kind="SIMPLE_MOTOR", kV=1))
                self.assertGreater(self.step(1), 0)
                if failure:
                    self.device.readings["position"] = float("nan")
                    self.system.periodic()
                    self.assertTrue(self.system.faulted)
                    self.device.readings["position"] = 0.5
                    self.assertTrue(self.system.recover_neutral())
                else:
                    self.system.stop()
                    self.device.readings["position"] = 0.5
                self.assertAlmostEqual(0, self.step(0.5))

    def test_invalid_profile_constraints_fault_before_output(self):
        for key, value in (("maximumVelocity", 0), ("maximumVelocity", float("nan")),
                           ("maximumAcceleration", -1), ("maximumAcceleration", float("inf"))):
            with self.subTest(key=key, value=value):
                self.make(strategy="PROFILED_POSITION_PID", kP=1, motionProfile={key: value})
                self.assertEqual(0, self.step(1))
                self.assertTrue(self.system.faulted)

    def test_velocity_feedforward_static_sign_and_zero(self):
        self.make(strategy="VELOCITY_PID", feedforward=dict(kind="SIMPLE_MOTOR", kS=0.2, kV=2))
        self.assertAlmostEqual(6.2, self.step(3))
        self.assertAlmostEqual(-6.2, self.step(-3))
        self.assertAlmostEqual(0, self.step(0))

    def test_profile_velocity_and_average_acceleration_feedforward(self):
        self.profile(feedforward=dict(kind="SIMPLE_MOTOR", kV=1, kA=0.1))
        self.assertAlmostEqual(0.12, self.step(1))
        self.assertAlmostEqual(0.14, self.step(1))

    def test_explicit_velocity_and_acceleration_override_profile(self):
        doc = descriptor(strategy="PROFILED_POSITION_PID", feedforward=dict(kind="SIMPLE_MOTOR",
            kS=0.2, kV=2, kA=0.5, velocityFieldId="velocity", accelerationFieldId="acceleration"))
        doc["stateFields"] += [dict(fieldId="velocity", type="DOUBLE", role="TARGET", defaultNumber=1),
                               dict(fieldId="acceleration", type="DOUBLE", role="TARGET", defaultNumber=2)]
        self.install(doc)
        self.assertAlmostEqual(3.2, self.step(1))
        self.assertEqual(1, self.device.read_count)

    def test_elevator_and_arm_gravity(self):
        self.make(feedforward=dict(kind="ELEVATOR", kG=0.7))
        self.assertAlmostEqual(0.7, self.step(0))
        self.make(feedforward=dict(kind="ARM", kG=2, gravityAngleFieldId="position"))
        for angle, expected in ((0, 2), (math.pi/2, 0), (math.pi, -2)):
            self.device.readings["position"] = angle
            self.assertAlmostEqual(expected, self.step(0))

    def test_two_joint_gravity_uses_both_links_and_relative_angle(self):
        for joint, expected in ((1, 4.4*9.80665), (2, 0.6*9.80665)):
            with self.subTest(joint=joint):
                doc = descriptor(feedforward=dict(kind="TWO_DOF_ARM", kG=1, linkageJoint=joint))
                doc["stateFields"].append(dict(fieldId="elbow", type="DOUBLE", role="TARGET", defaultNumber=0))
                doc["linkage"] = dict(enabled=True, link1LengthMeters=1, link2LengthMeters=0.5,
                    link1MassKg=2, link2MassKg=3, link1CenterOfMassMeters=0.4, link2CenterOfMassMeters=0.2,
                    joint1AngleFieldId="position", joint2AngleFieldId="elbow")
                self.install(doc)
                self.assertAlmostEqual(expected, self.step(0))
                self.system.set_target("elbow", math.pi)
                self.assertAlmostEqual((3.2 if joint == 1 else -0.6)*9.80665, self.step(0))

    def test_feedforward_participates_in_anti_windup_and_clamping(self):
        self.make(kI=1, minimumOutput=-1, maximumOutput=1, feedforward=dict(kind="ELEVATOR", kG=2))
        for _ in range(100): self.assertEqual(1, self.step(1))
        self.doc["controlLoops"][0]["feedforward"]["kG"] = 0
        self.assertAlmostEqual(0, self.step(0))

    def test_invalid_feedforward_faults_and_neutralizes(self):
        for ff in (dict(kind="UNKNOWN"), dict(kind="FOUR_BAR_LINKAGE"), dict(kind="ARM"),
                   dict(kind="SIMPLE_MOTOR", kV=float("nan")),
                   dict(kind="SIMPLE_MOTOR", kV=1, velocityFieldId="missing")):
            with self.subTest(ff=ff):
                self.make(kP=1, feedforward=ff)
                self.assertEqual(0, self.step(1))
                self.assertTrue(self.system.faulted)

    def test_optional_missing_feedforward_sensor_prevents_control(self):
        doc = descriptor(feedforward=dict(kind="ARM", kG=1, gravityAngleFieldId="angle"))
        doc["stateFields"].append(dict(fieldId="angle", type="DOUBLE", role="MEASUREMENT", defaultNumber=0))
        doc["hardware"].append(dict(hardwareId="encoder", kind="ENCODER", required=False,
                                    measurements=[dict(fieldId="angle", source="position")]))
        motor = CountingDevice()
        system = GeneratedXrpSubsystem(doc, lambda d: motor if d["hardwareId"] == "motor" else None)
        system.periodic()
        self.assertFalse(system.configured)
        self.assertFalse(system.recover_neutral())
        self.assertTrue(all(value == 0 for value in motor.writes))

    def test_feedforward_requires_pid_motor_and_numeric_fields(self):
        for strategy, kind, field_type in (("DIRECT", "MOTOR", "DOUBLE"),
                                           ("POSITION_PID", "SERVO", "DOUBLE"),
                                           ("POSITION_PID", "MOTOR", "BOOLEAN")):
            with self.subTest(strategy=strategy, kind=kind, field_type=field_type):
                doc = descriptor(strategy=strategy, feedforward=dict(kind="SIMPLE_MOTOR", velocityFieldId="speed"))
                doc["hardware"][0]["kind"] = kind
                doc["stateFields"].append(dict(fieldId="speed", type=field_type, role="TARGET", defaultNumber=0, defaultBoolean=False))
                self.install(doc)
                self.assertFalse(self.system.configured)
                self.assertEqual(0, self.step(1))

    def test_bad_auxiliary_value_prevents_every_nonzero_write(self):
        for value in (True, float("nan"), float("inf")):
            with self.subTest(value=value):
                doc = descriptor(kP=1)
                doc["stateFields"].append(dict(fieldId="speed", type="DOUBLE", role="TARGET", defaultNumber=value))
                doc["hardware"].append(dict(hardwareId="second", kind="MOTOR", safeOutput=0))
                doc["controlLoops"].append(dict(doc["controlLoops"][0], loopId="second", actuatorId="second",
                    feedforward=dict(kind="SIMPLE_MOTOR", velocityFieldId="speed", kV=1)))
                devices = {name: CountingDevice() for name in ("motor", "second")}
                system = GeneratedXrpSubsystem(doc, lambda d: devices[d["hardwareId"]])
                system.set_target("target", 1)
                system.periodic()
                self.assertTrue(system.faulted)
                for device in devices.values():
                    self.assertTrue(all(output == 0 for output in device.writes))

    def test_lowered_constraint_brakes_without_replacing_profile(self):
        self.profile(feedforward=dict(kind="SIMPLE_MOTOR", kV=1))
        profile = self.system._profiles["position"]
        for _ in range(30): self.step(10)
        self.doc["controlLoops"][0]["motionProfile"]["maximumVelocity"] = 0.1
        self.assertAlmostEqual(0.58, self.step(10))
        self.assertIs(profile, self.system._profiles["position"])
        self.system.stop()
        self.assertIs(profile, self.system._profiles["position"])


if __name__ == "__main__":
    unittest.main()
