import importlib.util
import hashlib
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class XrpSimulatorIntegrationTest(unittest.TestCase):
    def test_simulator_robot_accepts_leased_drive_and_updates_odometry(self):
        path = ROOT / "simulator" / "xrp_simulator.py"
        spec = importlib.util.spec_from_file_location("ares_xrp_simulator_test", path)
        simulator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(simulator)
        robot, motors = simulator.create_simulated_robot()

        robot.telemetry.get_command = lambda: "START_TELEOP"
        robot.telemetry.get_drive_frame = lambda: (0.4, 0.0, 0.0)
        robot.telemetry.poll = lambda: None
        robot.telemetry.publish_pose_frame = lambda **_values: None
        robot.telemetry.now_ms = lambda: 0
        robot.telemetry.elapsed_ms = lambda _started: 20
        robot.step(0.02)
        for motor in motors:
            motor.advance(0.1)
        robot.step(0.02)

        self.assertEqual(robot.mode, robot.STATE_TELEOP)
        self.assertGreater(robot.drivetrain.x, simulator.AUTONOMOUS_ROUTINES[simulator.DEFAULT_AUTONOMOUS_ID]["starting_pose"]["xMeters"])

    def test_simulator_robot_neutralizes_after_control_lease_loss(self):
        path = ROOT / "simulator" / "xrp_simulator.py"
        spec = importlib.util.spec_from_file_location("ares_xrp_simulator_lease_test", path)
        simulator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(simulator)
        robot, motors = simulator.create_simulated_robot()
        frames = iter(((0.5, 0.0, 0.0), None))

        robot.telemetry.get_command = lambda: "START_TELEOP"
        robot.telemetry.get_drive_frame = lambda: next(frames)
        robot.telemetry.poll = lambda: None
        robot.telemetry.publish_pose_frame = lambda **_values: None
        robot.telemetry.now_ms = lambda: 0
        robot.telemetry.elapsed_ms = lambda _started: 20
        robot.step(0.02)
        robot.step(0.02)

        self.assertTrue(all(motor.effort == 0.0 for motor in motors))

    def test_canonical_field_obstacle_stops_robot_before_penetration(self):
        module = load_simulator_module("ares_xrp_simulator_collision_test")
        with tempfile.TemporaryDirectory() as directory:
            field_path = pathlib.Path(directory) / "field.json"
            field_path.write_text(json.dumps({
                "widthMeters": 2.0,
                "heightMeters": 1.0,
                "obstacles": [{
                    "id": "block",
                    "shape": "rectangle",
                    "x": 0.25,
                    "y": 0.0,
                    "width": 0.20,
                    "height": 0.40,
                    "isBlocking": True,
                }],
            }), encoding="utf-8")
            collision = module.FieldCollisionConstraint(field_path, 0.16, 0.16)

            constrained = collision.constrain((0.0, 0.0, 0.0), (0.30, 0.0, 0.0))

            self.assertLess(constrained[0], 0.07)
            self.assertFalse(collision.collides(constrained))

    def test_canonical_field_reload_applies_editor_changes(self):
        module = load_simulator_module("ares_xrp_simulator_reload_test")
        with tempfile.TemporaryDirectory() as directory:
            field_path = pathlib.Path(directory) / "field.json"
            document = {"widthMeters": 2.0, "heightMeters": 1.0, "obstacles": []}
            field_path.write_text(json.dumps(document), encoding="utf-8")
            collision = module.FieldCollisionConstraint(field_path, 0.16, 0.16)
            self.assertFalse(collision.collides((0.0, 0.0, 0.0)))

            document["obstacles"] = [{
                "id": "new-block",
                "shape": "circle",
                "x": 0.0,
                "y": 0.0,
                "width": 0.20,
            }]
            field_path.write_text(json.dumps(document) + "\n", encoding="utf-8")
            collision._load(required=False)

            self.assertTrue(collision.collides((0.0, 0.0, 0.0)))

    def test_live_field_payload_returns_exact_application_receipt(self):
        module = load_simulator_module("ares_xrp_simulator_receipt_test")
        with tempfile.TemporaryDirectory() as directory:
            field_path = pathlib.Path(directory) / "field.json"
            field_path.write_text(json.dumps({
                "id": "initial",
                "revision": 1,
                "widthMeters": 2.0,
                "heightMeters": 1.0,
                "obstacles": [],
            }), encoding="utf-8")
            collision = module.FieldCollisionConstraint(field_path, 0.16, 0.16)
            payload = json.dumps({
                "id": "edited-tabletop",
                "revision": 9,
                "widthMeters": 2.54,
                "heightMeters": 1.4224,
                "obstacles": [{
                    "id": "block",
                    "shape": "rectangle",
                    "x": 0.4,
                    "y": -0.4,
                    "width": 0.2,
                    "height": 0.2,
                }],
                "elements": [],
                "apriltags": [],
            }, separators=(",", ":"))

            receipt = collision.apply_payload(payload)

            self.assertEqual(receipt["configId"], "edited-tabletop")
            self.assertEqual(receipt["revision"], 9)
            self.assertEqual(receipt["obstacleCount"], 1)
            self.assertEqual(
                receipt["sha256"],
                hashlib.sha256(payload.encode("utf-8")).hexdigest(),
            )
            self.assertTrue(collision.collides((0.4, -0.4, 0.0)))


def load_simulator_module(name):
    path = ROOT / "simulator" / "xrp_simulator.py"
    spec = importlib.util.spec_from_file_location(name, path)
    simulator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(simulator)
    return simulator


if __name__ == "__main__":
    unittest.main()
