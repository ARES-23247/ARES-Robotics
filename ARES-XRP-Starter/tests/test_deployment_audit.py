"""Deployment integrity and recovery checks with no connected controller."""

import hashlib
import builtins
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import sys
import types
import unittest
from unittest import mock

from test_xrp_device import xrp_device, ROOT
from test_deployment_recovery import Device


class DeploymentAuditTest(unittest.TestCase):
    def valid_report(self):
        return {
            "machine": "XRP RP2350", "micropython": "1.28.0", "xrplib": "2026.08.2",
            "apis": {key: True for key in ("drive", "encoders", "battery", "rangefinder", "imu", "motors")},
            "capabilities": {"genericIo": True},
            "ports": {"motors": [1, 2, 3, 4], "servos": [1, 2, 3, 4]},
        }

    def check_report(self, report, capabilities=None):
        with mock.patch.object(xrp_device, "run_mpremote", return_value=mock.Mock(
                stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))), mock.patch.object(
                xrp_device, "required_project_capabilities", return_value=capabilities or set()), mock.patch.object(
                xrp_device, "required_project_ports", return_value={"motors": set(), "servos": set()}):
            return xrp_device.preflight()

    def test_preflight_requires_all_core_api_entries_and_boolean_evidence(self):
        for value in (None, "false", 1, False):
            with self.subTest(value=value):
                report = self.valid_report()
                if value is None:
                    del report["apis"]["battery"]
                else:
                    report["apis"]["battery"] = value
                with self.assertRaisesRegex(xrp_device.DeviceError, "battery"):
                    self.check_report(report)
        report = self.valid_report()
        report["capabilities"]["genericIo"] = "false"
        with self.assertRaisesRegex(xrp_device.DeviceError, "genericIo"):
            self.check_report(report, {"genericIo"})

    def test_plain_analog_input_requires_generic_io(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subsystems = root / ".ares/subsystems"
            subsystems.mkdir(parents=True)
            (subsystems / "sensor.aressubsystem").write_text(json.dumps({"hardware": [{
                "kind": "ANALOG_INPUT", "connection": {"channel": 26},
                "measurements": [{"source": "ANALOG_VOLTAGE"}]}]}))
            with mock.patch.object(xrp_device, "ROOT", root):
                self.assertEqual(xrp_device.required_project_capabilities(), {"genericIo"})

    def test_declared_imu_and_button_request_their_complete_capabilities(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subsystems = root / ".ares/subsystems"
            subsystems.mkdir(parents=True)
            (subsystems / "sensors.aressubsystem").write_text(json.dumps({"hardware": [
                {"kind": "IMU"}, {"kind": "DIGITAL_INPUT"}]}))
            with mock.patch.object(xrp_device, "ROOT", root):
                self.assertEqual(xrp_device.required_project_capabilities(), {"fullImu", "userButton"})

    def test_device_probe_requires_callable_apis_and_checks_each_port(self):
        def device(*names):
            return types.SimpleNamespace(**{name: lambda *args: None for name in names})
        defaults = types.ModuleType("XRPLib.defaults")
        defaults.board = device("get_battery_voltage", "is_button_pressed", "led_on", "led_off", "set_rgb_led")
        defaults.drivetrain = device("set_effort", "get_left_encoder_position", "get_right_encoder_position")
        for name in ("left_motor", "right_motor", "motor_three", "motor_four"):
            setattr(defaults, name, device("set_effort", "get_position", "get_speed"))
        for name in ("servo_one", "servo_two", "servo_three", "servo_four"):
            setattr(defaults, name, device("set_angle"))
        defaults.imu = device("get_yaw", "get_pitch", "get_roll", "get_gyro_x_rate", "get_gyro_y_rate",
                              "get_gyro_z_rate", "get_acc_x", "get_acc_y", "get_acc_z")
        defaults.rangefinder = device("distance")
        defaults.reflectance = device("get_left", "get_middle", "get_right")
        defaults.buzzer = device("play_note", "reset_buzzer")
        fake_sys = types.SimpleNamespace(implementation=("micropython", (1, 28, 0), "XRP RP2350"))
        importer = lambda name, *args: fake_sys if name == "sys" else builtins.__import__(name, *args)
        modules = {"XRPLib": types.ModuleType("XRPLib"), "XRPLib.defaults": defaults,
                   "XRPLib.version": types.SimpleNamespace(__version__="2026.08.2"),
                   "machine": types.SimpleNamespace(Pin=type, PWM=type, ADC=type)}
        with mock.patch.dict(sys.modules, modules):
            def probe():
                output = io.StringIO()
                with redirect_stdout(output):
                    exec(xrp_device._preflight_script(), {"__builtins__": dict(vars(builtins), __import__=importer)})
                return xrp_device.parse_preflight_output(output.getvalue())
            report = probe()
            self.assertEqual(report["ports"]["motors"], [1, 2, 3, 4])
            self.assertTrue(report["capabilities"].get("fullImu"))
            defaults.drivetrain.set_effort = None
            defaults.motor_four.get_position = None
            defaults.servo_three.set_angle = None
            defaults.imu.get_pitch = None
            report = probe()
            self.assertFalse(report["apis"]["drive"])
            self.assertFalse(report["capabilities"]["fullImu"])
            self.assertNotIn(4, report["ports"]["motors"])
            self.assertNotIn(3, report["ports"]["servos"])

    def test_stage_excludes_bytecode_and_keeps_runtime_and_extension_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "project"
            runtime = root / "lib/ares_micro"
            extension = root / "extensions"
            generated = root / "build/generated/ares/python"
            for folder in (runtime, extension, generated):
                folder.mkdir(parents=True)
            for folder in (runtime, extension):
                (folder / "source.py").write_text("pass\n")
                (folder / "old.pyc").write_bytes(b"irrelevant cache")
                (folder / "__pycache__").mkdir()
                (folder / "__pycache__/source.cpython.pyc").write_bytes(b"irrelevant cache")
            for path in (root / "main.py", root / "hardware.py", generated / "generated_ares_project.py"):
                path.write_text("pass\n")
            stage = Path(directory) / "stage"
            stage.mkdir()
            with mock.patch.object(xrp_device, "ROOT", root):
                xrp_device._stage_directory(stage, "a" * 64)
            self.assertTrue((stage / "ares_micro/source.py").is_file())
            self.assertTrue((stage / "extensions/source.py").is_file())
            self.assertEqual(list(stage.rglob("*.pyc")), [])
            self.assertEqual(list(stage.rglob("__pycache__")), [])

    def test_resealing_same_payload_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            (stage / "main.py").write_text("pass\n")
            first = xrp_device._seal_payload(stage)
            second = xrp_device._seal_payload(stage)
            self.assertEqual(first, second)
            self.assertNotIn("ares-files.json", json.loads((stage / "ares-files.json").read_text()))

    def test_plan_digest_matches_deployment_to_the_selected_board(self):
        plan = xrp_device.deployment_plan()
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            board, _ = xrp_device.selected_board()
            xrp_device._stage_directory(stage, xrp_device._content_sha(), board)
            self.assertEqual(plan["payloadSha256"], xrp_device._seal_payload(stage))

    def test_download_owns_its_temporary_file_and_keeps_other_attempts(self):
        payload = b"valid image"
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "firmware.uf2"
            other = destination.with_suffix(".uf2.partial")
            other.write_bytes(b"another operation owns this")
            with mock.patch.object(xrp_device.urllib.request, "urlopen", return_value=io.BytesIO(payload)):
                xrp_device.verified_download("https://example.invalid/image", destination, len(payload), hashlib.sha256(payload).hexdigest())
            self.assertEqual(destination.read_bytes(), payload)
            self.assertTrue(other.exists())
            self.assertEqual(other.read_bytes(), b"another operation owns this")

    def test_oversized_download_stops_after_expected_length_plus_one(self):
        class Response(io.BytesIO):
            consumed = 0
            def read(self, size=-1):
                chunk = super().read(size)
                self.consumed += len(chunk)
                return chunk
        response = Response(b"x" * 50000)
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "firmware.uf2"
            destination.write_bytes(b"old image")
            with mock.patch.object(xrp_device.urllib.request, "urlopen", return_value=response):
                with self.assertRaises(xrp_device.DeviceError):
                    xrp_device.verified_download("https://example.invalid/image", destination, 10, "0" * 64)
            self.assertLessEqual(response.consumed, 11)
            self.assertEqual(destination.read_bytes(), b"old image")
            self.assertEqual(sorted(path.name for path in Path(directory).iterdir()), ["firmware.uf2"])

    def test_validation_rejects_extra_files_and_matching_hash_but_invalid_python(self):
        for source, extra in ((b"pass", True), (b"def broken(", False)):
            with self.subTest(source=source, extra=extra), tempfile.TemporaryDirectory() as directory:
                stage = Path(directory)
                (stage / "main.py").write_bytes(source)
                digest = xrp_device._seal_payload(stage)
                device = Device()
                device.files["/ares_slots/slot-old/main.py"] = source
                device.files["/ares_slots/slot-old/ares-files.json"] = (stage / "ares-files.json").read_bytes()
                if extra:
                    device.files["/ares_slots/slot-old/extra.py"] = b"pass"
                with self.assertRaises((AssertionError, SyntaxError)):
                    device.execute(xrp_device._validation_script("/ares_slots/slot-old", digest))

    def test_activation_recovers_from_corrupt_current_without_losing_rollback(self):
        device = Device()
        device.files["/ares_slots/slot-old/main.py"] = b"\xff\xfe"
        device.files["/ares_slots/slot-new/main.py"] = b"pass"
        device.execute(xrp_device._activation_script("/ares_slots/slot-new"))
        self.assertEqual(device.boot_slot(), "/ares_slots/slot-new")
        self.assertEqual(device.files["/ares_active_slot.prev"], b"/ares_slots/slot-older")

    def test_failed_download_preserves_destination_and_removes_its_own_partial(self):
        class BrokenResponse(io.BytesIO):
            def read(self, size=-1):
                raise OSError("connection lost")
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "firmware.uf2"
            destination.write_bytes(b"existing")
            with mock.patch.object(xrp_device.urllib.request, "urlopen", return_value=BrokenResponse()):
                with self.assertRaises(OSError):
                    xrp_device.verified_download("https://example.invalid/image", destination, 12, "0" * 64)
            self.assertEqual(destination.read_bytes(), b"existing")
            self.assertEqual([p.name for p in Path(directory).iterdir()], ["firmware.uf2"])

    def test_preflight_parser_rejects_nonobject_and_malformed_receipts(self):
        for payload in ("[]", "null", "{bad"):
            with self.subTest(payload=payload), self.assertRaises(xrp_device.DeviceError):
                xrp_device.parse_preflight_output(xrp_device.PREFLIGHT_MARKER + payload)

    def test_every_deployment_mutation_keeps_an_installed_bootable_slot(self):
        from test_deployment_recovery import PowerLoss
        launcher = (ROOT / "deploy/ares_boot.py").read_bytes()

        def create_device(cut=None):
            device = Device(cut)
            device.files["/main.py"] = launcher
            return device

        def stage(directory, *_):
            (directory / "main.py").write_text("pass\n")
            (directory / "module.py").write_text("value = 42\n")

        def deploy(device):
            with mock.patch.object(xrp_device, "preflight", return_value={"machine": "fake"}), mock.patch.object(
                    xrp_device, "_content_sha", return_value="a" * 64), mock.patch.object(
                    xrp_device, "_stage_directory", side_effect=stage), mock.patch.object(
                    xrp_device, "run_mpremote", side_effect=device.mpremote):
                xrp_device.deploy()

        complete = create_device()
        deploy(complete)
        self.assertGreater(complete.ops, 15)
        for cut in range(1, complete.ops + 1):
            with self.subTest(cut=cut):
                device = create_device(cut)
                with self.assertRaises(PowerLoss):
                    deploy(device)
                # Execute the launcher actually left on the simulated device,
                # not a fresh host copy that could hide an interrupted update.
                boot = device.execute(device.files["/main.py"].decode())
                slot = boot["_active_slot"]()
                self.assertEqual(device.files[slot + "/main.py"].strip(), b"pass")
                self.assertEqual(device.files["/ares_slots/slot-old/main.py"], b"pass")


class BootAuditTest(unittest.TestCase):
    def program(self, device):
        source = ROOT / "deploy/ares_boot.py"
        return device.execute(source.read_text(), str(source))

    def test_corrupt_utf8_active_slot_falls_back_before_any_execution(self):
        device = Device()
        device.files["/ares_slots/slot-old/main.py"] = b"\xff\xfe"
        self.assertEqual(device.boot_slot(), "/ares_slots/slot-older")

    def test_boot_reads_and_compiles_selected_program_once(self):
        device = Device()
        program = self.program(device)
        with mock.patch.object(device, "open", wraps=device.open) as reader:
            program["__builtins__"]["open"] = reader
            with mock.patch("sys.path", []):
                program["_run"]()
        self.assertEqual(sum(call.args[0] == "/ares_slots/slot-old/main.py" for call in reader.call_args_list), 1)

    def test_program_failure_never_executes_rollback_program(self):
        device = Device()
        device.files["/ares_slots/slot-old/main.py"] = b"raise RuntimeError('active fault')"
        device.files["/ares_slots/slot-older/main.py"] = b"raise AssertionError('must not run')"
        program = self.program(device)
        with mock.patch("sys.path", []), self.assertRaisesRegex(RuntimeError, "active fault"):
            program["_run"]()

    def test_selected_slot_is_first_even_if_it_was_already_on_import_path(self):
        device = Device()
        program = self.program(device)
        with mock.patch("sys.path", ["/lib", "/ares_slots/slot-old"]):
            program["_run"]()
            self.assertEqual(sys.path[0], "/ares_slots/slot-old")
            self.assertEqual(sys.path.count("/ares_slots/slot-old"), 1)


if __name__ == "__main__":
    unittest.main()
