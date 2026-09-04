import unittest
import json
import hashlib
import math
import struct
import sys
import os

# Add parent directory to path to import ares_micro
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from ares_micro.kinematics import DifferentialDriveKinematics, MecanumKinematics, wrap_angle
from ares_micro.sparkfun_otos import SparkFunOTOS
from ares_micro.drivetrain import DifferentialDrivetrain, MecanumDrivetrain
from ares_micro.opmode import Waypoint, PidPoseFollower, AutonomousRoutine
from ares_micro.telemetry import XrpTelemetryServer, PROTOCOL
from ares_micro.robot import XrpRobot
from ares_micro.subsystem import GeneratedXrpSubsystem, MockXrpDevice

class TestKinematics(unittest.TestCase):
    def test_differential_kinematics(self):
        diff = DifferentialDriveKinematics(track_width_meters=0.155)
        # Straight forward at 0.5 m/s
        left, right = diff.to_wheel_speeds(0.5, 0.0)
        self.assertAlmostEqual(left, 0.5)
        self.assertAlmostEqual(right, 0.5)

        vx, omega = diff.to_chassis_speeds(left, right)
        self.assertAlmostEqual(vx, 0.5)
        self.assertAlmostEqual(omega, 0.0)

        # Pure rotation CCW at 2.0 rad/s
        left_rot, right_rot = diff.to_wheel_speeds(0.0, 2.0)
        self.assertTrue(left_rot < 0)
        self.assertTrue(right_rot > 0)
        vx_rot, omega_rot = diff.to_chassis_speeds(left_rot, right_rot)
        self.assertAlmostEqual(vx_rot, 0.0)
        self.assertAlmostEqual(omega_rot, 2.0)

    def test_mecanum_kinematics(self):
        mec = MecanumKinematics(track_width_meters=0.155, wheel_base_meters=0.140)
        # Forward
        fl, fr, bl, br = mec.to_wheel_speeds(1.0, 0.0, 0.0)
        self.assertAlmostEqual(fl, 1.0)
        self.assertAlmostEqual(fr, 1.0)
        self.assertAlmostEqual(bl, 1.0)
        self.assertAlmostEqual(br, 1.0)
        vx, vy, omega = mec.to_chassis_speeds(fl, fr, bl, br)
        self.assertAlmostEqual(vx, 1.0)
        self.assertAlmostEqual(vy, 0.0)
        self.assertAlmostEqual(omega, 0.0)

        # Lateral strafe left (+Y)
        fl, fr, bl, br = mec.to_wheel_speeds(0.0, 1.0, 0.0)
        self.assertAlmostEqual(fl, -1.0)
        self.assertAlmostEqual(fr, 1.0)
        self.assertAlmostEqual(bl, 1.0)
        self.assertAlmostEqual(br, -1.0)
        vx, vy, omega = mec.to_chassis_speeds(fl, fr, bl, br)
        self.assertAlmostEqual(vx, 0.0)
        self.assertAlmostEqual(vy, 1.0)
        self.assertAlmostEqual(omega, 0.0)

    def test_mecanum_drivetrain_integrates_four_encoder_distances(self):
        class Motor:
            def __init__(self):
                self.position = 0.0

            def set_effort(self, effort):
                pass

            def get_position(self):
                return self.position

        motors = [Motor() for _ in range(4)]
        drivetrain = MecanumDrivetrain(*motors, wheel_radius=1.0)
        for motor in motors:
            motor.position = 1.0 / (2.0 * math.pi)
        drivetrain.update_odometry(dt=1.0)
        self.assertAlmostEqual(drivetrain.x, 1.0)
        self.assertAlmostEqual(drivetrain.y, 0.0)
        self.assertAlmostEqual(drivetrain.heading, 0.0)

    def test_differential_encoder_translation_uses_imu_heading(self):
        class DriveIo:
            def __init__(self):
                self.left = 0.0
                self.right = 0.0

            def set_effort(self, left, right):
                pass

            def get_left_encoder_position(self):
                return self.left

            def get_right_encoder_position(self):
                return self.right

        io = DriveIo()
        heading = [0.0]
        drivetrain = DifferentialDrivetrain(drivetrain_io=io, heading_supplier=lambda: heading[0])
        io.left = io.right = 100.0
        heading[0] = math.pi / 2.0
        drivetrain.update_odometry(dt=1.0)
        self.assertAlmostEqual(drivetrain.heading, math.pi / 2.0)
        self.assertAlmostEqual(drivetrain.x, math.sqrt(0.5), places=5)
        self.assertAlmostEqual(drivetrain.y, math.sqrt(0.5), places=5)

    def test_wrap_angle(self):
        self.assertAlmostEqual(wrap_angle(0.0), 0.0)
        self.assertAlmostEqual(wrap_angle(3.0 * math.pi), math.pi)
        self.assertAlmostEqual(wrap_angle(-3.0 * math.pi), -math.pi)


class MockI2C:
    def __init__(self):
        self.registers = {}
        self.registers[SparkFunOTOS.REG_PRODUCT_ID] = bytes([SparkFunOTOS.PRODUCT_ID])
        # Preload 12 bytes of telemetry at REG_POS_X:
        import struct
        self.registers[SparkFunOTOS.REG_POS_X] = struct.pack(
            '<hhhhhh',
            round(0.5 / SparkFunOTOS.INT16_TO_METERS),
            round(0.2 / SparkFunOTOS.INT16_TO_METERS),
            round((math.pi / 2.0) / SparkFunOTOS.INT16_TO_RADIANS),
            round(0.1 / SparkFunOTOS.INT16_TO_METERS_PER_SECOND),
            0,
            round(1.0 / SparkFunOTOS.INT16_TO_RADIANS_PER_SECOND),
        )

    def readfrom_mem(self, addr, reg, nbytes):
        if reg in self.registers:
            val = self.registers[reg]
            if len(val) >= nbytes:
                return val[:nbytes]
        return bytes(nbytes)

    def writeto_mem(self, addr, reg, data):
        self.registers[reg] = data


class TestSparkFunOTOS(unittest.TestCase):
    def test_otos_mock_read(self):
        mock_i2c = MockI2C()
        otos = SparkFunOTOS(i2c=mock_i2c)
        self.assertTrue(otos.begin())

        x, y, h, vx, vy, omega = otos.update()
        self.assertAlmostEqual(x, 0.5, places=3)
        self.assertAlmostEqual(y, 0.2, places=3)
        self.assertAlmostEqual(h, 1.57, places=2)
        self.assertAlmostEqual(vx, 0.1, places=3)

    def test_otos_uses_official_registers_scaling_and_pose_writes(self):
        mock_i2c = MockI2C()
        otos = SparkFunOTOS(i2c=mock_i2c)
        otos.set_linear_scalar(1.025)
        self.assertEqual(mock_i2c.registers[0x04], bytes([25]))
        otos.set_angular_scalar(0.975)
        self.assertEqual(mock_i2c.registers[0x05], bytes([231]))
        otos.set_offsets(0.1, -0.2, math.pi / 4.0)
        raw_x, raw_y, raw_h = struct.unpack('<hhh', mock_i2c.registers[0x10])
        self.assertAlmostEqual(raw_x * SparkFunOTOS.INT16_TO_METERS, 0.1, places=3)
        self.assertAlmostEqual(raw_y * SparkFunOTOS.INT16_TO_METERS, -0.2, places=3)
        self.assertAlmostEqual(raw_h * SparkFunOTOS.INT16_TO_RADIANS, math.pi / 4.0, places=3)
        otos.reset_tracking()
        self.assertEqual(mock_i2c.registers[0x07], bytes([1]))

    def test_otos_rejects_wrong_identity_and_propagates_runtime_io_failure(self):
        mock_i2c = MockI2C()
        mock_i2c.registers[SparkFunOTOS.REG_PRODUCT_ID] = bytes([0x5C])
        self.assertFalse(SparkFunOTOS(i2c=mock_i2c).begin())

        class FailingI2C(MockI2C):
            def readfrom_mem(self, addr, reg, nbytes):
                if reg == SparkFunOTOS.REG_POS_X:
                    raise OSError('I2C disconnected')
                return super().readfrom_mem(addr, reg, nbytes)

        with self.assertRaises(OSError):
            SparkFunOTOS(i2c=FailingI2C()).update()


class TestAutonomousRoutine(unittest.TestCase):
    def test_pid_follower_and_routine(self):
        routine = AutonomousRoutine(
            name="Test Auto",
            waypoints=[
                Waypoint(x=1.0, y=0.0, heading_rad=0.0, speed=0.5, tolerance=0.05),
            ]
        )
        # Initial step far from target: should drive forward
        vx, omega, finished = routine.update(current_x=0.0, current_y=0.0, current_heading=0.0)
        self.assertTrue(vx > 0)
        self.assertFalse(finished)

        # Step at waypoint: should reach and finish
        vx, omega, finished = routine.update(current_x=1.0, current_y=0.0, current_heading=0.0)
        self.assertTrue(finished)

    def test_wait_and_action_steps_are_deterministic(self):
        actions = []
        routine = AutonomousRoutine(
            steps=[
                {"kind": "WAIT", "duration_seconds": 0.04},
                {"kind": "ACTION", "action_key": "subsystem.arm.set.target", "arguments": {"value": 0.5}},
            ],
            action_handler=lambda key, arguments: actions.append((key, arguments)),
        )
        self.assertFalse(routine.update(0.0, 0.0, 0.0, 0.02)[2])
        self.assertFalse(routine.update(0.0, 0.0, 0.0, 0.02)[2])
        self.assertTrue(routine.update(0.0, 0.0, 0.0, 0.02)[2])
        self.assertEqual(actions, [("subsystem.arm.set.target", {"value": 0.5})])


class TestXrpRobotLifecycle(unittest.TestCase):
    PROJECT_ID = "test-xrp-project"
    CONTENT_SHA256 = "a" * 64

    def robot(self, **kwargs):
        return XrpRobot(
            project_id=self.PROJECT_ID,
            content_sha256=self.CONTENT_SHA256,
            **kwargs,
        )

    def server(self, **kwargs):
        return XrpTelemetryServer(
            project_id=self.PROJECT_ID,
            content_sha256=self.CONTENT_SHA256,
            drivetrain_type="differential",
            **kwargs,
        )

    class MockDriveIo:
        def __init__(self):
            self.last = None

        def set_effort(self, left, right):
            self.last = (left, right)

    def test_robot_lifecycle_and_drive(self):
        output = self.MockDriveIo()
        robot = self.robot(drivetrain_type="differential", use_otos=False, drivetrain_io=output)
        self.assertEqual(robot.mode, XrpRobot.STATE_INIT)

        # TeleOp and autonomous starts are distinct so the wrong mode cannot launch.
        robot.telemetry.last_command = "START_TELEOP"
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_TELEOP)

        # Send leased drive frame: forward at 0.5 m/s
        robot.telemetry.last_drive_frame = [0.5, 0.0, 0.0]
        robot.telemetry.last_drive_ms = 1
        robot.telemetry.armed = True
        robot.telemetry.deadman_timeout_ms = 10 ** 15
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_TELEOP)
        self.assertIsNotNone(output.last)

        # Send STOP command
        robot.telemetry.last_command = "STOP"
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_DISABLED)
        self.assertEqual(output.last, (0.0, 0.0))

    def test_robot_refuses_missing_motor_output(self):
        with self.assertRaises(ValueError):
            self.robot(drivetrain_type="differential", use_otos=False)

    def test_robot_refuses_declared_otos_without_bus_or_sensor(self):
        output = self.MockDriveIo()
        with self.assertRaisesRegex(ValueError, "explicit physical I2C"):
            self.robot(drivetrain_type="differential", use_otos=True, drivetrain_io=output)
        with self.assertRaisesRegex(ValueError, "did not answer"):
            self.robot(drivetrain_type="differential", use_otos=True, i2c=object(), drivetrain_io=output)

    def test_four_motor_xrp_mecanum_executes_strafe_frame(self):
        class Motor:
            def __init__(self):
                self.effort = 0.0

            def set_effort(self, effort):
                self.effort = effort

            def get_position(self):
                return 0.0

        motors = [Motor() for _ in range(4)]
        robot = self.robot(drivetrain_type="mecanum", use_otos=False, motors=motors)
        robot.telemetry.last_command = "START_TELEOP"
        robot.telemetry.last_drive_frame = [0.0, 0.5, 0.0]
        robot.telemetry.last_drive_ms = 1
        robot.telemetry.armed = True
        robot.telemetry.deadman_timeout_ms = 10 ** 15
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_TELEOP)
        self.assertLess(motors[0].effort, 0.0)
        self.assertGreater(motors[1].effort, 0.0)
        self.assertGreater(motors[2].effort, 0.0)
        self.assertLess(motors[3].effort, 0.0)

    def test_expired_drive_lease_neutralizes(self):
        server = self.server(deadman_timeout_ms=100)
        server.armed = True
        server.last_drive_frame = [0.5, 0.0, 0.0]
        server.last_drive_ms = -1000000
        self.assertIsNone(server.get_drive_frame())
        self.assertFalse(server.armed)

    def test_protocol_does_not_share_nt4_port(self):
        server = self.server()
        self.assertEqual(PROTOCOL, "ares-xrp/1")
        self.assertEqual(server.port, 5811)
        self.assertEqual(server.hello_payload()["projectId"], self.PROJECT_ID)
        self.assertEqual(server.hello_payload()["contentSha256"], self.CONTENT_SHA256)

    def test_protocol_rejects_ambiguous_start_command(self):
        server = self.server()
        server._recv_buffer = (
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":1,"requestRevision":1,"command":"START","armed":true,"driveFrame":[1,0,0]}\n'
        )
        server._process_buffer()
        self.assertEqual(server.get_command(), "")

    def test_connection_reset_releases_the_single_client_slot(self):
        class ResetSocket:
            def __init__(self):
                self.closed = False

            def recv(self, _size):
                raise OSError(104, "connection reset")

            def close(self):
                self.closed = True

        server = self.server()
        client = ResetSocket()
        server.server_socket = object()
        server.client_socket = client
        server.is_connected = True

        server.poll()

        self.assertFalse(server.is_connected)
        self.assertTrue(client.closed)
        self.assertIsNone(server.client_socket)

    def test_simulator_field_update_is_acknowledged_only_after_exact_apply(self):
        class CaptureSocket:
            def __init__(self):
                self.sent = []

            def send(self, payload):
                self.sent.append(payload.decode("utf-8"))

        payload = json.dumps({
            "id": "tabletop",
            "revision": 7,
            "widthMeters": 2.54,
            "heightMeters": 1.4224,
            "obstacles": [],
        }, separators=(",", ":"))
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        server = self.server()
        capture = CaptureSocket()
        server.client_socket = capture
        server.is_connected = True
        server.set_field_config_handler(lambda raw: {
            "configId": json.loads(raw)["id"],
            "revision": json.loads(raw)["revision"],
            "sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
            "obstacleCount": 0,
            "elementCount": 0,
            "aprilTagCount": 0,
        })
        server._recv_buffer = json.dumps({
            "protocol": PROTOCOL,
            "type": "fieldConfig",
            "configId": "tabletop",
            "revision": 7,
            "sha256": digest,
            "payload": payload,
        }) + "\n"

        server._process_buffer()

        receipt = json.loads(capture.sent[-1])
        self.assertEqual(receipt["type"], "fieldApplied")
        self.assertEqual(receipt["sha256"], digest)
        self.assertEqual(receipt["revision"], 7)

    def test_field_update_with_mismatched_identity_is_rejected(self):
        class CaptureSocket:
            def __init__(self):
                self.sent = []

            def send(self, payload):
                self.sent.append(payload.decode("utf-8"))

        server = self.server()
        capture = CaptureSocket()
        server.client_socket = capture
        server.is_connected = True
        server.set_field_config_handler(lambda _raw: {
            "configId": "different",
            "revision": 1,
            "sha256": "b" * 64,
        })
        server._recv_buffer = json.dumps({
            "protocol": PROTOCOL,
            "type": "fieldConfig",
            "configId": "expected",
            "revision": 1,
            "sha256": "a" * 64,
            "payload": "{}",
        }) + "\n"

        server._process_buffer()

        rejection = json.loads(capture.sent[-1])
        self.assertEqual(rejection["type"], "fieldRejected")
        self.assertIn("identity", rejection["message"])

    def test_explicit_auto_start_runs_registered_routine(self):
        output = self.MockDriveIo()
        robot = self.robot(drivetrain_type="differential", use_otos=False, drivetrain_io=output)
        robot.set_autonomous_routines(
            {"route": AutonomousRoutine(waypoints=[Waypoint(0.5, 0.0)])},
            default_id="route",
        )
        robot.telemetry.armed = True
        robot.telemetry.last_drive_frame = [0.0, 0.0, 0.0]
        robot.telemetry.last_drive_ms = 1
        robot.telemetry.deadman_timeout_ms = 10 ** 15
        robot.telemetry.last_command = "START_AUTO"
        robot.step(dt=0.02)
        self.assertEqual(robot.mode, XrpRobot.STATE_AUTO)
        self.assertGreater(output.last[0], 0.0)

    def test_non_finite_drive_frame_fails_closed(self):
        server = self.server()
        server._recv_buffer = (
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":1,"requestRevision":1,"command":"START_TELEOP","armed":true,"driveFrame":[1e999,0,0]}\n'
        )
        server._process_buffer()
        self.assertIsNone(server.get_drive_frame())
        self.assertFalse(server.armed)

    def test_mode_request_is_one_shot_while_drive_heartbeat_continues(self):
        server = self.server()
        server._recv_buffer = (
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":1,"requestRevision":7,"command":"START_AUTO","selectedOpMode":"route",'
            '"armed":true,"driveFrame":[0,0,0]}\n'
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":2,"requestRevision":7,"command":"START_AUTO","selectedOpMode":"route",'
            '"armed":true,"driveFrame":[0,0,0]}\n'
        )
        server._process_buffer()
        self.assertEqual(server.get_command(), "START_AUTO")
        self.assertEqual(server.get_command(), "")
        self.assertEqual(server.last_control_sequence, 2)
        self.assertTrue(server.armed)

    def test_missing_request_revision_fails_closed(self):
        server = self.server()
        server._recv_buffer = (
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":1,"command":"START_TELEOP","armed":true,"driveFrame":[1,0,0]}\n'
        )
        server._process_buffer()
        self.assertEqual(server.get_command(), "")
        self.assertFalse(server.armed)

    def test_reused_request_revision_cannot_change_mode(self):
        server = self.server()
        server._recv_buffer = (
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":1,"requestRevision":4,"command":"START_TELEOP",'
            '"armed":true,"driveFrame":[0.2,0,0]}\n'
            '{"protocol":"ares-xrp/1","type":"control","sessionId":"s",'
            '"sequence":2,"requestRevision":4,"command":"START_AUTO",'
            '"selectedOpMode":"route","armed":true,"driveFrame":[0.2,0,0]}\n'
        )
        server._process_buffer()
        self.assertEqual(server.get_command(), "START_TELEOP")
        self.assertFalse(server.armed)

    def test_failed_write_latches_until_successful_init_neutral(self):
        class FailingIo(self.MockDriveIo):
            def __init__(self):
                super().__init__()
                self.fail = True

            def set_effort(self, left, right):
                if self.fail:
                    raise OSError("write failed")
                super().set_effort(left, right)

        output = FailingIo()
        robot = self.robot(drivetrain_type="differential", use_otos=False, drivetrain_io=output)
        robot.telemetry.last_command = "START_TELEOP"
        robot.step()
        self.assertTrue(robot.faulted)
        self.assertEqual(robot.mode, XrpRobot.STATE_DISABLED)
        output.fail = False
        robot.telemetry.last_command = "INIT"
        robot.step()
        self.assertFalse(robot.faulted)
        self.assertEqual(robot.mode, XrpRobot.STATE_INIT)

    def test_brownout_fails_closed(self):
        output = self.MockDriveIo()
        robot = self.robot(
            drivetrain_type="differential",
            use_otos=False,
            drivetrain_io=output,
            brownout_threshold_volts=4.3,
            battery_voltage_supplier=lambda: 4.0,
        )
        robot.telemetry.last_command = "START_TELEOP"
        robot.step()
        self.assertTrue(robot.faulted)
        self.assertEqual(robot.mode, XrpRobot.STATE_DISABLED)
        self.assertEqual(output.last, (0.0, 0.0))

    def test_simulator_pose_constraint_is_applied_before_telemetry(self):
        output = self.MockDriveIo()
        robot = self.robot(
            drivetrain_type="differential",
            use_otos=False,
            drivetrain_io=output,
            pose_constraint=lambda previous, proposed: previous,
        )
        robot.drivetrain.update_odometry = lambda dt: setattr(robot.drivetrain, "x", 0.5)
        published = {}
        robot.telemetry.publish_pose_frame = lambda **values: published.update(values)

        robot.step()

        self.assertEqual(robot.drivetrain.x, 0.0)
        self.assertEqual(published["x"], 0.0)
        self.assertEqual(published["loop_time_ms"], 20.0)
        self.assertEqual(output.last, (0.0, 0.0))

    def test_otos_read_failure_latches_fault_and_neutralizes(self):
        class FailingI2C(MockI2C):
            def readfrom_mem(self, addr, reg, nbytes):
                if reg == SparkFunOTOS.REG_POS_X:
                    raise OSError("OTOS disconnected")
                return super().readfrom_mem(addr, reg, nbytes)

        output = self.MockDriveIo()
        robot = self.robot(
            drivetrain_type="differential",
            use_otos=True,
            i2c=FailingI2C(),
            drivetrain_io=output,
        )
        robot.telemetry.last_command = "START_TELEOP"
        robot.step()
        self.assertTrue(robot.faulted)
        self.assertEqual(robot.mode, XrpRobot.STATE_DISABLED)
        self.assertEqual(output.last, (0.0, 0.0))


class TestGeneratedXrpSubsystem(unittest.TestCase):
    def descriptor(self):
        return {
            "documentId": "arm",
            "stateFields": [
                {"fieldId": "target", "type": "DOUBLE", "role": "TARGET", "defaultNumber": 0.0, "minimum": -1.0, "maximum": 1.0},
                {"fieldId": "position", "type": "DOUBLE", "role": "MEASUREMENT", "defaultNumber": 0.0},
            ],
            "hardware": [
                {"hardwareId": "motor", "kind": "MOTOR", "safeOutput": 0.0, "measurements": [
                    {"fieldId": "position", "source": "MOTOR_POSITION_NATIVE", "scale": 1.0, "offset": 0.0}
                ]}
            ],
            "controlLoops": [
                {"loopId": "position", "strategy": "POSITION_PID", "actuatorId": "motor", "targetFieldId": "target", "measurementFieldId": "position", "kP": 2.0, "kI": 0.0, "kD": 0.0, "minimumOutput": -1.0, "maximumOutput": 1.0}
            ],
        }

    def test_pid_limits_and_safe_stop(self):
        device = MockXrpDevice({"MOTOR_POSITION_NATIVE": 0.25})
        subsystem = GeneratedXrpSubsystem(self.descriptor(), lambda _: device)
        subsystem.set_target("target", 0.75)
        subsystem.periodic(0.02)
        self.assertEqual(device.last_output, 1.0)
        subsystem.stop()
        self.assertEqual(device.last_output, 0.0)
        with self.assertRaises(ValueError):
            subsystem.set_target("target", 2.0)
        with self.assertRaises(ValueError):
            subsystem.set_target("target", float("nan"))

    def test_invalid_feedback_and_failed_write_latch_until_recovery(self):
        device = MockXrpDevice({"MOTOR_POSITION_NATIVE": float("nan")})
        subsystem = GeneratedXrpSubsystem(self.descriptor(), lambda _: device)
        subsystem.periodic()
        self.assertTrue(subsystem.faulted)
        device.readings["MOTOR_POSITION_NATIVE"] = 0.0
        self.assertTrue(subsystem.recover_neutral())
        device.fail_writes = True
        subsystem.periodic()
        self.assertTrue(subsystem.faulted)
        device.fail_writes = False
        self.assertTrue(subsystem.recover_neutral())

if __name__ == "__main__":
    unittest.main()
