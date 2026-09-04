"""Versioned, fail-closed XRP link used by ARES Robotics Studio.

This is deliberately not NT4.  The Pico W exposes a small newline-delimited JSON
protocol on a different port so an XRP can remain useful without carrying the
NetworkTables implementation used by FTC and FRC projects.
"""

import json
import math
import time

try:
    import socket
except ImportError:
    socket = None

PROTOCOL = "ares-xrp/1"


def _ticks_ms():
    ticks = getattr(time, "ticks_ms", None)
    return ticks() if ticks else int(time.time() * 1000)


def _ticks_diff(now, then):
    difference = getattr(time, "ticks_diff", None)
    return difference(now, then) if difference else now - then


class XrpTelemetryServer:
    def __init__(self, project_id, content_sha256, drivetrain_type,
                 host="0.0.0.0", port=5811, deadman_timeout_ms=200):
        if not isinstance(project_id, str) or not project_id:
            raise ValueError("XRP link requires a canonical project ID")
        if not isinstance(content_sha256, str) or len(content_sha256) != 64:
            raise ValueError("XRP link requires a generated content SHA-256")
        if drivetrain_type not in ("differential", "mecanum"):
            raise ValueError("XRP link requires a supported drivetrain type")
        self.project_id = project_id
        self.content_sha256 = content_sha256
        self.drivetrain_type = drivetrain_type
        self.host = host
        self.port = port
        self.server_socket = None
        self.client_socket = None
        self.is_connected = False

        # Incoming command buffers
        self.last_drive_frame = None
        self.last_drive_ms = None
        self.last_command = ""
        self.selected_opmode = ""
        self.active_session_id = None
        self.last_control_sequence = -1
        self.last_request_revision = -1
        self.active_request_command = ""
        self.active_selected_opmode = ""
        self.armed = False
        self.deadman_timeout_ms = int(deadman_timeout_ms)

        self.sequence = 0
        self._recv_buffer = ""

    def start(self):
        """Binds and starts listening on non-blocking server socket."""
        if socket is None:
            return False
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(1)
            self.server_socket.setblocking(False)
            return True
        except Exception:
            return False

    def poll(self):
        """Accepts incoming client connections and processes messages without blocking."""
        if self.server_socket is None:
            return

        # Check for new connection if not connected
        if not self.is_connected:
            try:
                client, addr = self.server_socket.accept()
                client.setblocking(False)
                self.client_socket = client
                self.is_connected = True
                self._recv_buffer = ""
                self._send(self.hello_payload())
            except Exception:
                pass

        # If connected, read pending data
        if self.is_connected and self.client_socket:
            try:
                chunk = self.client_socket.recv(512)
                if not chunk:
                    self.close_client()
                else:
                    self._recv_buffer += chunk.decode('utf-8', 'ignore')
                    self._process_buffer()
            except Exception:
                pass

    def publish_pose_frame(self, x, y, heading_rad, battery_volts=6.0, mode="TELEOP"):
        """Publishes atomic pose and robot state to connected Studio client."""
        if not self.is_connected or self.client_socket is None:
            return

        self.sequence += 1
        payload = {
            "protocol": PROTOCOL,
            "type": "telemetry",
            "sequence": self.sequence,
            "poseFrame": [x, y, heading_rad, x, y, heading_rad, x, y, heading_rad, self.sequence],
            "poseX": x,
            "poseY": y,
            "heading": heading_rad,
            "battery": battery_volts,
            "mode": mode,
            "timestampMs": _ticks_ms(),
            "armed": self.armed,
        }
        self._send(payload)

    def hello_payload(self):
        return {
            "protocol": PROTOCOL,
            "type": "hello",
            "role": "robot",
            "projectId": self.project_id,
            "contentSha256": self.content_sha256,
            "drivetrainType": self.drivetrain_type,
        }

    def get_drive_frame(self):
        """Returns latest (vx, vy, omega) drive frame from Studio, or None."""
        if not self.armed or self.last_drive_ms is None:
            return None
        if _ticks_diff(_ticks_ms(), self.last_drive_ms) > self.deadman_timeout_ms:
            self.neutralize()
            return None
        return self.last_drive_frame

    def get_command(self):
        """Returns a pending explicit mode command, or an empty string."""
        cmd = self.last_command
        self.last_command = ""
        return cmd

    def close_client(self):
        self.neutralize()
        self.active_session_id = None
        self.last_control_sequence = -1
        self.last_request_revision = -1
        self.active_request_command = ""
        self.active_selected_opmode = ""
        self.is_connected = False
        if self.client_socket:
            try:
                self.client_socket.close()
            except Exception:
                pass
            self.client_socket = None

    def neutralize(self):
        self.armed = False
        self.last_drive_frame = None
        self.last_drive_ms = None

    def _send(self, payload):
        try:
            line = json.dumps(payload) + "\n"
            self.client_socket.send(line.encode("utf-8"))
        except Exception:
            self.close_client()

    def _process_buffer(self):
        while "\n" in self._recv_buffer:
            line, self._recv_buffer = self._recv_buffer.split("\n", 1)
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
                if msg.get("protocol") != PROTOCOL or msg.get("type") != "control":
                    continue
                session_id = msg.get("sessionId")
                sequence = msg.get("sequence")
                if not isinstance(session_id, str) or not session_id:
                    continue
                if not isinstance(sequence, int):
                    continue
                if self.active_session_id != session_id:
                    self.neutralize()
                    self.active_session_id = session_id
                    self.last_control_sequence = -1
                    self.last_request_revision = -1
                    self.active_request_command = ""
                    self.active_selected_opmode = ""
                if sequence <= self.last_control_sequence:
                    continue
                self.last_control_sequence = sequence
                command = msg.get("command", "")
                request_revision = msg.get("requestRevision")
                if command not in ("INIT", "START_TELEOP", "START_AUTO", "STOP"):
                    self.neutralize()
                    continue
                if not isinstance(request_revision, int) or request_revision < 0:
                    self.neutralize()
                    continue
                selected_opmode = str(msg.get("selectedOpMode", ""))
                if request_revision > self.last_request_revision:
                    self.last_request_revision = request_revision
                    self.active_request_command = command
                    self.active_selected_opmode = selected_opmode
                    self.last_command = command
                    self.selected_opmode = selected_opmode
                elif (
                    request_revision < self.last_request_revision
                    or command != self.active_request_command
                    or selected_opmode != self.active_selected_opmode
                ):
                    self.neutralize()
                    continue
                requested_armed = msg.get("armed") is True and command in ("START_TELEOP", "START_AUTO")
                frame = msg.get("driveFrame")
                if requested_armed and isinstance(frame, list) and len(frame) >= 3:
                    try:
                        parsed = [float(frame[0]), float(frame[1]), float(frame[2])]
                        if not all(math.isfinite(value) for value in parsed):
                            raise ValueError("Drive frame values must be finite")
                        self.last_drive_frame = parsed
                        self.last_drive_ms = _ticks_ms()
                        self.armed = True
                    except (TypeError, ValueError):
                        self.neutralize()
                else:
                    self.neutralize()
            except Exception:
                self.neutralize()
