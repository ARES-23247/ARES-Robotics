"""Robot safety and loop-work regressions, independent of physical hardware."""

import json
import math
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ares_micro.robot import XrpRobot
from ares_micro.opmode import AutonomousRoutine
from ares_micro.telemetry import PROTOCOL


class Motor:
    def __init__(self): self.output = 0; self.reads = 0
    def set_effort(self, value): self.output = value
    def get_position(self): self.reads += 1; return 0


class Mechanism:
    document_id = "arm"
    faulted = False
    def __init__(self): self.output = 0; self.state_reads = 0
    @property
    def state(self): self.state_reads += 1; return {"target": 0.75}
    def periodic(self, dt): self.output = 0.75
    def stop(self): self.output = 0
    def recover_neutral(self): self.stop(); return True


class RobotRuntimeAuditTest(unittest.TestCase):
    def setUp(self):
        self.motors = [Motor(), Motor()]
        self.robot = XrpRobot("audit", "a" * 64, motors=self.motors)
        self.mechanism = Mechanism()
        self.robot.set_subsystems([self.mechanism])

    def command(self, command="START_TELEOP", revision=1):
        server = self.robot.telemetry
        server._recv_buffer = (json.dumps(dict(protocol=PROTOCOL, type="control", sessionId="audit",
            sequence=revision, requestRevision=revision, command=command, armed=True,
            selectedOpMode="route", driveFrame=[0.4, 0, 0])) + "\n").encode()
        server._process_buffer()

    def assert_neutral(self):
        self.assertEqual([0, 0], [motor.output for motor in self.motors])
        self.assertEqual(0, self.mechanism.output)
        self.assertEqual("DISABLED", self.robot.mode)

    def test_invalid_period_stops_all_outputs_before_running_actions(self):
        for dt in (0, -0.1, float("nan"), float("inf")):
            with self.subTest(dt=dt):
                self.robot.faulted = False
                self.command(revision=1)
                self.robot.mode = "TELEOP"
                self.robot.step(dt)
                self.assert_neutral()
                self.assertTrue(self.robot.faulted)

    def test_nonfinite_odometry_stops_teleop_and_mechanisms(self):
        self.command()
        self.robot.drivetrain.update_odometry = lambda dt: setattr(self.robot.drivetrain, "x", math.nan)
        self.robot.step()
        self.assert_neutral()
        self.assertTrue(self.robot.faulted)

    def test_invalid_constraint_result_cannot_drive_or_publish_invalid_pose(self):
        self.command()
        self.robot.set_pose_constraint(lambda previous, proposed: (math.nan, 0, 0))
        self.robot.step()
        self.assert_neutral()
        self.assertTrue(self.robot.faulted)
        self.assertTrue(math.isfinite(self.robot.drivetrain.x))

    def test_autonomous_completion_keeps_its_lease_without_entering_teleop(self):
        self.robot.set_autonomous_routines({"route": AutonomousRoutine(steps=[])}, "route")
        self.command("START_AUTO")
        self.robot.step()
        self.assertEqual("AUTO", self.robot.mode)
        self.assertEqual([0, 0], [motor.output for motor in self.motors])
        self.assertGreater(self.mechanism.output, 0)
        self.assertTrue(self.robot.telemetry.armed)
        self.robot.step()
        self.assertEqual([0, 0], [motor.output for motor in self.motors])
        self.command(revision=2)
        self.robot.step()
        self.assertGreater(self.motors[1].output, 0)
        self.assertGreater(self.mechanism.output, 0)

    def test_completed_auto_action_holds_mechanism_until_lease_expires(self):
        calls = []
        routine = AutonomousRoutine(steps=[dict(kind="ACTION", action_key="arm")],
                                    action_handler=lambda *args: calls.append(args))
        routine.update = mock.Mock(wraps=routine.update)
        self.robot.set_autonomous_routines({"route": routine}, "route")
        self.command("START_AUTO")
        self.robot.step()
        self.robot.step()
        self.assertEqual(1, len(calls))
        routine.update.assert_called_once()
        self.assertGreater(self.mechanism.output, 0)
        self.robot.telemetry.last_drive_ms -= 1000
        self.robot.step()
        self.assert_neutral()

    def test_shutdown_is_terminal_even_after_an_initialize_request(self):
        self.command()
        self.robot.step()
        self.robot.shutdown()
        self.command("INIT", revision=2)
        self.robot.step()
        self.command(revision=3)
        self.robot.step()
        self.assert_neutral()
        with mock.patch.object(self.robot.telemetry, "start") as start:
            self.assertFalse(self.robot.start_server())
            start.assert_not_called()

    def test_shutdown_releases_listener_when_client_cleanup_raises(self):
        listener = mock.Mock()
        self.robot.telemetry.server_socket = listener
        with mock.patch.object(self.robot.telemetry, "close_client", side_effect=OSError("client cleanup")):
            with self.assertRaises(OSError): self.robot.shutdown()
        listener.close.assert_called_once()
        self.assertIsNone(self.robot.telemetry.server_socket)
        self.assert_neutral()

    def test_listener_close_failure_does_not_leave_a_reusable_handle(self):
        listener = mock.Mock()
        listener.close.side_effect = OSError("listener cleanup")
        self.robot.telemetry.server_socket = listener
        with self.assertRaises(OSError): self.robot.shutdown()
        self.assertIsNone(self.robot.telemetry.server_socket)
        self.robot.shutdown()
        listener.close.assert_called_once()

    def test_disconnected_loop_does_not_copy_subsystem_telemetry(self):
        for _ in range(100): self.robot.step()
        self.assertEqual(0, self.mechanism.state_reads)
        self.assertEqual([100, 100], [motor.reads for motor in self.motors])

    def test_connected_telemetry_copies_state_once_and_preserves_period(self):
        self.robot.telemetry.is_connected = True
        published = {}
        self.robot.telemetry.publish_pose_frame = lambda **values: published.update(values)
        self.robot.step(0.035)
        self.assertEqual(1, self.mechanism.state_reads)
        self.assertEqual({"arm": {"target": 0.75}}, published["subsystems"])
        self.assertEqual(35, published["loop_time_ms"])

    def test_invalid_brownout_configuration_is_rejected(self):
        for value in (math.nan, math.inf, -1, 0, 2.9, 6.1, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                XrpRobot("audit", "a" * 64, motors=self.motors, brownout_threshold_volts=value)


if __name__ == "__main__":
    unittest.main()
