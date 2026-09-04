import importlib.util
import json
import pathlib
import sys
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
for candidate in (ROOT / "lib", ROOT.parent / "ARESLib-Kotlin" / "ares-micro"):
    if candidate.is_dir():
        sys.path.insert(0, str(candidate))


class GeneratedProjectTest(unittest.TestCase):
    def test_stock_xrp_input_and_light_adapters_use_public_xrplib_boundaries(self):
        hardware_path = ROOT / "hardware.py"
        spec = importlib.util.spec_from_file_location("ares_xrp_hardware", hardware_path)
        hardware = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hardware)

        digital = hardware._DigitalInputAdapter(lambda: True)
        analog = hardware._AnalogInputAdapter(lambda: 0.625)
        self.assertTrue(digital.read("DIGITAL_STATE"))
        self.assertEqual(analog.read("ANALOG_VOLTAGE"), 0.625)

        class Board:
            def __init__(self):
                self.green = False
                self.rgb = None

            def led_on(self): self.green = True
            def led_off(self): self.green = False
            def set_rgb_led(self, red, green, blue): self.rgb = (red, green, blue)

        board = Board()
        hardware._IndicatorLightAdapter(board).write(1.0)
        self.assertTrue(board.green)
        hardware._IndicatorLightAdapter(board, 0).write(0.5)
        hardware._IndicatorLightAdapter(board, 2).write(1.0)
        self.assertEqual(board.rgb, (128, 0, 255))

    def test_stock_xrp_output_buzzer_and_full_imu_adapters(self):
        hardware_path = ROOT / "hardware.py"
        spec = importlib.util.spec_from_file_location("ares_xrp_hardware_outputs", hardware_path)
        hardware = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(hardware)

        class Pin:
            def __init__(self): self.value_written = None
            def value(self, value): self.value_written = value

        class Pwm:
            def __init__(self): self.duty = None
            def duty_u16(self, value): self.duty = value

        pin, pwm = Pin(), Pwm()
        hardware._DigitalOutputAdapter(pin).write(0.75)
        hardware._PwmOutputAdapter(pwm).write(0.25)
        self.assertEqual(pin.value_written, 1)
        self.assertEqual(pwm.duty, 16384)

        class Buzzer:
            def __init__(self): self.notes, self.reset = [], 0
            def play_note(self, note, duration, blocking): self.notes.append((note, duration, blocking))
            def reset_buzzer(self): self.reset += 1

        buzzer = Buzzer()
        adapter = hardware._BuzzerAdapter(buzzer)
        adapter.write(69)
        adapter.write(69)
        adapter.write(0)
        self.assertEqual(buzzer.notes, [("A4", "quarter", False)])
        self.assertEqual(buzzer.reset, 1)

        class Imu:
            def get_yaw(self): return 90.0
            def get_pitch(self): return 30.0
            def get_roll(self): return -15.0
            def get_gyro_x_rate(self): return 1000.0
            def get_gyro_y_rate(self): return -2000.0
            def get_gyro_z_rate(self): return 500.0
            def get_acc_x(self): return 1000.0
            def get_acc_y(self): return 0.0
            def get_acc_z(self): return -1000.0

        imu = hardware._ImuAdapter(Imu())
        self.assertAlmostEqual(imu.read("IMU_PITCH_RADIANS"), 0.5235987756)
        self.assertAlmostEqual(imu.read("IMU_GYRO_X_RADIANS_PER_SECOND"), 0.0174532925)
        self.assertAlmostEqual(imu.read("IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED"), -9.80665)
    def test_verify_regenerates_disposable_outputs_before_running_tests(self):
        tool_path = ROOT / "tools" / "ares_project.py"
        spec = importlib.util.spec_from_file_location("ares_project_verify_tool", tool_path)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        empty_suite = unittest.TestSuite()

        with (
            mock.patch.object(tool, "generate") as generate,
            mock.patch.object(tool, "discover_test_suites", return_value=empty_suite),
            mock.patch.object(tool.unittest, "TextTestRunner") as runner,
        ):
            runner.return_value.run.return_value.wasSuccessful.return_value = True
            tool.test()

        generate.assert_called_once_with()

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
