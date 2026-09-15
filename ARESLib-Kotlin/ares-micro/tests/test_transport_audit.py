"""Transport and lease regressions with deterministic clocks and fragmented sockets."""

import hashlib
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ares_micro import telemetry
from ares_micro.robot import XrpRobot


def control(sequence=1, revision=1, **changes):
    message = dict(protocol=telemetry.PROTOCOL, type="control", sessionId="session",
                   sequence=sequence, requestRevision=revision, command="START_TELEOP",
                   armed=True, driveFrame=[0.4, 0, 0])
    message.update(changes)
    return (json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8")


class Socket:
    def __init__(self, chunks=()):
        self.chunks = list(chunks)
        self.closed = False
        self.sent = b""

    def recv(self, size):
        if not self.chunks:
            raise OSError(11, "would block")
        chunk = self.chunks.pop(0)
        assert len(chunk) <= size
        return chunk

    def send(self, data):
        self.sent += data
        return len(data)

    def setblocking(self, value):
        pass

    def close(self):
        self.closed = True


class ListenerBoundaryTest(unittest.TestCase):
    def test_default_listener_is_reachable_only_on_loopback(self):
        server = telemetry.XrpTelemetryServer("test", "a" * 64, "differential", port=0)
        try:
            self.assertTrue(server.start())
            address = server.server_socket.getsockname()
            self.assertEqual(address[0], "127.0.0.1")
            with telemetry.socket.create_connection(address, timeout=1) as client:
                server.poll()
                hello = json.loads(client.recv(4096).decode("utf-8"))
            self.assertEqual(hello["type"], "hello")
            self.assertEqual(hello["projectId"], "test")
        finally:
            server.close_client()
            if server.server_socket is not None:
                server.server_socket.close()

    def test_listener_rejects_implicit_all_interface_addresses(self):
        for host in (None, "", " ", "0.0.0.0"):
            with self.subTest(host=host), self.assertRaisesRegex(ValueError, "interface address"):
                telemetry.XrpTelemetryServer("test", "a" * 64, "differential", host=host)


class TransportAuditTest(unittest.TestCase):
    def setUp(self):
        self.now = 1000
        self.clock = mock.patch.object(telemetry, "_ticks_ms", side_effect=lambda: self.now)
        self.clock.start()
        self.addCleanup(self.clock.stop)
        self.server = telemetry.XrpTelemetryServer("test", "a" * 64, "differential")
        self.socket = Socket()
        self.server.server_socket = mock.Mock()
        self.server.client_socket = self.socket
        self.server.is_connected = True

    def receive(self, data):
        self.socket.chunks.extend(data[i:i + 512] for i in range(0, len(data), 512))
        while self.socket.chunks and self.server.is_connected:
            self.server.poll()

    def test_expired_lease_cannot_be_renewed_before_robot_observes_expiry(self):
        self.receive(control())
        self.assertEqual("START_TELEOP", self.server.get_command())
        self.now += 201
        self.receive(control(2))
        self.assertIsNone(self.server.get_drive_frame())
        self.assertEqual("", self.server.get_command())
        self.receive(control(3, 2))
        self.assertEqual("START_TELEOP", self.server.get_command())
        self.assertEqual([0.4, 0, 0], self.server.get_drive_frame())

    def test_live_heartbeat_and_exact_deadline_remain_valid(self):
        self.receive(control())
        self.now += 200
        self.assertIsNotNone(self.server.get_drive_frame())
        self.receive(control(2))
        self.now += 200
        self.assertIsNotNone(self.server.get_drive_frame())
        self.now += 1
        self.assertIsNone(self.server.get_drive_frame())
        self.assertEqual("", self.server.get_command())

    def test_clock_rewind_invalidates_lease(self):
        self.receive(control())
        self.now -= 1
        self.assertIsNone(self.server.get_drive_frame())

    def test_invalid_control_cancels_pending_start_and_requires_new_revision(self):
        for invalid in (dict(driveFrame=[float("inf"), 0, 0]), dict(command="bad"),
                        dict(sequence=True), dict(sequence=-1), dict(requestRevision=True),
                        dict(driveFrame=[True, 0, 0]), dict(driveFrame=["0.4", 0, 0]),
                        dict(selectedOpMode={}), dict(sessionId="")):
            with self.subTest(invalid=invalid):
                self.server.close_client()
                self.server.client_socket = self.socket
                self.server.is_connected = True
                self.receive(control())
                self.receive(control(2, **invalid) if "sequence" not in invalid else control(**invalid))
                self.assertFalse(self.server.armed)
                self.assertEqual("", self.server.get_command())
                self.receive(control(3))
                self.assertFalse(self.server.armed)
                self.receive(control(4, 2))
                self.assertTrue(self.server.armed)

    def test_disconnect_discards_pending_command_and_incomplete_line(self):
        self.receive(control() + b'{"incomplete":')
        self.server.close_client()
        self.assertEqual("", self.server.get_command())
        self.assertFalse(self.server._recv_buffer)

    def test_split_utf8_preserves_selected_autonomous_id(self):
        data = control(command="START_AUTO", selectedOpMode="route-é-λ")
        for byte in data:
            self.receive(bytes([byte]))
        self.assertEqual("route-é-λ", self.server.selected_opmode)
        self.assertTrue(self.server.armed)

    def test_invalid_utf8_does_not_silently_change_a_command(self):
        self.receive(control().replace(b"START_TELEOP", b"START_\xffTELEOP"))
        self.assertFalse(self.server.armed)
        self.assertEqual("", self.server.get_command())

    def test_unterminated_input_is_bounded_and_disconnects(self):
        self.receive(control())
        for _ in range(200):
            if not self.server.is_connected:
                break
            self.receive(b"x" * 512)
            self.assertLessEqual(len(self.server._recv_buffer), 16384)
        self.assertFalse(self.server.is_connected)
        self.assertFalse(self.server.armed)
        self.assertTrue(self.socket.closed)

    def test_malformed_line_cannot_be_followed_by_same_revision_rearm(self):
        self.receive(control() + b"[broken\n" + control(2))
        self.assertFalse(self.server.armed)
        self.assertEqual("", self.server.get_command())

    def test_stale_sequence_cannot_replace_valid_frame_or_command(self):
        self.receive(control(5, 3))
        self.receive(control(4, 4, command="STOP"))
        self.assertTrue(self.server.armed)
        self.assertEqual("START_TELEOP", self.server.get_command())

    def test_field_identity_is_checked_before_mutating_handler(self):
        payload = json.dumps(dict(id="new", revision=2, widthMeters=3, heightMeters=2))
        for changes in (dict(configId="old"), dict(revision=1), dict(sha256="b" * 64)):
            with self.subTest(changes=changes):
                handler = mock.Mock()
                self.server.set_field_config_handler(handler)
                message = dict(protocol=telemetry.PROTOCOL, type="fieldConfig", configId="new",
                               revision=2, sha256=hashlib.sha256(payload.encode()).hexdigest(), payload=payload)
                message.update(changes)
                self.receive((json.dumps(message) + "\n").encode())
                handler.assert_not_called()
                self.assertEqual("fieldRejected", json.loads(self.socket.sent.splitlines()[-1])["type"])

    def test_start_failure_closes_owned_listener_and_can_retry(self):
        self.server.server_socket = None
        failed = mock.Mock()
        failed.bind.side_effect = OSError("address in use")
        ready = mock.Mock()
        with mock.patch.object(telemetry.socket, "socket", side_effect=[failed, ready]) as factory:
            self.assertFalse(self.server.start())
            failed.close.assert_called_once()
            self.assertIsNone(self.server.server_socket)
            self.assertTrue(self.server.start())
            self.assertTrue(self.server.start())
            self.assertEqual(2, factory.call_count)
            self.assertIs(ready, self.server.server_socket)

    def test_accepted_socket_is_closed_if_configuration_fails(self):
        self.server.close_client()
        client = mock.Mock()
        client.setblocking.side_effect = OSError(5, "configuration failed")
        self.server.server_socket.accept.return_value = (client, ("peer", 1))
        self.server.poll()
        client.close.assert_called_once()
        self.assertIsNone(self.server.client_socket)

    def test_configuration_rejects_noncanonical_hash_and_unsafe_timeout(self):
        for value in (True, 0, -1, 99, 1001, 200.5, float("nan"), float("inf"), "200"):
            with self.subTest(timeout=value), self.assertRaises(ValueError):
                telemetry.XrpTelemetryServer("test", "a" * 64, "differential", deadman_timeout_ms=value)
        with self.assertRaises(ValueError):
            telemetry.XrpTelemetryServer("test", "z" * 64, "differential")

    def test_runtime_metadata_cannot_override_validated_handshake(self):
        server = telemetry.XrpTelemetryServer("test", "a" * 64, "differential",
            runtime_identity=dict(projectId="other", protocol="bad", role="desktop",
                                  contentSha256="bad", drivetrainType="bad", boardType="XRP_BETA"))
        hello = server.hello_payload()
        self.assertEqual("test", hello["projectId"])
        self.assertEqual("a" * 64, hello["contentSha256"])
        self.assertEqual(telemetry.PROTOCOL, hello["protocol"])
        self.assertEqual("robot", hello["role"])
        self.assertEqual("differential", hello["drivetrainType"])
        self.assertEqual("XRP_BETA", hello["boardType"])

    def test_desktop_clock_uses_monotonic_time(self):
        # Temporarily restore the implementation hidden by the deterministic clock.
        self.clock.stop()
        with mock.patch.object(telemetry.time, "monotonic", return_value=12.345), \
                mock.patch.object(telemetry.time, "time", side_effect=AssertionError("wall clock")):
            self.assertEqual(12345, telemetry._ticks_ms())

    def test_tick_wrap_preserves_lease_then_expires(self):
        period = 4096
        self.now = period - 100
        with mock.patch.object(telemetry.time, "ticks_diff", create=True,
                side_effect=lambda now, then: (now - then + period // 2) % period - period // 2):
            self.receive(control())
            self.now = 50
            self.assertIsNotNone(self.server.get_drive_frame())
            self.now = 101
            self.assertIsNone(self.server.get_drive_frame())

    def test_late_heartbeat_stops_robot_and_mechanism_until_explicit_restart(self):
        class Motor:
            output = 0
            def get_position(self): return 0
            def set_effort(self, value): self.output = value
        class Mechanism:
            output = 0
            document_id = "arm"
            state = {}
            faulted = False
            def periodic(self, dt): self.output = 0.75
            def stop(self): self.output = 0
        motors = [Motor(), Motor()]
        mechanism = Mechanism()
        robot = XrpRobot("test", "a" * 64, motors=motors)
        robot.telemetry = self.server
        robot.set_subsystems([mechanism])
        self.receive(control())
        robot.step()
        self.assertGreater(motors[1].output, 0)
        self.now += 201
        self.socket.chunks.append(control(2))
        robot.step()
        self.assertEqual("DISABLED", robot.mode)
        self.assertEqual([0, 0], [motor.output for motor in motors])
        self.assertEqual(0, mechanism.output)
        self.socket.chunks.append(control(3, 2))
        robot.step()
        self.assertGreater(motors[1].output, 0)
        self.assertGreater(mechanism.output, 0)


if __name__ == "__main__":
    unittest.main()
