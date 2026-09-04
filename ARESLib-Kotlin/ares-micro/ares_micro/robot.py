"""
ARES Micro - Central XrpRobot Facade for MicroPython
Coordinates hardware subsystems, localization, telemetry, and execution modes.
"""

from .drivetrain import DifferentialDrivetrain, MecanumDrivetrain
from .sparkfun_otos import SparkFunOTOS
from .telemetry import XrpTelemetryServer

class XrpRobot:
    STATE_DISABLED = "DISABLED"
    STATE_INIT = "INIT"
    STATE_AUTO = "AUTO"
    STATE_TELEOP = "TELEOP"

    def __init__(self, drivetrain_type="differential", use_otos=True, i2c=None):
        self.otos = SparkFunOTOS(i2c=i2c) if use_otos else None
        if self.otos and i2c is not None:
            self.otos.begin()

        if drivetrain_type.lower() == "mecanum":
            self.drivetrain = MecanumDrivetrain(otos=self.otos)
        else:
            self.drivetrain = DifferentialDrivetrain(otos=self.otos)

        self.telemetry = XrpTelemetryServer()
        self.mode = self.STATE_INIT
        self.active_routine = None

    def start_server(self):
        """Starts the wireless telemetry server for ARES Studio tethering."""
        return self.telemetry.start()

    def set_autonomous_routine(self, routine):
        self.active_routine = routine
        if self.active_routine:
            self.active_routine.reset()

    def step(self, dt=0.02):
        """Main robot cycle executing at 50Hz (20ms)."""
        # 1. Poll incoming network telemetry
        self.telemetry.poll()

        # 2. Check Driver Station commands from Studio
        cmd = self.telemetry.get_command()
        if cmd == "START":
            if self.active_routine and not self.active_routine.is_finished:
                self.mode = self.STATE_AUTO
            else:
                self.mode = self.STATE_TELEOP
        elif cmd == "STOP":
            self.mode = self.STATE_DISABLED
            self.drivetrain.stop()
        elif cmd == "INIT":
            self.mode = self.STATE_INIT
            self.drivetrain.stop()
            if self.active_routine:
                self.active_routine.reset()

        # 3. Update sensor odometry
        self.drivetrain.update_odometry(dt=dt)

        # 4. Mode-based execution
        if self.mode == self.STATE_AUTO:
            if self.active_routine:
                vx, omega, finished = self.active_routine.update(
                    self.drivetrain.x, self.drivetrain.y, self.drivetrain.heading
                )
                if finished:
                    self.drivetrain.stop()
                    self.mode = self.STATE_TELEOP # Auto finished, transition to teleop ready
                else:
                    self.drivetrain.drive(vx, omega)
            else:
                self.drivetrain.stop()

        elif self.mode == self.STATE_TELEOP:
            # Check for leased control frame from Studio driver station
            frame = self.telemetry.get_drive_frame()
            if frame and len(frame) >= 3:
                vx = frame[0]
                vy = frame[1]
                omega = frame[2]
                if isinstance(self.drivetrain, MecanumDrivetrain):
                    self.drivetrain.drive(vx, vy, omega)
                else:
                    self.drivetrain.drive(vx, omega)
            else:
                self.drivetrain.stop()

        elif self.mode == self.STATE_DISABLED:
            self.drivetrain.stop()

        # 5. Stream telemetry frame to Studio
        self.telemetry.publish_pose_frame(
            x=self.drivetrain.x,
            y=self.drivetrain.y,
            heading_rad=self.drivetrain.heading,
            mode=self.mode
        )
