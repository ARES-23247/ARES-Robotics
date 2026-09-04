#!/usr/bin/env python3
"""Pinned XRP image preparation, fail-closed preflight, and A/B deployment."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "device" / "xrp-runtime-manifest.json"
PREFLIGHT_MARKER = "ARES_XRP_PREFLIGHT="


class DeviceError(RuntimeError):
    pass


def load_manifest() -> dict:
    document = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 2:
        raise DeviceError("Unsupported XRP runtime manifest")
    return document


def selected_board() -> tuple[str, dict]:
    project = json.loads((ROOT / ".ares" / "project.json").read_text(encoding="utf-8"))
    if project.get("schemaVersion") != 5 or project.get("league") != "XRP":
        raise DeviceError("Canonical project metadata must be schema 5 and league XRP")
    controller_model = project.get("runtimeOptions", {}).get("xrp", {}).get("controllerModel")
    matches = [
        (board_id, board) for board_id, board in load_manifest()["firmware"]["boards"].items()
        if board.get("controllerModel") == controller_model
    ]
    if len(matches) != 1:
        raise DeviceError("Canonical project metadata must select one supported SparkFun XRP controller")
    return matches[0]


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verified_download(url: str, destination: pathlib.Path, expected_size: int, expected_sha256: str) -> pathlib.Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and destination.stat().st_size == expected_size and sha256_file(destination) == expected_sha256:
        return destination
    partial = destination.with_suffix(destination.suffix + ".partial")
    try:
        with urllib.request.urlopen(url, timeout=30) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output)
        actual_size = partial.stat().st_size
        actual_sha256 = sha256_file(partial)
        if actual_size != expected_size or actual_sha256 != expected_sha256:
            raise DeviceError(
                f"Downloaded image identity mismatch: expected {expected_size} bytes/{expected_sha256}, "
                f"received {actual_size} bytes/{actual_sha256}"
            )
        partial.replace(destination)
        return destination
    finally:
        partial.unlink(missing_ok=True)


def prepare_image(output: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    manifest = load_manifest()
    board, board_manifest = selected_board()
    firmware = verified_download(
        board_manifest["url"],
        output / f"ares-xrp-{board}-{manifest['firmware']['release']}.uf2",
        board_manifest["sizeBytes"],
        board_manifest["sha256"],
    )
    xrplib_manifest = manifest["xrplib"]
    xrplib = verified_download(
        xrplib_manifest["archiveUrl"],
        output / f"xrplib-{xrplib_manifest['release']}.zip",
        xrplib_manifest["sizeBytes"],
        xrplib_manifest["sha256"],
    )
    return firmware, xrplib


def run_mpremote(arguments: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["mpremote", "connect", "auto", *arguments],
            check=True,
            text=True,
            capture_output=capture,
        )
    except FileNotFoundError as error:
        raise DeviceError("mpremote is not installed; install it with `python -m pip install mpremote`") from error
    except subprocess.CalledProcessError as error:
        detail = (error.stderr or error.stdout or "mpremote command failed").strip()
        raise DeviceError(detail) from error


def _preflight_script() -> str:
    return """import json, sys
result = {'machine': str(sys.implementation[2]), 'micropython': '.'.join(str(sys.implementation[1][i]) for i in range(3)), 'xrplib': '', 'apis': {}}
try:
    from XRPLib.version import __version__ as xrplib_version
    result['xrplib'] = str(xrplib_version)
except Exception:
    pass
try:
    import XRPLib.defaults as defaults
    board, drivetrain, left_motor, right_motor, rangefinder, imu, reflectance = defaults.board, defaults.drivetrain, defaults.left_motor, defaults.right_motor, defaults.rangefinder, defaults.imu, defaults.reflectance
    result['boardType'] = str(board.get_type()) if hasattr(board, 'get_type') else ''
    result['apis']['drive'] = hasattr(drivetrain, 'set_effort')
    result['apis']['encoders'] = hasattr(drivetrain, 'get_left_encoder_position') and hasattr(drivetrain, 'get_right_encoder_position')
    result['apis']['battery'] = hasattr(board, 'get_battery_voltage')
    result['apis']['rangefinder'] = hasattr(rangefinder, 'distance')
    result['apis']['imu'] = hasattr(imu, 'get_yaw') and hasattr(imu, 'get_gyro_z_rate')
    result['apis']['motors'] = hasattr(left_motor, 'set_effort') and hasattr(right_motor, 'set_effort')
    result['capabilities'] = {
        'reflectance': all(hasattr(reflectance, name) for name in ('get_left', 'get_middle', 'get_right')),
        'greenLed': hasattr(board, 'led_on') and hasattr(board, 'led_off'),
        'rgbLed': hasattr(board, 'set_rgb_led'),
        'genericIo': False,
        'buzzer': False,
    }
    result['ports'] = {
        'motors': [number for number, name in ((1, 'left_motor'), (2, 'right_motor'), (3, 'motor_three'), (4, 'motor_four')) if getattr(defaults, name, None) is not None],
        'servos': [number for number, name in ((1, 'servo_one'), (2, 'servo_two'), (3, 'servo_three'), (4, 'servo_four')) if getattr(defaults, name, None) is not None],
    }
    try:
        from XRPLib.defaults import buzzer
        result['capabilities']['buzzer'] = hasattr(buzzer, 'play_note') and hasattr(buzzer, 'reset_buzzer')
    except ImportError:
        pass
    try:
        from machine import Pin, PWM, ADC
        result['capabilities']['genericIo'] = all(item is not None for item in (Pin, PWM, ADC))
    except ImportError:
        pass
except Exception as error:
    result['importError'] = repr(error)
print('ARES_XRP_PREFLIGHT=' + json.dumps(result))
"""


def parse_preflight_output(output: str) -> dict:
    for line in reversed(output.splitlines()):
        if PREFLIGHT_MARKER in line:
            return json.loads(line.split(PREFLIGHT_MARKER, 1)[1].strip())
    raise DeviceError("Connected device did not return an ARES XRP preflight report")


def required_project_capabilities() -> set[str]:
    required = set()
    for path in (ROOT / ".ares" / "subsystems").glob("*.aressubsystem"):
        document = json.loads(path.read_text(encoding="utf-8"))
        for device in document.get("hardware", []):
            kind = device.get("kind")
            channel = device.get("connection", {}).get("channel")
            measurement_sources = {item.get("source") for item in device.get("measurements", [])}
            if kind == "ANALOG_INPUT" and "REFLECTANCE_NORMALIZED" in measurement_sources:
                required.add("reflectance")
            elif kind == "INDICATOR_LIGHT":
                required.add("greenLed" if channel is None else "rgbLed")
            elif kind == "BUZZER":
                required.add("buzzer")
            elif kind in ("DIGITAL_INPUT", "DIGITAL_OUTPUT", "PWM_OUTPUT") and channel is not None:
                required.add("genericIo")
    return required


def required_project_ports() -> dict[str, set[int]]:
    required = {"motors": set(), "servos": set()}
    for path in (ROOT / ".ares" / "drivetrains").glob("*.aresdrivetrain"):
        document = json.loads(path.read_text(encoding="utf-8"))
        for component in document.get("components", []):
            if component.get("role") == "DRIVE_MOTOR":
                hardware_id = str(component["hardwareId"])
                required["motors"].add(
                    {"left": 1, "right": 2}[hardware_id]
                    if hardware_id in ("left", "right")
                    else int(hardware_id)
                )
    for path in (ROOT / ".ares" / "subsystems").glob("*.aressubsystem"):
        document = json.loads(path.read_text(encoding="utf-8"))
        for device in document.get("hardware", []):
            channel = device.get("connection", {}).get("channel")
            if channel is None:
                continue
            if device.get("kind") == "MOTOR":
                required["motors"].add(int(channel))
            elif device.get("kind") == "POSITIONAL_SERVO":
                required["servos"].add(int(channel))
    return required


def preflight() -> dict:
    manifest = load_manifest()
    expected_board, board = selected_board()
    report = parse_preflight_output(run_mpremote(["exec", _preflight_script()], capture=True).stdout)
    problems = []
    if report.get("micropython") != manifest["firmware"]["micropythonVersion"]:
        problems.append(f"MicroPython {report.get('micropython') or 'unknown'} is installed; ARES requires {manifest['firmware']['micropythonVersion']}")
    if report.get("xrplib") != manifest["xrplib"]["version"]:
        problems.append(f"XRPLib {report.get('xrplib') or 'missing'} is installed; ARES requires {manifest['xrplib']['version']}")
    if report.get("importError"):
        problems.append(f"XRPLib import failed: {report['importError']}")
    missing_apis = sorted(name for name, present in report.get("apis", {}).items() if not present)
    if missing_apis:
        problems.append("XRPLib is missing required APIs: " + ", ".join(missing_apis))
    missing_capabilities = sorted(
        name for name in required_project_capabilities()
        if not report.get("capabilities", {}).get(name, False)
    )
    if missing_capabilities:
        problems.append("The selected project requires unavailable board capabilities: " + ", ".join(missing_capabilities))
    available_ports = {name: set(values) for name, values in report.get("ports", {}).items()}
    for kind, expected in (("motors", board["motorChannels"]), ("servos", board["servoChannels"])):
        actual = available_ports.get(kind, set())
        if actual != set(expected):
            problems.append(
                f"Connected controller exposes {kind} {sorted(actual)}, but {board['controllerModel']} requires {expected}"
            )
    for kind, required in required_project_ports().items():
        missing = sorted(required - available_ports.get(kind, set()))
        if missing:
            problems.append(f"The project requires unavailable {kind} ports: {', '.join(map(str, missing))}")
    machine = report.get("machine", "")
    detected = [
        board_id for board_id, board in manifest["firmware"]["boards"].items()
        if board["machineContains"] in machine and not (board.get("machineExcludes") and board["machineExcludes"] in machine)
    ]
    if len(detected) != 1:
        problems.append(f"Connected machine {machine!r} did not match exactly one pinned XRP board")
    else:
        report["detectedBoard"] = detected[0]
    if detected != [expected_board]:
        problems.append(f"Connected machine {machine!r} does not match selected board {expected_board}")
    report["ready"] = not problems
    report["problems"] = problems
    if problems:
        raise DeviceError("XRP preflight failed:\n- " + "\n- ".join(problems))
    return report


def _content_sha() -> str:
    generated = ROOT / "build" / "generated" / "ares" / "python" / "generated_ares_project.py"
    for line in generated.read_text(encoding="utf-8").splitlines():
        if line.startswith("CONTENT_SHA256 = "):
            return line.split("=", 1)[1].strip().strip("'\"")
    raise DeviceError("Generated project does not contain CONTENT_SHA256")


def _stage_directory(slot: pathlib.Path, content_sha256: str, detected_board: str | None = None) -> None:
    runtime = ROOT / "lib" / "ares_micro"
    if not runtime.is_dir():
        runtime = ROOT.parent / "ARESLib-Kotlin" / "ares-micro" / "ares_micro"
    if not runtime.is_dir():
        raise DeviceError("Pinned ares_micro runtime is missing; re-export this project from Studio")
    shutil.copytree(runtime, slot / "ares_micro")
    for source, destination in (
        (ROOT / "main.py", slot / "main.py"),
        (ROOT / "hardware.py", slot / "hardware.py"),
        (ROOT / "build" / "generated" / "ares" / "python" / "generated_ares_project.py", slot / "generated_ares_project.py"),
    ):
        shutil.copy2(source, destination)
    extensions = ROOT / "extensions"
    if extensions.is_dir():
        shutil.copytree(extensions, slot / "extensions")
    secrets = ROOT / "xrp_secrets.py"
    if secrets.is_file():
        shutil.copy2(secrets, slot / "xrp_secrets.py")
    identity = load_manifest()
    (slot / "ares-deployment.json").write_text(
        json.dumps({
            "schemaVersion": 1,
            "contentSha256": content_sha256,
            "protocol": identity["aresRuntime"]["protocol"],
            "micropythonVersion": identity["firmware"]["micropythonVersion"],
            "xrplibVersion": identity["xrplib"]["version"],
            "detectedBoard": detected_board,
        }, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def deploy() -> str:
    report = preflight()
    content_sha256 = _content_sha()
    slot_name = "slot-" + content_sha256[:16]
    remote_slot = "/ares_slots/" + slot_name
    with tempfile.TemporaryDirectory(prefix="ares-xrp-deploy-") as temporary:
        stage = pathlib.Path(temporary) / slot_name
        stage.mkdir()
        _stage_directory(stage, content_sha256, report.get("detectedBoard"))
        run_mpremote(["exec", "import os\ntry:\n os.mkdir('/ares_slots')\nexcept OSError:\n pass"])
        run_mpremote(["exec", f"import os\ndef rm(p):\n try:\n  mode=os.stat(p)[0]\n except OSError:\n  return\n if mode & 0x4000:\n  for item in os.ilistdir(p): rm(p+'/'+item[0])\n  os.rmdir(p)\n else: os.remove(p)\nrm('{remote_slot}')"])
        run_mpremote(["fs", "cp", "-r", str(stage), ":/ares_slots"])
        validation = (
            f"import os\nroot='{remote_slot}'\ncount=0\n"
            "def check(path):\n global count\n for item in os.ilistdir(path):\n  child=path+'/'+item[0]\n  if item[1] & 0x4000: check(child)\n  elif child.endswith('.py'):\n   with open(child,'r') as f: compile(f.read(),child,'exec')\n   count += 1\n"
            "check(root)\nprint('ARES_SLOT_VALID=' + str(count))\n"
        )
        result = run_mpremote(["exec", validation], capture=True)
        expected_python_files = len(tuple(stage.rglob("*.py")))
        validation_counts = [
            int(line.split("ARES_SLOT_VALID=", 1)[1].strip())
            for line in result.stdout.splitlines()
            if "ARES_SLOT_VALID=" in line
        ]
        if validation_counts != [expected_python_files]:
            raise DeviceError("The staged ARES slot could not be verified; the previous slot remains active")
        # The stable launcher is installed before changing the active marker. A
        # first deployment can therefore never activate an unreachable slot.
        run_mpremote(["fs", "cp", str(ROOT / "deploy" / "ares_boot.py"), ":/main.py"])
        activate = (
            f"import os\nnext_slot='{remote_slot}'\n"
            "try:\n os.remove('/ares_active_slot.prev')\nexcept OSError:\n pass\n"
            "try:\n os.rename('/ares_active_slot.txt','/ares_active_slot.prev')\nexcept OSError:\n pass\n"
            "with open('/ares_active_slot.next','w') as f: f.write(next_slot)\n"
            "os.rename('/ares_active_slot.next','/ares_active_slot.txt')\n"
        )
        run_mpremote(["exec", activate])
        run_mpremote(["reset"])
    return f"Deployed {slot_name} to {report.get('machine', 'XRP')}"


def deployment_plan() -> dict:
    content_sha256 = _content_sha()
    with tempfile.TemporaryDirectory(prefix="ares-xrp-plan-") as temporary:
        stage = pathlib.Path(temporary) / ("slot-" + content_sha256[:16])
        stage.mkdir()
        _stage_directory(stage, content_sha256)
        python_files = sorted(str(path.relative_to(stage)).replace("\\", "/") for path in stage.rglob("*.py"))
        for path in stage.rglob("*.py"):
            compile(path.read_text(encoding="utf-8"), str(path), "exec")
    return {
        "contentSha256": content_sha256,
        "slot": "slot-" + content_sha256[:16],
        "pythonFiles": python_files,
        "deviceMutation": False,
        "evidence": "Compiled successfully",
    }


def rollback() -> None:
    script = """import os
with open('/ares_active_slot.prev','r') as f: previous=f.read().strip()
with open('/ares_active_slot.txt','r') as f: current=f.read().strip()
with open('/ares_active_slot.next','w') as f: f.write(previous)
os.rename('/ares_active_slot.next','/ares_active_slot.txt')
with open('/ares_active_slot.prev','w') as f: f.write(current)
"""
    run_mpremote(["exec", script])
    run_mpremote(["reset"])


def main() -> None:
    parser = argparse.ArgumentParser(description="ARES-managed XRP device tools")
    subparsers = parser.add_subparsers(dest="command", required=True)
    image = subparsers.add_parser("prepare-image", help="download and verify the official firmware/XRPLib image")
    image.add_argument("--output", type=pathlib.Path, default=ROOT / "build" / "device-image")
    subparsers.add_parser("preflight", help="verify the connected controller selected by .ares/project.json without changing it")
    subparsers.add_parser("deploy", help="verify and deploy into a recoverable A/B slot")
    subparsers.add_parser("rollback", help="reactivate the previously deployed ARES slot")
    subparsers.add_parser("plan-deploy", help="stage and compile a deployment without connecting to hardware")
    args = parser.parse_args()
    if args.command == "prepare-image":
        firmware, xrplib = prepare_image(args.output)
        print(f"Verified firmware: {firmware}")
        print(f"Verified XRPLib: {xrplib}")
    elif args.command == "preflight":
        print(json.dumps(preflight(), indent=2, sort_keys=True))
    elif args.command == "deploy":
        print(deploy())
    elif args.command == "plan-deploy":
        print(json.dumps(deployment_plan(), indent=2, sort_keys=True))
    else:
        rollback()
        print("Reactivated the previous ARES deployment slot")


if __name__ == "__main__":
    try:
        main()
    except DeviceError as error:
        print(f"ARES XRP device operation failed: {error}", file=sys.stderr)
        raise SystemExit(2)
