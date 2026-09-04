"""
ARES XRP Starter - Pico W Main Entry Point
Runs automatically on power-on or boot on the Raspberry Pi Pico W.
Starts Wi-Fi AP / STA network and executes the 50Hz ARES robot cycle.
"""

import math
import time
from ares_micro import XrpRobot
from generated_ares_project import CONTENT_SHA256, PROJECT, DEFAULT_AUTONOMOUS_ID, create_autonomous_routines, create_subsystems
from hardware import create_otos_i2c, create_xrp_hardware, create_xrp_mecanum_motors

def _wifi_password(required):
    try:
        from xrp_secrets import WIFI_PASSWORD
        if WIFI_PASSWORD:
            return WIFI_PASSWORD
    except ImportError:
        pass
    if required:
        raise RuntimeError("STATION mode requires WIFI_PASSWORD in user-owned xrp_secrets.py")
    return "aresrobotics"


def init_wifi(mode, ssid):
    """Configures the declared Pico W access-point or station mode."""
    import network
    if mode == "AP":
        interface = network.WLAN(network.AP_IF)
        interface.config(essid=ssid, password=_wifi_password(False))
        interface.active(True)
        while not interface.active():
            time.sleep(0.1)
    elif mode == "STATION":
        interface = network.WLAN(network.STA_IF)
        interface.active(True)
        interface.connect(ssid, _wifi_password(True))
        remaining = 150
        while not interface.isconnected() and remaining > 0:
            remaining -= 1
            time.sleep(0.1)
        if not interface.isconnected():
            raise RuntimeError("Timed out joining the configured Wi-Fi network")
    else:
        raise RuntimeError("Unsupported generated Wi-Fi mode: " + str(mode))
    print("[Wi-Fi]", mode, "active. SSID:", ssid, "IP:", interface.ifconfig()[0])
    return True

def main():
    print("=== ARES Robotics - XRP MicroPython Controller ===")
    init_wifi(PROJECT["wifi_mode"], PROJECT["wifi_ssid"])

    try:
        from XRPLib.defaults import board as xrp_board, drivetrain as xrp_drivetrain, imu as xrp_imu
        from XRPLib.version import __version__ as xrplib_version
    except ImportError as error:
        raise RuntimeError("XRPLib is required on the XRP controller") from error

    mecanum_motors = None
    differential_io = xrp_drivetrain
    if PROJECT["drivetrain_type"] == "mecanum":
        mecanum_motors = create_xrp_mecanum_motors(PROJECT["drive_motors"])
        differential_io = None
    otos_i2c = None
    if PROJECT["use_otos"]:
        from machine import I2C, Pin
        machine_name = str(__import__("sys").implementation[2])
        otos_i2c = create_otos_i2c(machine_name, I2C, Pin)
    robot = XrpRobot(
        project_id=PROJECT["project_id"],
        content_sha256=CONTENT_SHA256,
        drivetrain_type=PROJECT["drivetrain_type"],
        use_otos=PROJECT["use_otos"],
        drivetrain_io=differential_io,
        motors=mecanum_motors,
        link_port=PROJECT["link_port"],
        deadman_timeout_ms=PROJECT["deadman_timeout_ms"],
        brownout_threshold_volts=PROJECT["brownout_threshold_volts"],
        battery_voltage_supplier=xrp_board.get_battery_voltage,
        heading_supplier=lambda: math.radians(xrp_imu.get_yaw()),
        i2c=otos_i2c,
        runtime_identity=dict(PROJECT["runtime_identity"], **{
            "boardType": str(__import__("sys").implementation[2]),
            "micropythonVersion": ".".join(str(__import__("sys").implementation[1][index]) for index in range(3)),
            "xrplibVersion": xrplib_version,
        }),
        track_width=PROJECT["track_width_meters"],
        wheel_base=PROJECT["wheel_base_meters"],
        wheel_radius=PROJECT["wheel_diameter_meters"] / 2.0,
        max_linear_speed=PROJECT["max_linear_speed_mps"],
    )
    try:
        robot.start_server()

        robot.set_subsystems(create_subsystems(create_xrp_hardware))
        robot.set_autonomous_routines(create_autonomous_routines(robot.handle_action), DEFAULT_AUTONOMOUS_ID)

        print("[Robot] Ready for ARES Studio Driver Station connection.")

        # 50Hz main loop (20ms)
        loop_period_sec = 0.02
        while True:
            start_time = time.time()
            robot.step(dt=loop_period_sec)
            elapsed = time.time() - start_time
            sleep_time = loop_period_sec - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)
    finally:
        robot.shutdown()

if __name__ == "__main__":
    main()
