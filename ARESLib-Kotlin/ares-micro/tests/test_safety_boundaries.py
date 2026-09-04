import json
import sys
import unittest
from pathlib import Path
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ares_micro.robot import XrpRobot
from ares_micro.telemetry import XrpTelemetryServer, PROTOCOL

class Motor:
    def __init__(self):
        self.output = 0
        self.fail = False
    def set_effort(self, value):
        if self.fail: raise OSError("motor disconnected")
        self.output = value
    def get_position(self): return 0

class Mechanism:
    document_id = "intake"
    state = {}
    faulted = False
    def __init__(self): self.output = 0
    def periodic(self, dt): self.output = 0.75
    def stop(self): self.output = 0
    def recover_neutral(self): self.stop(); return True

class SafetyBoundariesTest(unittest.TestCase):
    def setUp(self):
        self.motors = [Motor(), Motor()]
        self.robot = XrpRobot("test", "a" * 64, motors=self.motors)
        self.mechanism = Mechanism()
        self.robot.set_subsystems([self.mechanism])
        self.control(1, 1)
        self.robot.step()
        self.assertGreater(self.motors[1].output, 0)
        self.assertGreater(self.mechanism.output, 0)

    def control(self, sequence, revision):
        server = self.robot.telemetry
        server._recv_buffer = json.dumps(dict(protocol=PROTOCOL, type="control", sessionId="test",
            sequence=sequence, requestRevision=revision, command="START_TELEOP", armed=True,
            driveFrame=[0.4, 0, 0])) + "\n"
        server._process_buffer()

    def assert_neutral(self):
        self.assertEqual("DISABLED", self.robot.mode)
        self.assertEqual(0, self.motors[1].output)
        self.assertEqual(0, self.mechanism.output)

    def test_battery_exception_stops_every_output(self):
        self.robot.battery_voltage_supplier = mock.Mock(side_effect=OSError("ADC failed"))
        self.robot.step()
        self.assert_neutral()
        self.assertTrue(self.robot.faulted)

    def test_failed_early_stop_still_stops_other_motor_and_mechanism(self):
        self.motors[0].fail = True
        self.robot.telemetry.last_command = "STOP"
        self.robot.step()
        self.assert_neutral()
        self.assertTrue(self.robot.faulted)

    def test_expiry_requires_a_new_explicit_start_revision(self):
        self.robot.telemetry.last_drive_ms -= 1000
        self.robot.step()
        self.assert_neutral()
        self.control(2, 1)  # renewed heartbeat alone must not resume mechanisms
        self.robot.step()
        self.assert_neutral()
        self.control(3, 2)
        self.robot.step()
        self.assertEqual("TELEOP", self.robot.mode)
        self.assertGreater(self.mechanism.output, 0)

    def test_disconnect_stops_mechanisms(self):
        self.robot.telemetry.close_client()
        self.robot.step()
        self.assert_neutral()

    def test_stock_xrplib_composite_failure_still_attempts_both_motors(self):
        motors = self.motors
        class StockDrive:
            left_motor, right_motor = motors
            def set_effort(self, left, right):
                self.left_motor.set_effort(left)
                self.right_motor.set_effort(right)
        robot = XrpRobot("test", "a" * 64, drivetrain_io=StockDrive())
        robot.set_subsystems([self.mechanism])
        motors[0].fail = True
        robot.battery_voltage_supplier = mock.Mock(side_effect=OSError("ADC failed"))
        robot.step()
        self.assertEqual(0, motors[1].output)
        self.assertEqual(0, self.mechanism.output)
        self.assertTrue(robot.faulted)

    def test_shutdown_attempts_all_outputs(self):
        self.motors[0].fail = True
        self.robot.shutdown()
        self.assert_neutral()

    def test_poll_and_publish_exceptions_are_inside_safety_boundary(self):
        for method in ("poll", "publish_pose_frame"):
            with self.subTest(method=method):
                self.robot.faulted = False
                self.control(5, 4)
                self.robot.mode = "TELEOP"
                with mock.patch.object(self.robot.telemetry, method, side_effect=RuntimeError("fault")):
                    self.robot.step()
                self.assert_neutral()
                self.assertTrue(self.robot.faulted)

class PartialSocket:
    def __init__(self): self.bytes = b""; self.blocked = False; self.closed = False
    def send(self, data):
        if self.blocked: raise OSError(11, "would block")
        count = min(5, len(data)); self.bytes += data[:count]; return count
    def close(self): self.closed = True

class TransportTest(unittest.TestCase):
    def setUp(self):
        self.server = XrpTelemetryServer("test", "a" * 64, "differential")
        self.sock = PartialSocket()
        self.server.client_socket = self.sock
        self.server.is_connected = True

    def test_partial_hello_and_telemetry_retain_complete_ordered_lines(self):
        self.server._send(self.server.hello_payload())
        self.sock.blocked = True
        self.server.publish_pose_frame(1, 2, 3)
        self.server._flush_output()
        self.assertTrue(self.server.is_connected)
        self.sock.blocked = False
        while self.server._send_buffer: self.server._flush_output()
        lines = [json.loads(line) for line in self.sock.bytes.splitlines()]
        self.assertEqual(["hello", "telemetry"], [line["type"] for line in lines])
        self.assertEqual(1, lines[1]["poseX"])

    def test_backpressure_is_bounded_and_disarms(self):
        self.sock.blocked = True
        self.server.armed = True
        for _ in range(1000):
            self.server.publish_pose_frame(0, 0, 0)
            self.assertLessEqual(len(self.server._send_buffer), 16384)
        self.assertFalse(self.server.armed)
        self.assertTrue(self.sock.closed)
