"""
ARES XRP Starter - Pico W Main Entry Point
Runs automatically on power-on or boot on the Raspberry Pi Pico W.
Starts Wi-Fi AP / STA network and executes the 50Hz ARES robot cycle.
"""

import time
from ares_micro import XrpRobot, AutonomousRoutine, Waypoint

def init_wifi(ssid="ARES-XRP-23247", password="aresrobotics"):
    """Configures Pico W Wi-Fi in Access Point mode."""
    try:
        import network
        ap = network.WLAN(network.AP_IF)
        ap.config(essid=ssid, password=password)
        ap.active(True)
        while not ap.active():
            time.sleep(0.1)
        print("[Wi-Fi] Access Point active. SSID:", ssid, "IP:", ap.ifconfig()[0])
        return True
    except Exception as e:
        print("[Wi-Fi] Note: Running without network hardware:", e)
        return False

def main():
    print("=== ARES Robotics - XRP MicroPython Controller ===")
    init_wifi()

    # Create XRP robot with Differential drivetrain and OTOS odometry
    robot = XrpRobot(drivetrain_type="differential", use_otos=True)
    robot.start_server()

    # Pre-configure Orbit Odyssey sample autonomous routine:
    # 1. Start at Red Launch (0.35, 0.71)
    # 2. Drive to Red Rubble Zone (0.85, 0.50)
    # 3. Orbit pass Earth Pedestal (1.27, 1.20)
    orbit_routine = AutonomousRoutine(
        name="Orbit Odyssey Autonomous",
        waypoints=[
            Waypoint(x=0.35, y=0.7112, heading_rad=0.0, speed=0.4),
            Waypoint(x=0.85, y=0.50, heading_rad=0.0, speed=0.5),
            Waypoint(x=1.27, y=1.20, heading_rad=0.0, speed=0.5),
        ]
    )
    robot.set_autonomous_routine(orbit_routine)

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

if __name__ == "__main__":
    main()
