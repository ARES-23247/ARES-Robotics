# SPDX-License-Identifier: Apache-2.0
"""Host-side rejection must precede generating an unusable autonomous program."""

import copy
import importlib.util
import pathlib
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("xrp_routine_audit_tool", ROOT / "tools/ares_project.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class RoutineValidationAuditTest(unittest.TestCase):
    def setUp(self):
        self.pose = {"xMeters": 0.25, "yMeters": -0.5, "headingRadians": 1.0}
        self.entry = {"entryId": "test", "routineId": "test", "enabled": True,
                      "startingPose": dict(self.pose)}
        self.catalog = {"defaultEntryId": "test", "entries": [self.entry]}
        self.routine = {"schemaVersion": 2, "documentId": "test", "steps": []}

    def select(self):
        def load(path):
            return copy.deepcopy(self.catalog if path.name == "autonomous-catalog.json" else self.routine)
        with mock.patch.object(tool, "load_json", side_effect=load):
            return tool.selected_routines({"test.action"})

    def test_wait_rejects_nonfinite_coercible_and_missing_durations(self):
        for value in (float("nan"), float("inf"), -float("inf"), -0.1, True, "1.5", None, 10 ** 400):
            with self.subTest(value=value):
                self.routine["steps"] = [{"kind": "WAIT", "durationSeconds": value}]
                with self.assertRaises(ValueError):
                    self.select()
        self.routine["steps"] = [{"kind": "WAIT"}]
        with self.assertRaises(ValueError):
            self.select()

    def test_wait_accepts_zero_and_preserves_fractional_seconds(self):
        self.routine["steps"] = [{"kind": "WAIT", "durationSeconds": value} for value in (0, 0.125)]
        _, routines = self.select()
        compiled = [tool.compile_routine_step(step, {}) for step in routines["test"]["steps"]]
        self.assertEqual([step["duration_seconds"] for step in compiled], [0.0, 0.125])

    def test_start_and_target_poses_require_finite_numeric_components(self):
        for location in ("start", "target"):
            for component in self.pose:
                for value in (float("nan"), float("inf"), True, "0.25", None):
                    with self.subTest(location=location, component=component, value=value):
                        invalid = dict(self.pose, **{component: value})
                        self.entry["startingPose"] = invalid if location == "start" else dict(self.pose)
                        self.routine["steps"] = [{"kind": "DRIVE_TO", "drive": {
                            "target": invalid if location == "target" else dict(self.pose)}}]
                        with self.assertRaises(ValueError):
                            self.select()

    def test_missing_pose_component_is_rejected_and_omitted_start_defaults_to_origin(self):
        self.entry["startingPose"] = {"xMeters": 0.0, "yMeters": 0.0}
        with self.assertRaises(ValueError):
            self.select()
        del self.entry["startingPose"]
        _, routines = self.select()
        self.assertEqual(routines["test"]["_startingPose"],
                         {"xMeters": 0.0, "yMeters": 0.0, "headingRadians": 0.0})

    def test_duplicate_enabled_entry_cannot_silently_replace_previous_routine(self):
        self.catalog["entries"].append(dict(self.entry))
        with self.assertRaisesRegex(ValueError, "[Dd]uplicate"):
            self.select()

    def test_drive_and_action_payloads_preserve_supported_values(self):
        self.routine["steps"] = [
            {"kind": "DRIVE_TO", "drive": {"target": dict(self.pose)}},
            {"kind": "ACTION", "actionKey": "test.action", "arguments": {"value": "0.125"}},
        ]
        default, routines = self.select()
        self.assertEqual(default, "test")
        drive, action = [tool.compile_routine_step(s, {"max_linear_speed_mps": 0.3})
                         for s in routines[default]["steps"]]
        self.assertEqual(drive["waypoint"], {"x": 0.25, "y": -0.5, "heading_rad": 1.0, "speed": 0.3})
        self.assertEqual(action, {"kind": "ACTION", "action_key": "test.action", "arguments": {"value": "0.125"}})

    def test_json_loader_rejects_nonstandard_and_overflowed_numbers(self):
        path = tool.ARES / "routines/test.aresroutine"
        for literal in ("NaN", "Infinity", "-Infinity", "1e999"):
            with self.subTest(literal=literal), mock.patch.object(pathlib.Path, "read_text", return_value='{"x":' + literal + '}'):
                with self.assertRaises(ValueError):
                    tool.load_json(path)
