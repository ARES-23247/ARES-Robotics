#!/usr/bin/env python3
"""Deterministic project tool for a standalone ARES XRP repository."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARES = ROOT / ".ares"
GENERATED_ROOT = ROOT / "build" / "generated" / "ares"
GENERATED = GENERATED_ROOT / "python" / "generated_ares_project.py"
GENERATED_TESTS = GENERATED_ROOT / "tests"
TEST_RESULTS = ROOT / "build" / "test-results" / "test"


def load_json(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"{path.relative_to(ROOT)} is not valid JSON: {error}") from error


def one_document(directory: pathlib.Path, suffix: str) -> tuple[pathlib.Path, dict]:
    paths = sorted(directory.glob(f"*{suffix}"))
    if len(paths) != 1:
        raise ValueError(f"Expected exactly one {suffix} document in {directory.relative_to(ROOT)}")
    return paths[0], load_json(paths[0])


def selected_routines(action_keys: set[str]) -> tuple[str | None, dict[str, dict]]:
    catalog = load_json(ARES / "autonomous-catalog.json")
    default_id = catalog.get("defaultEntryId")
    routines = {}
    for entry in catalog.get("entries", []):
        if not entry.get("enabled"):
            continue
        entry_id = entry.get("entryId")
        routine_id = entry.get("routineId")
        if not entry_id or not routine_id:
            raise ValueError("Every enabled autonomous entry requires entryId and routineId")
        path = ARES / "routines" / f"{routine_id}.aresroutine"
        routine = load_json(path)
        if routine.get("schemaVersion") != 2 or routine.get("documentId") != routine_id:
            raise ValueError(f"{path.relative_to(ROOT)} does not match its catalog entry")
        for step in routine.get("steps", []):
            kind = step.get("kind")
            if kind not in ("DRIVE_TO", "WAIT", "ACTION"):
                raise ValueError(f"XRP routine {entry_id} contains unsupported step {kind}")
            if kind == "WAIT" and float(step.get("durationSeconds", -1.0)) < 0.0:
                raise ValueError(f"XRP routine {entry_id} has a negative wait")
            if kind == "ACTION" and step.get("actionKey") not in action_keys:
                raise ValueError(f"XRP routine action {step.get('actionKey')} is not declared")
        routine["_startingPose"] = entry.get("startingPose", {"xMeters": 0.0, "yMeters": 0.0, "headingRadians": 0.0})
        routines[entry_id] = routine
    if default_id is not None and default_id not in routines:
        raise ValueError("The default autonomous entry is missing or disabled")
    return default_id, routines


def load_subsystems() -> list[dict]:
    documents = []
    supported_hardware = {
        "MOTOR", "POSITIONAL_SERVO", "DIGITAL_INPUT", "DIGITAL_OUTPUT", "ANALOG_INPUT",
        "PWM_OUTPUT", "DISTANCE_SENSOR", "IMU", "INDICATOR_LIGHT", "BUZZER",
    }
    for path in sorted((ARES / "subsystems").glob("*.aressubsystem")):
        document = load_json(path)
        if document.get("schemaVersion") != 11 or document.get("platform") != "XRP":
            raise ValueError(f"{path.relative_to(ROOT)} must be a schema-11 XRP subsystem")
        implementation = document.get("implementation", {})
        if implementation.get("kind", "GENERATED_STARTER") == "HAND_AUTHORED":
            module_name = implementation.get("pythonModuleName")
            factory_name = implementation.get("pythonFactoryName")
            sources = implementation.get("sourceFiles", [])
            if not module_name or not factory_name or not sources:
                raise ValueError("Hand-authored XRP subsystems require explicit module, factory, and source metadata")
            for source in sources:
                source_path = ROOT / source
                if source_path.suffix != ".py" or not source_path.is_file() or ROOT not in source_path.resolve().parents:
                    raise ValueError(f"Registered XRP extension source is missing or unsafe: {source}")
            simulation = implementation.get("simulation", {})
            if simulation.get("support") in ("HAND_AUTHORED_MOCK", "HAND_AUTHORED_SIMULATOR") and not implementation.get("pythonSimulationFactoryName"):
                raise ValueError("Hand-authored XRP simulation support requires pythonSimulationFactoryName")
        for device in document.get("hardware", []):
            kind = device.get("kind")
            channel = device.get("connection", {}).get("channel")
            if kind not in supported_hardware:
                raise ValueError(f"XRP does not have a generated {kind} hardware adapter")
            if kind == "MOTOR" and channel not in (3, 4):
                raise ValueError("Generated XRP mechanism motors must use channel 3 or 4")
            if kind == "POSITIONAL_SERVO" and channel not in (1, 2, 3, 4):
                raise ValueError("Generated XRP servos must use channel 1..4")
            if kind == "DIGITAL_INPUT" and channel is not None and channel not in range(30):
                raise ValueError("Generated XRP digital inputs use GPIO 0..29")
            if kind in ("DIGITAL_OUTPUT", "PWM_OUTPUT") and channel not in range(30):
                raise ValueError("Generated XRP digital and PWM outputs use GPIO 0..29")
            measurement_sources = {item.get("source") for item in device.get("measurements", [])}
            is_reflectance = "REFLECTANCE_NORMALIZED" in measurement_sources
            if kind == "ANALOG_INPUT" and not is_reflectance and channel not in (26, 27, 28, 29):
                raise ValueError("Generated XRP analog inputs use GPIO 26..29 unless they are built-in reflectance sensors")
            if kind == "ANALOG_INPUT" and is_reflectance and channel not in (0, 1, 2):
                raise ValueError("Built-in XRP reflectance sensors use channel 0=left, 1=middle, or 2=right")
            if kind == "INDICATOR_LIGHT" and channel is not None and channel not in (0, 1, 2):
                raise ValueError("Generated XRP indicator lights use no channel for green or RGB component 0..2")
            if kind == "BUZZER" and channel is not None:
                raise ValueError("The built-in XRP buzzer has no selectable channel")
        safety = document.get("safety", {})
        if safety.get("requiresCurrentMonitoring"):
            raise ValueError("XRPLib does not expose motor current for generated safety checks")
        if safety.get("homing", {}).get("method", "NONE") != "NONE":
            raise ValueError("Generated XRP homing is not available yet")
        if safety.get("requiresCalibration"):
            raise ValueError("Generated XRP calibration is not available yet")
        if document.get("interlocks"):
            raise ValueError("Generated XRP cross-subsystem interlocks are not available yet")
        documents.append(document)
    return documents


def derived_subsystem_action_keys(subsystems: list[dict]) -> set[str]:
    keys = set()
    for subsystem in subsystems:
        subsystem_id = subsystem["documentId"]
        for field in subsystem.get("stateFields", []):
            if field.get("role") == "TARGET":
                keys.add(f"subsystem.{subsystem_id}.set.{field['fieldId']}")
        if subsystem.get("safety", {}).get("requiresExplicitNeutralRecovery"):
            keys.add(f"subsystem.{subsystem_id}.recover.neutral")
    return keys


def validate_drivebase(drivebase: dict) -> None:
    if drivebase.get("schemaVersion") != 1 or drivebase.get("platform") != "XRP":
        raise ValueError("The drivetrain must be a schema-1 XRP drivetrain")
    if drivebase.get("kind") not in ("DIFFERENTIAL", "FTC_MECANUM"):
        raise ValueError("XRP supports differential or four-motor mecanum drive")
    if drivebase.get("kind") == "FTC_MECANUM":
        motors = [item for item in drivebase.get("components", []) if item.get("role") == "DRIVE_MOTOR"]
        ports = {int(item.get("hardwareId", 0)) for item in motors}
        if len(motors) != 4 or ports != {1, 2, 3, 4}:
            raise ValueError("XRP mecanum requires exactly one drive motor on each port 1, 2, 3, and 4")
    if not drivebase.get("safety", {}).get("safeNeutralRequired"):
        raise ValueError("XRP drive must require safe neutral")
    if not drivebase.get("safety", {}).get("explicitNeutralRecoveryRequired"):
        raise ValueError("XRP drive must require explicit neutral recovery")


def validate() -> tuple[dict, dict, str | None, dict[str, dict], list[dict]]:
    project = load_json(ARES / "project.json")
    if project.get("schemaVersion") != 4 or project.get("league") != "XRP":
        raise ValueError(".ares/project.json must be schema 4 and league XRP")
    if project.get("coordinateConvention") != "CENTER_ORIGIN_CCW":
        raise ValueError("XRP projects use CENTER_ORIGIN_CCW coordinates")
    options = project.get("runtimeOptions", {}).get("xrp")
    if not isinstance(options, dict):
        raise ValueError("XRP runtime options are required")
    if options.get("port") == 5810:
        raise ValueError("XRP link must not reuse the NT4 port 5810")
    if options.get("wifiMode") not in ("AP", "STATION"):
        raise ValueError("XRP wifiMode must be AP or STATION")
    if not isinstance(options.get("ssid"), str) or not 1 <= len(options["ssid"]) <= 32:
        raise ValueError("XRP Wi-Fi SSID must contain 1..32 characters")
    if not 1024 <= int(options.get("port", 0)) <= 65535:
        raise ValueError("XRP port must be 1024..65535")
    if not 100 <= int(options.get("deadmanTimeoutMs", 0)) <= 1000:
        raise ValueError("XRP deadmanTimeoutMs must be 100..1000")
    brownout = options.get("brownoutThresholdVolts")
    if not isinstance(brownout, (int, float)) or not math.isfinite(float(brownout)) or not 3.0 <= float(brownout) <= 6.0:
        raise ValueError("XRP brownoutThresholdVolts must be finite and from 3.0 through 6.0")

    _, drivebase = one_document(ARES / "drivetrains", ".aresdrivetrain")
    validate_drivebase(drivebase)
    subsystems = load_subsystems()
    actions = load_json(ARES / "action-catalog.json")
    if actions.get("projectId") != project.get("projectId"):
        raise ValueError("The action catalog must use the canonical projectId")
    action_keys = {action.get("key") for action in actions.get("actions", [])} | derived_subsystem_action_keys(subsystems)
    for controls_path in sorted((ARES / "controls").glob("*.arescontrols")):
        controls = load_json(controls_path)
        if controls.get("schemaVersion") != 2:
            raise ValueError(f"{controls_path.relative_to(ROOT)} must use controls schema 2")
        for binding in controls.get("bindings", []):
            target = binding.get("target", {})
            if target.get("kind") == "ACTION" and target.get("key") not in action_keys:
                raise ValueError(f"Control action {target.get('key')} is not declared")
            if drivebase.get("kind") == "DIFFERENTIAL" and target.get("kind") == "DRIVE" and target.get("key") == "vy":
                raise ValueError("Differential XRP drive cannot bind a strafe axis")
    default_routine_id, routines = selected_routines(action_keys)
    return project, drivebase, default_routine_id, routines, subsystems


def generated_source(project: dict, drivebase: dict, default_routine_id: str | None, routines: dict[str, dict], subsystems: list[dict]) -> str:
    options = project["runtimeOptions"]["xrp"]
    device_identity = load_json(ROOT / "device" / "xrp-runtime-manifest.json")
    drivetrain_type = "mecanum" if drivebase["kind"] == "FTC_MECANUM" else "differential"
    values = {
        "project_id": project["projectId"],
        "drivetrain_type": drivetrain_type,
        "wifi_mode": options["wifiMode"],
        "wifi_ssid": options["ssid"],
        "link_port": options["port"],
        "deadman_timeout_ms": options["deadmanTimeoutMs"],
        "brownout_threshold_volts": options["brownoutThresholdVolts"],
        "robot_length_meters": project["robotLengthMeters"],
        "robot_width_meters": project["robotWidthMeters"],
        "drive_motors": [
            {
                "port": int(component["hardwareId"]),
                "inverted": bool(component.get("inverted", False)),
            }
            for component in drivebase.get("components", [])
            if component.get("role") == "DRIVE_MOTOR"
        ] if drivebase["kind"] == "FTC_MECANUM" else [],
        "use_otos": drivebase["localization"]["primaryOdometry"]["source"] == "SPARKFUN_OTOS",
        "track_width_meters": drivebase["geometry"]["trackWidthMeters"],
        "wheel_base_meters": drivebase["geometry"]["wheelBaseMeters"],
        "wheel_diameter_meters": drivebase["geometry"]["wheelDiameterMeters"],
        "max_linear_speed_mps": drivebase["geometry"]["maxLinearSpeedMetersPerSecond"],
        "max_angular_speed_radps": drivebase["geometry"]["maxAngularSpeedRadiansPerSecond"],
        "runtime_identity": {
            "micropythonVersion": device_identity["firmware"]["micropythonVersion"],
            "xrplibVersion": device_identity["xrplib"]["version"],
            "aresRuntimeVersion": device_identity["aresRuntime"]["starterVersion"],
        },
    }
    generated_routines = {}
    for entry_id, routine in routines.items():
        generated_routines[entry_id] = {
            "name": routine.get("name", entry_id),
            "starting_pose": routine["_startingPose"],
            "steps": [compile_routine_step(step, values) for step in routine.get("steps", [])],
        }
    content_hash = hashlib.sha256(
        json.dumps(
            {
                "project": project,
                "drivebase": drivebase,
                "routines": routines,
                "subsystems": subsystems,
            },
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    encoded = json.dumps(values, sort_keys=True, indent=4)
    return (
        '"""Generated from .ares documents. Do not edit by hand."""\n\n'
        "from ares_micro import AutonomousRoutine, GeneratedXrpSubsystem, Waypoint\n\n"
        f"CONTENT_SHA256 = {content_hash!r}\n"
        f"PROJECT = {encoded.replace('true', 'True').replace('false', 'False').replace('null', 'None')}\n"
        f"DEFAULT_AUTONOMOUS_ID = {repr(default_routine_id)}\n"
        f"AUTONOMOUS_ROUTINES = {repr(generated_routines)}\n"
        f"SUBSYSTEMS = {repr(subsystems)}\n\n"
        "def create_autonomous_routines(action_handler=None):\n"
        "    return {\n"
        "        entry_id: AutonomousRoutine(\n"
        "            name=definition['name'],\n"
        "            steps=[dict(step, waypoint=Waypoint(**step['waypoint'])) if step['kind'] == 'DRIVE_TO' else step for step in definition['steps']],\n"
        "            action_handler=action_handler,\n"
        "        )\n"
        "        for entry_id, definition in AUTONOMOUS_ROUTINES.items()\n"
        "    }\n\n"
        "def create_subsystems(hardware_factory, simulation=False):\n"
        "    instances = []\n"
        "    for descriptor in SUBSYSTEMS:\n"
        "        implementation = descriptor.get('implementation', {})\n"
        "        if implementation.get('kind') == 'HAND_AUTHORED':\n"
        "            module = __import__(implementation['pythonModuleName'], fromlist=['*'])\n"
        "            factory_name = implementation.get('pythonSimulationFactoryName') if simulation else implementation['pythonFactoryName']\n"
        "            if not factory_name:\n"
        "                raise ValueError('Registered XRP subsystem has no factory for this runtime')\n"
        "            instance = getattr(module, factory_name)(hardware_factory)\n"
        "            instance.document_id = descriptor['documentId']\n"
        "            instance.capability_action_keys = tuple(descriptor.get('capabilityActionKeys', ()))\n"
        "            instances.append(instance)\n"
        "        else:\n"
        "            instances.append(GeneratedXrpSubsystem(descriptor, hardware_factory))\n"
        "    return instances\n"
    )


def compile_routine_step(step: dict, values: dict) -> dict:
    kind = step["kind"]
    if kind == "DRIVE_TO":
        target = step["drive"]["target"]
        return {
            "kind": kind,
            "waypoint": {
                "x": target["xMeters"],
                "y": target["yMeters"],
                "heading_rad": target["headingRadians"],
                "speed": min(0.5, values["max_linear_speed_mps"]),
            },
        }
    if kind == "WAIT":
        return {"kind": kind, "duration_seconds": float(step["durationSeconds"])}
    return {"kind": kind, "action_key": step["actionKey"], "arguments": step.get("arguments", {})}


def generate(check: bool = False) -> None:
    project, drivebase, default_routine_id, routines, subsystems = validate()
    expected = generated_source(project, drivebase, default_routine_id, routines, subsystems)
    if check:
        actual = GENERATED.read_text(encoding="utf-8") if GENERATED.is_file() else None
        if actual != expected:
            raise ValueError("Generated XRP source is stale; run `ares generate`")
        test_path = GENERATED_TESTS / "test_generated_safety.py"
        actual_test = test_path.read_text(encoding="utf-8") if test_path.is_file() else None
        if actual_test != generated_test_source():
            raise ValueError("Generated XRP safety tests are stale; run `ares generate`")
        return
    GENERATED.parent.mkdir(parents=True, exist_ok=True)
    current_source = GENERATED.read_text(encoding="utf-8") if GENERATED.is_file() else None
    if current_source != expected:
        GENERATED.write_text(expected, encoding="utf-8", newline="\n")
    GENERATED_TESTS.mkdir(parents=True, exist_ok=True)
    generated_test = GENERATED_TESTS / "test_generated_safety.py"
    expected_test = generated_test_source()
    current_test = generated_test.read_text(encoding="utf-8") if generated_test.is_file() else None
    if current_test != expected_test:
        generated_test.write_text(expected_test, encoding="utf-8", newline="\n")
    print(f"Generated {GENERATED.relative_to(ROOT)}")


def generated_test_source() -> str:
    return '''"""Generated safety checks. Do not edit by hand."""

import unittest
from generated_ares_project import PROJECT, AUTONOMOUS_ROUTINES, DEFAULT_AUTONOMOUS_ID, create_subsystems
from ares_micro import mock_hardware_factory


class GeneratedSafetyTest(unittest.TestCase):
    def test_generated_project_identity_and_footprint_are_valid(self):
        from tools import ares_project
        project, _, _, _, _ = ares_project.validate()
        self.assertEqual(project["league"], "XRP")

    def test_generated_drivetrain_safety_contract_is_valid(self):
        from tools import ares_project
        _, drivebase, _, _, _ = ares_project.validate()
        ares_project.validate_drivebase(drivebase)

    def test_generated_controls_resolve_typed_project_targets(self):
        from tools import ares_project
        ares_project.validate()

    def test_generated_autonomous_graph_is_closed(self):
        from tools import ares_project
        _, _, default_id, routines, _ = ares_project.validate()
        if default_id is not None:
            self.assertIn(default_id, routines)

    def test_generated_superstructure_references_and_interlocks_are_valid(self):
        from pathlib import Path
        self.assertFalse(list((Path.cwd() / ".ares" / "superstructures").glob("*.aressuperstructure")),
                         "XRP superstructures are not a generated runtime capability")

    def test_fail_closed_link_policy(self):
        self.assertNotEqual(PROJECT["link_port"], 5810)
        self.assertGreaterEqual(PROJECT["deadman_timeout_ms"], 100)
        self.assertLessEqual(PROJECT["deadman_timeout_ms"], 1000)

    def test_default_autonomous_is_declared(self):
        if DEFAULT_AUTONOMOUS_ID is not None:
            self.assertIn(DEFAULT_AUTONOMOUS_ID, AUTONOMOUS_ROUTINES)

    def test_generated_subsystems_start_and_stop_neutral(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            self.assertFalse(subsystem.faulted)
            subsystem.stop()
            for declaration in subsystem.descriptor.get("hardware", []):
                neutral = declaration.get("safeOutput")
                if neutral is not None:
                    self.assertEqual(subsystem.devices[declaration["hardwareId"]].last_output, neutral)

    def test_generated_subsystems_fail_closed_on_invalid_feedback(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            measurements = [
                (device, measurement)
                for device in subsystem.descriptor.get("hardware", [])
                for measurement in device.get("measurements", [])
                if next(field for field in subsystem.descriptor["stateFields"] if field["fieldId"] == measurement["fieldId"])["type"] == "DOUBLE"
            ]
            if not measurements:
                continue
            device, measurement = measurements[0]
            source = measurement["source"]
            subsystem.devices[device["hardwareId"]].readings[source] = float("nan")
            subsystem.periodic()
            self.assertTrue(subsystem.faulted)

    def test_generated_subsystems_reject_failed_feedback_reads(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            measured = [
                device for device in subsystem.descriptor.get("hardware", [])
                if device.get("measurements")
            ]
            if not measured:
                continue
            subsystem.devices[measured[0]["hardwareId"]].fail_reads = True
            subsystem.periodic()
            self.assertTrue(subsystem.faulted)

    def test_generated_subsystems_latch_failed_writes(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            actuators = [
                device for device in subsystem.descriptor.get("hardware", [])
                if device.get("safeOutput") is not None
            ]
            if not actuators:
                continue
            subsystem.devices[actuators[0]["hardwareId"]].fail_writes = True
            subsystem.periodic()
            self.assertTrue(subsystem.faulted)

    def test_declared_target_limits_reject_out_of_range_values(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            for field in subsystem.descriptor.get("stateFields", []):
                if field.get("role") != "TARGET" or field.get("maximum") is None:
                    continue
                with self.assertRaises(ValueError):
                    subsystem.set_target(field["fieldId"], field["maximum"] + 1.0)

    def test_generated_subsystem_actions_update_state(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            for field in subsystem.descriptor.get("stateFields", []):
                if field.get("role") != "TARGET":
                    continue
                value = field.get("defaultNumber", field.get("defaultBoolean", field.get("defaultInt", field.get("defaultText"))))
                subsystem.set_target(field["fieldId"], value)
                self.assertEqual(subsystem.state[field["fieldId"]], value)

    def test_generated_subsystems_recover_only_after_successful_neutral(self):
        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):
            actuators = [device for device in subsystem.descriptor.get("hardware", []) if device.get("safeOutput") is not None]
            if not actuators:
                continue
            adapter = subsystem.devices[actuators[0]["hardwareId"]]
            adapter.fail_writes = True
            subsystem.periodic()
            self.assertFalse(subsystem.recover_neutral())
            adapter.fail_writes = False
            self.assertTrue(subsystem.recover_neutral())


if __name__ == "__main__":
    unittest.main()
'''


def test() -> None:
    # Generated sources and tests are disposable build outputs. A freshly cloned or
    # exported standalone project must therefore be verifiable before they exist.
    # `ares check` remains the explicit command for detecting stale generated files.
    if TEST_RESULTS.is_dir():
        for previous in TEST_RESULTS.glob("TEST-*.xml"):
            previous.unlink()
    generate()
    sys.path.insert(0, str(ROOT))
    sys.path.insert(0, str(GENERATED.parent))
    for candidate in (ROOT / "lib", ROOT.parent / "ARESLib-Kotlin" / "ares-micro"):
        if candidate.is_dir():
            sys.path.insert(0, str(candidate))
    for source in sorted(ROOT.rglob("*.py")):
        if "__pycache__" not in source.parts:
            compile(source.read_text(encoding="utf-8"), str(source), "exec")
    combined = discover_test_suites()
    result = unittest.TextTestRunner(verbosity=2, resultclass=RecordingTestResult).run(combined)
    write_junit_report(result)
    if not result.wasSuccessful():
        raise SystemExit(1)


class RecordingTestResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.successes = []

    def addSuccess(self, test):
        self.successes.append(test)
        super().addSuccess(test)


def write_junit_report(result) -> None:
    failures = {test.id(): detail for test, detail in result.failures}
    errors = {test.id(): detail for test, detail in result.errors}
    skipped = {test.id(): reason for test, reason in result.skipped}
    tests = list(result.successes) + [test for test, _ in result.failures + result.errors + result.skipped]
    suite = ET.Element("testsuite", {
        "name": "ares-xrp",
        "tests": str(len(tests)),
        "failures": str(len(failures)),
        "errors": str(len(errors)),
        "skipped": str(len(skipped)),
    })
    for test_case in sorted(tests, key=lambda item: item.id()):
        identity = test_case.id()
        case = ET.SubElement(suite, "testcase", {
            "classname": identity.rsplit(".", 1)[0],
            "name": getattr(test_case, "_testMethodName", identity),
        })
        if identity in failures:
            ET.SubElement(case, "failure", {"message": "assertion failed"}).text = failures[identity]
        elif identity in errors:
            ET.SubElement(case, "error", {"message": "test error"}).text = errors[identity]
        elif identity in skipped:
            ET.SubElement(case, "skipped", {"message": skipped[identity]})
    TEST_RESULTS.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(TEST_RESULTS / "TEST-ares-xrp.xml", encoding="utf-8", xml_declaration=True)


def discover_test_suites() -> unittest.TestSuite:
    # Each discovery root needs its own loader. Reusing defaultTestLoader leaks the first
    # top-level directory into the second discovery on Linux and makes generated tests appear
    # non-importable even though both directories contain valid standalone test modules.
    source_suite = unittest.TestLoader().discover(str(ROOT / "tests"))
    generated_suite = unittest.TestLoader().discover(str(GENERATED_TESTS))
    return unittest.TestSuite((source_suite, generated_suite))


def build() -> None:
    """Regenerate canonical plumbing and run the complete compile/safety verification."""
    test()


def simulate() -> None:
    build()
    command = [sys.executable, str(ROOT / "simulator" / "xrp_simulator.py")]
    raise SystemExit(subprocess.call(command, cwd=ROOT))


def deploy() -> None:
    build()
    command = [sys.executable, str(ROOT / "deploy" / "xrp_device.py"), "deploy"]
    raise SystemExit(subprocess.call(command, cwd=ROOT))


def device_command(command: str, extra: list[str]) -> None:
    raise SystemExit(subprocess.call([sys.executable, str(ROOT / "deploy" / "xrp_device.py"), command, *extra], cwd=ROOT))


def main() -> None:
    parser = argparse.ArgumentParser(prog="ares", description="ARES XRP project tool")
    parser.add_argument("command", choices=("generate", "check", "verify", "test", "build", "simulate", "deploy", "device-preflight", "prepare-image", "plan-deploy", "rollback"))
    parser.add_argument("args", nargs=argparse.REMAINDER)
    parsed = parser.parse_args()
    command = parsed.command
    if command == "generate":
        generate()
    elif command == "check":
        generate(check=True)
    elif command in ("verify", "test"):
        test()
    elif command == "build":
        build()
    elif command == "simulate":
        simulate()
    elif command == "deploy":
        deploy()
    elif command == "device-preflight":
        device_command("preflight", parsed.args)
    elif command == "prepare-image":
        device_command("prepare-image", parsed.args)
    elif command == "plan-deploy":
        build()
        device_command("plan-deploy", parsed.args)
    else:
        device_command("rollback", parsed.args)


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(f"ARES XRP verification failed: {error}", file=sys.stderr)
        raise SystemExit(2)
