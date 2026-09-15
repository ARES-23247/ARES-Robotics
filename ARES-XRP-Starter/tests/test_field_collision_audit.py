"""Field edits must be validated before replacing usable collision geometry."""

import importlib.util
import json
import math
import random
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SOURCE = Path(__file__).resolve().parents[1] / "simulator/field_collision.py"
SPEC = importlib.util.spec_from_file_location("field_collision_audit", SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FieldCollisionAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "field.json"
        self.document = {"widthMeters": 2.0, "heightMeters": 2.0, "obstacles": [
            {"shape": "rectangle", "x": 0.5, "width": 0.2, "height": 0.2}]}
        self.path.write_text(json.dumps(self.document), encoding="utf-8")
        self.collision = MODULE.FieldCollisionConstraint(self.path, 0.16, 0.16)

    def test_nonfinite_field_dimensions_reject_without_replacing_field(self):
        for value in (float("nan"), float("inf"), -1.0, 0.0):
            with self.subTest(value=value):
                document = dict(self.document, widthMeters=value)
                with self.assertRaises(ValueError):
                    self.collision.apply_payload(json.dumps(document))
                self.assertEqual(self.collision.width, 2.0)
                self.assertTrue(self.collision.collides((0.5, 0.0, 0.0)))

    def test_malformed_obstacles_reject_atomically(self):
        for obstacle in (
            {"shape": "rectangle", "width": "bad", "height": 0.2},
            {"shape": "rectangle", "width": 0.2, "height": 0.2, "rotation": float("inf")},
            {"shape": "circle", "width": -0.1},
            {"shape": "circle", "width": 0.1, "x": float("nan")},
            {"shape": "polygon", "points": [{"x": 0.0, "y": 0.0}]},
            {"shape": "triangle"},
        ):
            with self.subTest(obstacle=obstacle):
                with self.assertRaises(ValueError):
                    self.collision.apply_payload(json.dumps(dict(self.document, widthMeters=4, obstacles=[obstacle])))
                self.assertEqual(self.collision.width, 2.0)
                self.assertTrue(self.collision.collides((0.5, 0.0, 0.0)))

    def test_invalid_receipt_metadata_does_not_partially_install_field(self):
        with self.assertRaises(ValueError):
            self.collision.apply_payload(json.dumps(dict(self.document, widthMeters=4, elements=None)))
        self.assertEqual(self.collision.width, 2.0)

    def test_nonfinite_pose_is_blocked_and_invalid_robot_dimensions_rejected(self):
        for value in (float("nan"), float("inf"), -1.0, 0.0):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.FieldCollisionConstraint(self.path, value, 0.16)
        for pose in ((math.nan, 0, 0), (0, math.inf, 0), (0, 0, math.nan)):
            self.assertTrue(self.collision.collides(pose))

    def test_static_rectangle_geometry_is_built_only_when_field_changes(self):
        with mock.patch.object(MODULE, "_rectangle_corners", wraps=MODULE._rectangle_corners) as corners:
            for _ in range(10):
                self.assertFalse(self.collision.collides((0.0, 0.0, 0.0)))
            # One moving robot rectangle per query; the obstacle stays precomputed.
            self.assertEqual(corners.call_count, 10)

    def test_circle_radius_and_rotated_rectangle_preserve_canonical_units(self):
        self.collision.apply_payload(json.dumps(dict(self.document, obstacles=[
            {"shape": "circle", "x": 0.5, "width": 0.2}])))
        self.assertTrue(self.collision.collides((0.25, 0.0, 0.0)))
        self.assertFalse(self.collision.collides((0.20, 0.0, 0.0)))
        self.collision.apply_payload(json.dumps(dict(self.document, obstacles=[
            {"shape": "rectangle", "width": 0.8, "height": 0.1, "rotation": 90}])))
        self.assertTrue(self.collision.collides((0.0, 0.3, 0.0)))
        self.assertFalse(self.collision.collides((0.3, 0.0, 0.0)))

    def test_concave_polygon_keeps_its_open_notch(self):
        points = [{"x": x, "y": y} for x, y in (
            (-0.5, -0.5), (0.5, -0.5), (0.5, 0.5), (0.2, 0.5), (0.2, -0.2), (-0.5, -0.2))]
        self.collision.apply_payload(json.dumps(dict(self.document, obstacles=[{"shape": "polygon", "points": points}])))
        self.assertFalse(self.collision.collides((0.0, 0.2, 0.0)))
        self.assertTrue(self.collision.collides((0.35, 0.2, 0.0)))

    def test_translation_cannot_tunnel_through_obstacle_with_clear_endpoint(self):
        for obstacle in (
            {"shape": "rectangle", "width": 0.01, "height": 0.5},
            {"shape": "circle", "width": 0.01},
            {"shape": "polygon", "points": [{"x": x, "y": y} for x, y in (
                (-0.01, -0.25), (0.01, -0.25), (0.01, 0.25), (-0.01, 0.25))]},
        ):
            with self.subTest(shape=obstacle["shape"]):
                self.collision.apply_payload(json.dumps(dict(self.document, obstacles=[obstacle])))
                result = self.collision.constrain((-0.5, 0, 0), (0.5, 0, 0))
                self.assertLess(result[0], -0.08)
                self.assertGreater(result[0], -0.091)
                self.assertFalse(self.collision.collides(result))

    def test_rotation_cannot_sweep_through_obstacle_between_clear_endpoints(self):
        collision = MODULE.FieldCollisionConstraint(self.path, 0.8, 0.1)
        collision.apply_payload(json.dumps(dict(self.document, obstacles=[
            {"shape": "circle", "x": 0.28, "y": 0.28, "width": 0.02}])))
        self.assertFalse(collision.collides((0, 0, 0)))
        self.assertFalse(collision.collides((0, 0, math.pi / 2)))
        result = collision.constrain((0, 0, 0), (0, 0, math.pi / 2))
        self.assertGreater(result[2], 0.1)
        self.assertLess(result[2], math.pi / 4)
        self.assertFalse(collision.collides(result))

    def test_free_motion_keeps_exact_proposed_pose_and_short_heading_wrap(self):
        self.collision.apply_payload(json.dumps(dict(self.document, obstacles=[])))
        start = (0.0, 0.0, math.pi - 0.01)
        end = (0.4, 0.4, -math.pi + 0.01)
        self.assertEqual(self.collision.constrain(start, end), end)

    def test_rotating_footprint_cannot_cross_field_boundary_between_endpoints(self):
        collision = MODULE.FieldCollisionConstraint(self.path, 0.8, 0.1)
        collision.apply_payload(json.dumps(dict(self.document, widthMeters=0.78, obstacles=[])))
        start = (0, 0, -math.pi / 4)
        end = (0, 0, math.pi / 4)
        self.assertFalse(collision.collides(start))
        self.assertFalse(collision.collides(end))
        result = collision.constrain(start, end)
        self.assertLess(result[2], 0.0)
        self.assertFalse(collision.collides(result))

    def test_clear_rotation_near_obstacle_is_not_rejected_by_coarse_sweep(self):
        collision = MODULE.FieldCollisionConstraint(self.path, 0.8, 0.1)
        collision.apply_payload(json.dumps(dict(self.document, obstacles=[
            {"shape": "circle", "x": 0.32, "y": 0.32, "width": 0.02}])))
        self.assertEqual(collision.constrain((0, 0, 0), (0, 0, math.pi / 2)), (0, 0, math.pi / 2))

    def test_accepted_prefix_has_no_sampled_collision_during_combined_motion(self):
        rng = random.Random(23247)
        accepted = 0
        for _ in range(100):
            start = tuple(rng.uniform(-0.8, 0.8) for _ in range(2)) + (rng.uniform(-math.pi, math.pi),)
            end = tuple(rng.uniform(-0.8, 0.8) for _ in range(2)) + (rng.uniform(-math.pi, math.pi),)
            if self.collision.collides(start):
                continue
            result = self.collision.constrain(start, end)
            for step in range(101):
                pose = MODULE._interpolate_pose(start, result, step / 100)
                self.assertFalse(self.collision.collides(pose), (start, end, result, step))
            accepted += 1
        self.assertGreater(accepted, 80)

    def test_invalid_disk_reload_retains_last_valid_geometry(self):
        self.path.write_text(json.dumps(dict(self.document, widthMeters=4, obstacles=[
            {"shape": "rectangle", "width": "invalid", "height": 1}])), encoding="utf-8")
        self.collision._load(required=False)
        self.assertEqual(self.collision.width, 2.0)
        self.assertTrue(self.collision.collides((0.5, 0, 0)))

    def test_repeated_uncertain_sweep_stops_at_safe_pose_with_bounded_work(self):
        start = (0, 0, 0)
        with mock.patch.object(self.collision, "_sweep_collides", return_value=True) as query:
            self.assertEqual(self.collision.constrain(start, (0.2, 0, 0)), start)
            self.assertLessEqual(query.call_count, 256)


if __name__ == "__main__":
    unittest.main()
