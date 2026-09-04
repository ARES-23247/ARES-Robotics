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
                 host="0.0.0.0", port=5811, deadman_timeout_ms=200, runtime_identity=None):
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
        self.runtime_identity = dict(runtime_identity or {})

        self.sequence = 0
        self.field_sequence = 0
        self.field_session = "%s-%s" % (project_id, _ticks_ms())
        self.field_config_handler = None
        self._recv_buffer = ""

    def set_field_config_handler(self, handler):
        """Installs the desktop-simulator field replacement hook.

        Physical XRP runtimes intentionally leave this unset.  A field update is
        acknowledged only after the simulator has decoded and installed the exact
        canonical payload.
        """
        self.field_config_handler = handler

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
            except OSError as error:
                # A non-blocking socket reports "no bytes available" as would-block;
                # every other socket error means the peer is gone and must release
                # the single XRP connection slot so Studio can reconnect.
                error_code = error.args[0] if error.args else None
                if error_code not in (11, 35, 10035):
                    self.close_client()
            except Exception:
                self.close_client()

        # If connected, read pending data
        if self.is_connected and self.client_socket:
            try:
                chunk = self.client_socket.recv(512)
                if not chunk:
                    self.close_client()
                else:
                    self._recv_buffer += chunk.decode('utf-8', 'ignore')
                    self._process_buffer()
            except OSError as error:
                # Only a would-block error means there is simply no complete
                # command available yet. A reset/broken pipe is a disconnected
                # peer and must release the single Studio connection slot.
                error_code = error.args[0] if error.args else None
                if error_code not in (11, 35, 10035):
                    self.close_client()
            except Exception:
                self.close_client()

    def now_ms(self):
        return _ticks_ms()

    def elapsed_ms(self, since_ms):
        return _ticks_diff(_ticks_ms(), since_ms)

    def publish_pose_frame(self, x, y, heading_rad, battery_volts=6.0, mode="TELEOP",
                           faulted=False, loop_time_ms=0, subsystems=None):
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
            "faulted": bool(faulted),
            "loopTimeMs": loop_time_ms,
            "subsystems": subsystems or {},
        }
        self._send(payload)

    def hello_payload(self):
        payload = {
            "protocol": PROTOCOL,
            "type": "hello",
            "role": "robot",
            "projectId": self.project_id,
            "contentSha256": self.content_sha256,
            "drivetrainType": self.drivetrain_type,
        }
        payload.update(self.runtime_identity)
        return payload

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
                if msg.get("protocol") != PROTOCOL:
                    continue
                if msg.get("type") == "fieldConfig":
                    self._apply_field_config(msg)
                    continue
                if msg.get("type") != "control":
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

    def _apply_field_config(self, msg):
        self.field_sequence += 1
        base = {
            "protocol": PROTOCOL,
            "session": self.field_session,
            "sequence": self.field_sequence,
            "configId": str(msg.get("configId", "")),
            "revision": msg.get("revision"),
            "sha256": str(msg.get("sha256", "")),
        }
        try:
            if self.field_config_handler is None:
                raise ValueError("this XRP runtime does not support live field replacement")
            payload = msg.get("payload")
            if not isinstance(payload, str) or not payload:
                raise ValueError("field payload is missing")
            result = self.field_config_handler(payload)
            if (
                result.get("configId") != base["configId"]
                or result.get("revision") != base["revision"]
                or result.get("sha256") != base["sha256"]
            ):
                raise ValueError("field identity did not match the canonical payload")
            response = dict(base)
            response.update({
                "type": "fieldApplied",
                "obstacleCount": int(result.get("obstacleCount", 0)),
                "elementCount": int(result.get("elementCount", 0)),
                "aprilTagCount": int(result.get("aprilTagCount", 0)),
            })
            self._send(response)
        except Exception as error:
            response = dict(base)
            response.update({
                "type": "fieldRejected",
                "message": str(error) or "field configuration was rejected",
            })
            self._send(response)
