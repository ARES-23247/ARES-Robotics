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
        self.assertEqual(set(manifest["firmware"]["boards"]), {"xrp-2350", "xrp-beta", "xrp-nano"})
        for board in manifest["firmware"]["boards"].values():
            self.assertRegex(board["sha256"], r"^[0-9a-f]{64}$")
            self.assertGreater(board["sizeBytes"], 1_000_000)

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
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        with (
            mock.patch.object(xrp_device, "run_mpremote", return_value=completed),
            mock.patch.object(xrp_device, "required_project_capabilities", return_value=set()),
        ):
            checked = xrp_device.preflight()
        self.assertTrue(checked["ready"])
        self.assertEqual(checked["detectedBoard"], "xrp-2350")

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
                xrp_device.preflight("xrp-2350")

    def test_preflight_fails_when_project_requires_missing_board_capability(self):
        report = {
            "machine": "XRP RP2350",
            "micropython": "1.28.0",
            "xrplib": "2026.08.2",
            "apis": {"drive": True, "encoders": True, "battery": True, "rangefinder": True, "imu": True, "motors": True},
            "capabilities": {"buzzer": False},
        }
        completed = mock.Mock(stdout=xrp_device.PREFLIGHT_MARKER + json.dumps(report))
        with (
            mock.patch.object(xrp_device, "run_mpremote", return_value=completed),
            mock.patch.object(xrp_device, "required_project_capabilities", return_value={"buzzer"}),
        ):
            with self.assertRaisesRegex(xrp_device.DeviceError, "buzzer"):
                xrp_device.preflight("xrp-2350")

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
            xrp_device.deploy("xrp-2350")

        bootstrap_index = next(index for index, call in enumerate(calls) if call[:2] == ["fs", "cp"] and call[-1] == ":/main.py")
        activation_index = next(index for index, call in enumerate(calls) if call[0] == "exec" and "ares_active_slot.next" in call[1])
        self.assertLess(bootstrap_index, activation_index)
        self.assertEqual(calls[-1], ["reset"])


if __name__ == "__main__":
    unittest.main()
