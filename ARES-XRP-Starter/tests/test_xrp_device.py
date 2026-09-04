import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOL_PATH = ROOT / "deploy" / "xrp_device.py"
SPEC = importlib.util.spec_from_file_location("xrp_device", TOOL_PATH)
xrp_device = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(xrp_device)


class XrpDeviceTest(unittest.TestCase):
    def test_manifest_pins_every_supported_board_and_library(self):
        manifest = xrp_device.load_manifest()
        self.assertEqual(manifest["firmware"]["micropythonVersion"], "1.28.0")
        self.assertEqual(manifest["xrplib"]["version"], "2026.08.2")
        self.assertEqual(set(manifest["firmware"]["boards"]), {"xrp-2350", "xrp-beta"})
        self.assertEqual(manifest["firmware"]["boards"]["xrp-2350"]["servoChannels"], [1, 2, 3, 4])
        self.assertEqual(manifest["firmware"]["boards"]["xrp-beta"]["servoChannels"], [1, 2])
        for board in manifest["firmware"]["boards"].values():
            self.assertRegex(board["sha256"], r"^[0-9a-f]{64}$")
            self.assertGreater(board["sizeBytes"], 1_000_000)

    def test_canonical_project_selects_the_beta_controller_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / ".ares").mkdir()
            (root / ".ares" / "project.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 5,
                        "league": "XRP",
                        "runtimeOptions": {"xrp": {"controllerModel": "SPARKFUN_XRP_BETA_RP2040"}},
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.object(xrp_device, "ROOT", root):
                board_id, board = xrp_device.selected_board()
        self.assertEqual(board_id, "xrp-beta")
        self.assertEqual(board["motorChannels"], [1, 2, 3, 4])
        self.assertEqual(board["servoChannels"], [1, 2])

    def test_verified_download_rejects_wrong_identity_without_replacing_destination(self):
        payload = b"verified fixture"
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory) / "firmware.uf2"
            with mock.patch.object(xrp_device.urllib.request, "urlopen", return_value=mock.mock_open(read_data=payload)()) as urlopen:
                with self.assertRaisesRegex(xrp_device.DeviceError, "identity mismatch"):
                    xrp_device.verified_download("https://example.invalid/image", destination, len(payload), "0" * 64)
            urlopen.assert_called_once_with("https://example.invalid/image", timeout=30)
            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_suffix(".uf2.partial").exists())

    def test_verified_download_reuses_an_exact_cached_artifact(self):
        payload = b"verified fixture"
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory) / "firmware.uf2"
            destination.write_bytes(payload)
            with mock.patch.object(xrp_device.urllib.request, "urlopen") as urlopen:
                result = xrp_device.verified_download(
                    "https://example.invalid/image",
                    destination,
                    len(payload),
                    hashlib.sha256(payload).hexdigest(),
                )
            self.assertEqual(result, destination)
            urlopen.assert_not_called()

    def test_preflight_output_is_parsed_around_mpremote_noise(self):
        report = {"machine": "XRP RP2350", "micropython": "1.28.0"}
        output = "Connected to COM5\r\n" + xrp_device.PREFLIGHT_MARKER + json.dumps(report) + "\r\n>"
        self.assertEqual(xrp_device.parse_preflight_output(output), report)

    def test_preflight_reads_the_importable_official_xrplib_identity(self):
        script = xrp_device._preflight_script()
        self.assertIn("from XRPLib.version import __version__", script)
        self.assertNotIn("/lib/XRPLib", script)

    def test_preflight_detects_exact_pinned_board_without_mutation(self):
        report = {
            "machine": "XRP RP2350",
            "micropython": "1.28.0",
            "xrplib": "2026.08.2",
            "apis": {"drive": True, "encoders": True, "battery": True, "rangefinder": True, "imu": True, "motors": True},
            "capabilities": {"reflectance": True, "greenLed": True, "rgbLed": True, "genericIo": True, "buzzer": True},
            "ports": {"motors": [1, 2, 3, 4], "servos": [1, 2, 3, 4]},
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        with (
            mock.patch.object(xrp_device, "run_mpremote", return_value=completed),
            mock.patch.object(xrp_device, "required_project_capabilities", return_value=set()),
            mock.patch.object(xrp_device, "required_project_ports", return_value={"motors": set(), "servos": set()}),
        ):
            checked = xrp_device.preflight()
        self.assertTrue(checked["ready"])
        self.assertEqual(checked["detectedBoard"], "xrp-2350")

    def test_preflight_accepts_the_beta_controller_and_its_two_servo_ports(self):
        report = {
            "machine": "XRP Controller Beta with RP2040",
            "micropython": "1.28.0",
            "xrplib": "2026.08.2",
            "apis": {"drive": True, "encoders": True, "battery": True, "rangefinder": True, "imu": True, "motors": True},
            "capabilities": {"reflectance": True, "greenLed": True, "rgbLed": True, "genericIo": True, "buzzer": True},
            "ports": {"motors": [1, 2, 3, 4], "servos": [1, 2]},
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        beta = xrp_device.load_manifest()["firmware"]["boards"]["xrp-beta"]
        with (
            mock.patch.object(xrp_device, "selected_board", return_value=("xrp-beta", beta)),
            mock.patch.object(xrp_device, "run_mpremote", return_value=completed),
            mock.patch.object(xrp_device, "required_project_capabilities", return_value=set()),
            mock.patch.object(xrp_device, "required_project_ports", return_value={"motors": {1, 2}, "servos": {1, 2}}),
        ):
            checked = xrp_device.preflight()
        self.assertTrue(checked["ready"])
        self.assertEqual(checked["detectedBoard"], "xrp-beta")

    def test_preflight_fails_closed_on_stale_xrplib(self):
        report = {
            "machine": "XRP RP2350",
            "micropython": "1.28.0",
            "xrplib": "2025.1.0",
            "apis": {"drive": True, "encoders": True, "battery": True, "rangefinder": True, "imu": True, "motors": True},
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        with mock.patch.object(xrp_device, "run_mpremote", return_value=completed):
            with self.assertRaisesRegex(xrp_device.DeviceError, "XRPLib 2025.1.0"):
                xrp_device.preflight()

    def test_preflight_fails_when_project_requires_missing_board_capability(self):
        report = {
            "machine": "XRP RP2350",
            "micropython": "1.28.0",
            "xrplib": "2026.08.2",
            "apis": {"drive": True, "encoders": True, "battery": True, "rangefinder": True, "imu": True, "motors": True},
            "capabilities": {"buzzer": False},
            "ports": {"motors": [1, 2, 3, 4], "servos": [1, 2, 3, 4]},
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        with (
            mock.patch.object(xrp_device, "run_mpremote", return_value=completed),
            mock.patch.object(xrp_device, "required_project_capabilities", return_value={"buzzer"}),
            mock.patch.object(xrp_device, "required_project_ports", return_value={"motors": set(), "servos": set()}),
        ):
            with self.assertRaisesRegex(xrp_device.DeviceError, "buzzer"):
                xrp_device.preflight()

    def test_preflight_rejects_a_controller_that_does_not_match_canonical_project(self):
        report = {
            "machine": "XRP Controller Beta with RP2040",
            "micropython": "1.28.0",
            "xrplib": "2026.08.2",
            "apis": {"drive": True, "encoders": True, "battery": True, "rangefinder": True, "imu": True, "motors": True},
            "capabilities": {"reflectance": True, "greenLed": True, "rgbLed": True, "genericIo": True, "buzzer": True},
            "ports": {"motors": [1, 2, 3, 4], "servos": [1, 2]},
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        with (
            mock.patch.object(xrp_device, "run_mpremote", return_value=completed),
            mock.patch.object(xrp_device, "required_project_capabilities", return_value=set()),
            mock.patch.object(xrp_device, "required_project_ports", return_value={"motors": {1, 2}, "servos": set()}),
        ):
            with self.assertRaisesRegex(xrp_device.DeviceError, "does not match selected board xrp-2350"):
                xrp_device.preflight()

    def test_stage_manifest_is_tied_to_generated_content(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = pathlib.Path(directory) / "slot"
            stage.mkdir()
            with mock.patch.object(xrp_device, "ROOT", ROOT):
                xrp_device._stage_directory(stage, "a" * 64, "xrp-2350")
            identity = json.loads((stage / "ares-deployment.json").read_text(encoding="utf-8"))
            self.assertEqual(identity["contentSha256"], "a" * 64)
            self.assertEqual(identity["protocol"], "ares-xrp/1")
            self.assertEqual(identity["detectedBoard"], "xrp-2350")
            self.assertTrue((stage / "ares_micro" / "robot.py").is_file())

    def test_deployment_plan_compiles_staged_slot_without_device_mutation(self):
        plan = xrp_device.deployment_plan()
        self.assertFalse(plan["deviceMutation"])
        self.assertEqual(plan["evidence"], "Compiled successfully")
        self.assertIn("main.py", plan["pythonFiles"])
        self.assertIn("ares_micro/robot.py", plan["pythonFiles"])

    def test_deploy_installs_bootstrap_before_atomic_slot_activation(self):
        expected_count = len(xrp_device.deployment_plan()["pythonFiles"])
        calls = []

        def mpremote(arguments, capture=False):
            calls.append(arguments)
            stdout = f"ARES_SLOT_VALID={expected_count}\n" if capture else ""
            return mock.Mock(stdout=stdout)

        with (
            mock.patch.object(xrp_device, "preflight", return_value={"machine": "XRP RP2350", "detectedBoard": "xrp-2350"}),
            mock.patch.object(xrp_device, "run_mpremote", side_effect=mpremote),
        ):
            xrp_device.deploy()

        bootstrap_index = next(index for index, call in enumerate(calls) if call[:2] == ["fs", "cp"] and call[-1] == ":/main.py")
        activation_index = next(index for index, call in enumerate(calls) if call[0] == "exec" and "ares_active_slot.next" in call[1])
        self.assertLess(bootstrap_index, activation_index)
        self.assertEqual(calls[-1], ["reset"])


if __name__ == "__main__":
    unittest.main()
