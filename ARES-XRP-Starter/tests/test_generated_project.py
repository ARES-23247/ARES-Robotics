import importlib.util
import json
import pathlib
import sys
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
for candidate in (ROOT / "lib", ROOT.parent / "ARESLib-Kotlin" / "ares-micro"):
    if candidate.is_dir():
        sys.path.insert(0, str(candidate))


class GeneratedProjectTest(unittest.TestCase):
    def test_source_and_generated_suites_use_independent_discovery_roots(self):
        tool_path = ROOT / "tools" / "ares_project.py"
        spec = importlib.util.spec_from_file_location("ares_project_discovery_tool", tool_path)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)

        self.assertGreaterEqual(tool.discover_test_suites().countTestCases(), 8)

    def test_mecanum_requires_all_four_xrp_motor_ports(self):
        tool_path = ROOT / "tools" / "ares_project.py"
        spec = importlib.util.spec_from_file_location("ares_project_tool", tool_path)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        base = {
            "schemaVersion": 1,
            "platform": "XRP",
            "kind": "FTC_MECANUM",
            "safety": {"safeNeutralRequired": True, "explicitNeutralRecoveryRequired": True},
            "components": [
                {"role": "DRIVE_MOTOR", "hardwareId": str(port)} for port in (1, 2, 3, 4)
            ],
        }
        tool.validate_drivebase(base)
        with self.assertRaisesRegex(ValueError, "port 1, 2, 3, and 4"):
            tool.validate_drivebase({**base, "components": base["components"][:-1]})

    def test_mecanum_generation_preserves_four_explicit_ports_and_geometry(self):
        tool_path = ROOT / "tools" / "ares_project.py"
        spec = importlib.util.spec_from_file_location("ares_project_mecanum_tool", tool_path)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        project, drivebase, default_id, routines, subsystems = tool.validate()
        mecanum = json.loads(json.dumps(drivebase))
        mecanum["kind"] = "FTC_MECANUM"
        mecanum["geometry"]["trackWidthMeters"] = 0.18
        mecanum["geometry"]["wheelBaseMeters"] = 0.16
        mecanum["components"] = [
            {
                "uid": f"drive.motor.{port}",
                "displayName": f"Motor {port}",
                "role": "DRIVE_MOTOR",
                "hardwareId": str(port),
                "inverted": port in (2, 4),
            }
            for port in (1, 2, 3, 4)
        ]
        tool.validate_drivebase(mecanum)

        namespace = {}
        exec(tool.generated_source(project, mecanum, default_id, routines, subsystems), namespace)
        generated = namespace["PROJECT"]

        self.assertEqual(generated["drivetrain_type"], "mecanum")
        self.assertEqual([motor["port"] for motor in generated["drive_motors"]], [1, 2, 3, 4])
        self.assertEqual([motor["inverted"] for motor in generated["drive_motors"]], [False, True, False, True])
        self.assertEqual(generated["track_width_meters"], 0.18)
        self.assertEqual(generated["wheel_base_meters"], 0.16)

    def test_generated_project_matches_canonical_drivebase_and_has_fail_closed_link_settings(self):
        path = ROOT / "build" / "generated" / "ares" / "python" / "generated_ares_project.py"
        spec = importlib.util.spec_from_file_location("generated_ares_project", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        project = module.PROJECT
        descriptor_path = next((ROOT / ".ares" / "drivetrains").glob("*.aresdrivetrain"))
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        expected_type = "mecanum" if descriptor["kind"] == "FTC_MECANUM" else "differential"
        self.assertEqual(project["drivetrain_type"], expected_type)
        if expected_type == "mecanum":
            expected_ports = sorted(
                int(component["hardwareId"])
                for component in descriptor["components"]
                if component["role"] == "DRIVE_MOTOR"
            )
            self.assertEqual(sorted(motor["port"] for motor in project["drive_motors"]), expected_ports)
        self.assertNotEqual(project["link_port"], 5810)
        self.assertGreaterEqual(project["deadman_timeout_ms"], 100)
        self.assertLessEqual(project["deadman_timeout_ms"], 1000)

    def test_auto_builder_routine_is_compiled_to_python(self):
        path = ROOT / "build" / "generated" / "ares" / "python" / "generated_ares_project.py"
        spec = importlib.util.spec_from_file_location("generated_ares_project_auto", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        routines = module.create_autonomous_routines()
        routine = routines[module.DEFAULT_AUTONOMOUS_ID]
        self.assertIsNotNone(routine)
        self.assertEqual(routine.name, "Orbit preview")
        self.assertEqual([step["kind"] for step in routine.steps], ["DRIVE_TO", "DRIVE_TO"])
        self.assertAlmostEqual(module.AUTONOMOUS_ROUTINES[module.DEFAULT_AUTONOMOUS_ID]["starting_pose"]["xMeters"], -0.9)


if __name__ == "__main__":
    unittest.main()
