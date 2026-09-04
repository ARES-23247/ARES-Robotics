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

ROOT = pathlib.Path(__file__).resolve().parents[1]
ARES = ROOT / ".ares"
GENERATED_ROOT = ROOT / "build" / "generated" / "ares"
GENERATED = GENERATED_ROOT / "python" / "generated_ares_project.py"
GENERATED_TESTS = GENERATED_ROOT / "tests"


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
    supported_hardware = {"MOTOR", "POSITIONAL_SERVO", "DISTANCE_SENSOR", "IMU"}
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
        safety = document.get("safety", {})
        if safety.get("requiresCurrentMonitoring"):
            raise ValueError("XRPLib does not expose motor current for generated safety checks")
        if safety.get("homing", {}).get("method", "NONE") != "NONE":
            raise ValueError("Generated XRP homing is not available yet")
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
    drivetrain_type = "mecanum" if drivebase["kind"] == "FTC_MECANUM" else "differential"
    values = {
        "project_id": project["projectId"],
        "drivetrain_type": drivetrain_type,
        "wifi_mode": options["wifiMode"],
        "wifi_ssid": options["ssid"],
        "link_port": options["port"],
        "deadman_timeout_ms": options["deadmanTimeoutMs"],
        "brownout_threshold_volts": options["brownoutThresholdVolts"],
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
    GENERATED.write_text(expected, encoding="utf-8", newline="\n")
    GENERATED_TESTS.mkdir(parents=True, exist_ok=True)
    (GENERATED_TESTS / "test_generated_safety.py").write_text(generated_test_source(), encoding="utf-8", newline="\n")
    print(f"Generated {GENERATED.relative_to(ROOT)}")


def generated_test_source() -> str:
    return '''"""Generated safety checks. Do not edit by hand."""\n\nimport unittest\nfrom generated_ares_project import PROJECT, AUTONOMOUS_ROUTINES, DEFAULT_AUTONOMOUS_ID, create_subsystems\nfrom ares_micro import mock_hardware_factory\n\nclass GeneratedSafetyTest(unittest.TestCase):\n    def test_fail_closed_link_policy(self):\n        self.assertNotEqual(PROJECT["link_port"], 5810)\n        self.assertGreaterEqual(PROJECT["deadman_timeout_ms"], 100)\n        self.assertLessEqual(PROJECT["deadman_timeout_ms"], 1000)\n\n    def test_default_autonomous_is_declared(self):\n        if DEFAULT_AUTONOMOUS_ID is not None:\n            self.assertIn(DEFAULT_AUTONOMOUS_ID, AUTONOMOUS_ROUTINES)\n\n    def test_generated_subsystems_start_and_stop_neutral(self):\n        for subsystem in create_subsystems(mock_hardware_factory, simulation=True):\n            self.assertFalse(subsystem.faulted)\n            subsystem.stop()\n\nif __name__ == "__main__":\n    unittest.main()\n'''


def test() -> None:
    generate(check=True)
    sys.path.insert(0, str(GENERATED.parent))
    for candidate in (ROOT / "lib", ROOT.parent / "ARESLib-Kotlin" / "ares-micro"):
        if candidate.is_dir():
            sys.path.insert(0, str(candidate))
    for source in sorted(ROOT.rglob("*.py")):
        if "__pycache__" not in source.parts:
            compile(source.read_text(encoding="utf-8"), str(source), "exec")
    suite = unittest.defaultTestLoader.discover(str(ROOT / "tests"))
    generated_suite = unittest.defaultTestLoader.discover(str(GENERATED_TESTS))
    combined = unittest.TestSuite((suite, generated_suite))
    result = unittest.TextTestRunner(verbosity=2).run(combined)
    if not result.wasSuccessful():
        raise SystemExit(1)


def build() -> None:
    """Regenerate canonical plumbing and run the complete compile/safety verification."""
    generate()
    test()


def simulate() -> None:
    build()
    command = [sys.executable, str(ROOT / "simulator" / "xrp_simulator.py")]
    raise SystemExit(subprocess.call(command, cwd=ROOT))


def deploy() -> None:
    build()
    command = [sys.executable, str(ROOT / "deploy" / "deploy_to_pico.py")]
    raise SystemExit(subprocess.call(command, cwd=ROOT))


def main() -> None:
    parser = argparse.ArgumentParser(prog="ares", description="ARES XRP project tool")
    parser.add_argument("command", choices=("generate", "check", "verify", "test", "build", "simulate", "deploy"))
    command = parser.parse_args().command
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
    else:
        deploy()


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(f"ARES XRP verification failed: {error}", file=sys.stderr)
        raise SystemExit(2)
