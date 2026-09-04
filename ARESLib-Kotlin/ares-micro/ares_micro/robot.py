"""
ARES Micro - Central XrpRobot Facade for MicroPython
Coordinates hardware subsystems, localization, telemetry, and execution modes.
"""

import math

from .drivetrain import DifferentialDrivetrain, MecanumDrivetrain
from .sparkfun_otos import SparkFunOTOS
from .telemetry import XrpTelemetryServer

class XrpRobot:
    STATE_DISABLED = "DISABLED"
    STATE_INIT = "INIT"
    STATE_AUTO = "AUTO"
    STATE_TELEOP = "TELEOP"

    def __init__(self, project_id, content_sha256, drivetrain_type="differential", use_otos=False, i2c=None,
                 drivetrain_io=None, motors=None, link_port=5811,
                 deadman_timeout_ms=200, brownout_threshold_volts=4.3,
                 battery_voltage_supplier=None, track_width=0.155,
                 wheel_base=0.140, wheel_radius=0.030, max_linear_speed=0.85,
                 heading_supplier=None, runtime_identity=None, pose_constraint=None):
        if use_otos and i2c is None:
            raise ValueError("SPARKFUN_OTOS localization requires an explicit physical I2C bus")
        self.otos = SparkFunOTOS(i2c=i2c) if use_otos else None
        if self.otos and not self.otos.begin():
            raise ValueError("SPARKFUN_OTOS was selected but did not answer on the configured I2C bus")

        if drivetrain_type.lower() == "mecanum":
            motors = motors or ()
            self.drivetrain = MecanumDrivetrain(
                *motors, track_width=track_width, wheel_base=wheel_base,
                wheel_radius=wheel_radius, max_speed=max_linear_speed, otos=self.otos,
                heading_supplier=heading_supplier,
            )
        else:
            motors = motors or (None, None)
            self.drivetrain = DifferentialDrivetrain(
                left_motor=motors[0], right_motor=motors[1],
                drivetrain_io=drivetrain_io, track_width=track_width,
                wheel_radius=wheel_radius, max_speed=max_linear_speed, otos=self.otos,
                heading_supplier=heading_supplier,
            )

        if not self.drivetrain.has_output():
            raise ValueError("XRP drivetrain requires explicit physical motor output")
        self.telemetry = XrpTelemetryServer(
            project_id=project_id,
            content_sha256=content_sha256,
            drivetrain_type=drivetrain_type.lower(),
            port=link_port,
            deadman_timeout_ms=deadman_timeout_ms,
            runtime_identity=runtime_identity,
        )
        self.mode = self.STATE_INIT
        self.active_routine = None
        self.autonomous_routines = {}
        self.default_autonomous_id = None
        self.faulted = False
        self.subsystems = []
        self.brownout_threshold_volts = float(brownout_threshold_volts)
        self.battery_voltage_supplier = battery_voltage_supplier or (lambda: 6.0)
        self.pose_constraint = pose_constraint

    def set_pose_constraint(self, pose_constraint):
        """Installs a simulator-only pose constraint in canonical field coordinates."""
        self.pose_constraint = pose_constraint

    def start_server(self):
        """Starts the wireless telemetry server for ARES Studio tethering."""
        return self.telemetry.start()

    def set_autonomous_routines(self, routines, default_id=None):
        self.autonomous_routines = routines or {}
        self.default_autonomous_id = default_id
        self.active_routine = self.autonomous_routines.get(default_id)
        if self.active_routine:
            self.active_routine.reset()

    def set_subsystems(self, subsystems):
        self.subsystems = list(subsystems or [])

    def handle_action(self, action_key, arguments=None):
        arguments = arguments or {}
        if action_key == "drivetrain.recoverNeutral":
            self.telemetry.last_command = "INIT"
            return
        for subsystem in self.subsystems:
            if action_key in getattr(subsystem, "capability_action_keys", ()):
                subsystem.handle_action(action_key, arguments)
                return
        parts = action_key.split(".")
        if len(parts) == 4 and parts[0] == "subsystem" and parts[2] == "set":
            subsystem = next((item for item in self.subsystems if item.document_id == parts[1]), None)
            if subsystem is None:
                raise ValueError("Unknown XRP subsystem action: " + action_key)
            if "value" not in arguments:
                raise ValueError("XRP subsystem set action requires a value")
            subsystem.set_target(parts[3], arguments["value"])
            return
        if len(parts) == 4 and parts[0] == "subsystem" and parts[2:] == ["recover", "neutral"]:
            subsystem = next((item for item in self.subsystems if item.document_id == parts[1]), None)
            if subsystem is None or not subsystem.recover_neutral():
                raise ValueError("XRP subsystem neutral recovery failed: " + action_key)
            return
        raise ValueError("Unknown XRP action: " + action_key)

    def step(self, dt=0.02):
        """Main robot cycle executing at 50Hz (20ms)."""
        # 1. Poll incoming network telemetry
        self.telemetry.poll()
        battery_volts = float(self.battery_voltage_supplier())

        # 2. Check Driver Station commands from Studio
        cmd = self.telemetry.get_command()
        if cmd == "START_TELEOP":
            if not self.faulted:
                self.mode = self.STATE_TELEOP
        elif cmd == "START_AUTO":
            selected = self.telemetry.selected_opmode or self.default_autonomous_id
            self.active_routine = self.autonomous_routines.get(selected)
            if self.active_routine and not self.faulted:
                self.active_routine.reset()
                self.mode = self.STATE_AUTO
            else:
                self.mode = self.STATE_DISABLED
                self.telemetry.neutralize()
                self.drivetrain.stop()
        elif cmd == "STOP":
            self.mode = self.STATE_DISABLED
            self.telemetry.neutralize()
            self.drivetrain.stop()
        elif cmd == "INIT":
            self.telemetry.neutralize()
            try:
                self.drivetrain.stop()
                recovered = all(subsystem.recover_neutral() for subsystem in self.subsystems)
                self.faulted = not recovered
                self.mode = self.STATE_INIT if recovered else self.STATE_DISABLED
            except Exception:
                self.faulted = True
                self.mode = self.STATE_DISABLED
            if self.active_routine:
                self.active_routine.reset()

        # A mode request can never clear a live brownout or invalid voltage reading.
        if not math.isfinite(battery_volts) or battery_volts < self.brownout_threshold_volts:
            self.faulted = True
            self.mode = self.STATE_DISABLED
            self.telemetry.neutralize()
            self.drivetrain.stop()

        # 3. Update sensor odometry and execute the selected mode. Sensor failures
        # use the same fail-closed path as actuator failures.
        try:
            previous_pose = (
                self.drivetrain.x,
                self.drivetrain.y,
                self.drivetrain.heading,
            )
            self.drivetrain.update_odometry(dt=dt)
            if self.pose_constraint:
                constrained_pose = self.pose_constraint(
                    previous_pose,
                    (self.drivetrain.x, self.drivetrain.y, self.drivetrain.heading),
                )
                if constrained_pose != (
                    self.drivetrain.x,
                    self.drivetrain.y,
                    self.drivetrain.heading,
                ):
                    self.drivetrain.reset_pose(*constrained_pose)
                    self.drivetrain.vx = 0.0
                    self.drivetrain.vy = 0.0
                    self.drivetrain.omega = 0.0
                    self.drivetrain.stop()
            if self.mode == self.STATE_AUTO:
                # Autonomous motion remains leased by Studio. A disconnect or stale heartbeat
                # stops the robot within the configured deadman interval.
                if self.telemetry.get_drive_frame() is None:
                    self.mode = self.STATE_DISABLED
                    self.drivetrain.stop()
                elif self.active_routine:
                    vx, omega, finished = self.active_routine.update(
                        self.drivetrain.x, self.drivetrain.y, self.drivetrain.heading, dt
                    )
                    if finished:
                        self.drivetrain.stop()
                        self.mode = self.STATE_TELEOP # Auto finished, transition to teleop ready
                    else:
                        if isinstance(self.drivetrain, MecanumDrivetrain):
                            self.drivetrain.drive(vx, 0.0, omega)
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
            for subsystem in self.subsystems:
                if self.mode in (self.STATE_AUTO, self.STATE_TELEOP):
                    subsystem.periodic(dt)
                else:
                    subsystem.stop()
        except Exception:
            self.faulted = True
            self.mode = self.STATE_DISABLED
            self.telemetry.neutralize()
            try:
                self.drivetrain.stop()
            except Exception:
                pass
            for subsystem in self.subsystems:
                try:
                    subsystem.stop()
                except Exception:
                    pass

        # 5. Stream telemetry frame to Studio
        self.telemetry.publish_pose_frame(
            x=self.drivetrain.x,
            y=self.drivetrain.y,
            heading_rad=self.drivetrain.heading,
            battery_volts=battery_volts,
            mode=self.mode,
            faulted=self.faulted or any(subsystem.faulted for subsystem in self.subsystems),
            # LoopTimeMs is the control period, not only the sub-millisecond CPU work inside
            # this method. Studio uses it to display effective control frequency and overruns.
            loop_time_ms=max(0.0, float(dt) * 1000.0),
            subsystems={subsystem.document_id: dict(subsystem.state) for subsystem in self.subsystems},
        )
