#!/usr/bin/env python3
"""
ARES XRP Starter - Pico W Deployment Helper
Installs `ares_micro` to `/lib/ares_micro` on the connected Raspberry Pi Pico W,
and flashes `main.py` and autonomous assets to the root filesystem.
"""

import os
import sys
import subprocess

def find_ares_micro_path():
    bundled = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "lib", "ares_micro"))
    if os.path.isdir(bundled):
        return bundled
    # Check parent monorepo path first
    candidate = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "ARESLib-Kotlin", "ares-micro", "ares_micro"))
    if os.path.isdir(candidate):
        return candidate
    return None

def deploy():
    print("=== ARES Robotics - Deploying to Raspberry Pi Pico W ===")
    ares_micro = find_ares_micro_path()

    try:
        # Check mpremote
        subprocess.run(["mpremote", "connect", "list"], check=True, stdout=subprocess.PIPE)
    except Exception:
        print("[Error] `mpremote` command not found or Pico W not detected.")
        print("Please install mpremote (`pip install mpremote`) and plug in your Pico W via USB.")
        sys.exit(1)

    print("[Deploy] Creating /lib directory on Pico W...")
    subprocess.run(["mpremote", "mkdir", ":lib"], stderr=subprocess.DEVNULL)

    if ares_micro:
        print(f"[Deploy] Flashing ares_micro library from {ares_micro} -> :lib/ares_micro ...")
        subprocess.run(["mpremote", "cp", "-r", ares_micro, ":lib/ares_micro"], check=True)
    else:
        print("[Error] This project does not include its pinned ares_micro runtime.")
        print("Re-export it from ARES Robotics Studio or use a verified XRP starter archive.")
        sys.exit(2)

    main_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "main.py"))
    print(f"[Deploy] Flashing main.py -> :main.py ...")
    subprocess.run(["mpremote", "cp", main_script, ":main.py"], check=True)

    generated_script = os.path.abspath(os.path.join(
        os.path.dirname(__file__), "..", "build", "generated", "ares", "python", "generated_ares_project.py"
    ))
    print(f"[Deploy] Flashing generated project -> :generated_ares_project.py ...")
    subprocess.run(["mpremote", "cp", generated_script, ":generated_ares_project.py"], check=True)

    hardware_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "hardware.py"))
    print(f"[Deploy] Flashing XRPLib adapters -> :hardware.py ...")
    subprocess.run(["mpremote", "cp", hardware_script, ":hardware.py"], check=True)

    secrets_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "xrp_secrets.py"))
    if os.path.isfile(secrets_script):
        print("[Deploy] Flashing user-owned Wi-Fi secrets -> :xrp_secrets.py ...")
        subprocess.run(["mpremote", "cp", secrets_script, ":xrp_secrets.py"], check=True)

    print("[Deploy] Resetting Pico W...")
    subprocess.run(["mpremote", "reset"], check=True)
    print("=== Deployment Complete! Robot running main.py ===")

if __name__ == "__main__":
    deploy()
