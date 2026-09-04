#!/usr/bin/env python3
"""Desktop XRP physics/link process launched by Studio."""

import pathlib
import signal
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build" / "generated" / "ares" / "python"))
sys.path.insert(0, str(ROOT / "lib"))
sys.path.insert(0, str(ROOT.parent / "ARESLib-Kotlin" / "ares-micro"))

from generated_ares_project import (
    AUTONOMOUS_ROUTINES,
    CONTENT_SHA256,
    DEFAULT_AUTONOMOUS_ID,
    PROJECT,
    create_autonomous_routines,
    create_subsystems,
)
from ares_micro import XrpRobot, mock_hardware_factory


class SimMotor:
    def __init__(self, max_speed_mps):
        self.max_speed_mps = max_speed_mps
        self.effort = 0.0
        self.distance_meters = 0.0

    def set_effort(self, effort):
        self.effort = max(-1.0, min(1.0, float(effort)))

    def advance(self, dt):
        self.distance_meters += self.effort * self.max_speed_mps * dt

    def get_position(self):
        wheel_radius = PROJECT["wheel_diameter_meters"] / 2.0
        return self.distance_meters / (2.0 * 3.141592653589793 * wheel_radius)


def main():
    motor_count = 4 if PROJECT["drivetrain_type"] == "mecanum" else 2
    motors = tuple(SimMotor(PROJECT["max_linear_speed_mps"]) for _ in range(motor_count))
    robot = XrpRobot(
        project_id=PROJECT["project_id"],
        content_sha256=CONTENT_SHA256,
        drivetrain_type=PROJECT["drivetrain_type"],
        use_otos=False,
        motors=motors,
        link_port=PROJECT["link_port"],
        deadman_timeout_ms=PROJECT["deadman_timeout_ms"],
        brownout_threshold_volts=PROJECT["brownout_threshold_volts"],
        battery_voltage_supplier=lambda: 6.0 - 0.6 * max(abs(motor.effort) for motor in motors),
        track_width=PROJECT["track_width_meters"],
        wheel_base=PROJECT["wheel_base_meters"],
        wheel_radius=PROJECT["wheel_diameter_meters"] / 2.0,
        max_linear_speed=PROJECT["max_linear_speed_mps"],
    )
    robot.set_subsystems(create_subsystems(mock_hardware_factory, simulation=True))
    robot.set_autonomous_routines(create_autonomous_routines(robot.handle_action), DEFAULT_AUTONOMOUS_ID)
    if DEFAULT_AUTONOMOUS_ID in AUTONOMOUS_ROUTINES:
        pose = AUTONOMOUS_ROUTINES[DEFAULT_AUTONOMOUS_ID]["starting_pose"]
        robot.drivetrain.reset_pose(pose["xMeters"], pose["yMeters"], pose["headingRadians"])
    if not robot.start_server():
        raise RuntimeError("XRP simulator could not bind its control link port")
    running = True

    def stop(*_):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, stop)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, stop)
    print("ARES XRP simulator ready on port", PROJECT["link_port"], flush=True)
    last = time.monotonic()
    while running:
        now = time.monotonic()
        dt = min(max(now - last, 0.0), 0.1)
        last = now
        for motor in motors:
            motor.advance(dt)
        robot.step(dt=dt)
        time.sleep(max(0.0, 0.02 - (time.monotonic() - now)))
    robot.drivetrain.stop()


if __name__ == "__main__":
    main()
