"""
ARES Micro - Telemetry & Driver Station Tether Bridge for MicroPython
Handles Wi-Fi socket communication with ARES Robotics Studio desktop app.
Publishes poses and robot status, receives leased drive frames and DS commands.
"""

import json
import time

try:
    import socket
except ImportError:
    socket = None

class XrpTelemetryServer:
    def __init__(self, host="0.0.0.0", port=5810):
        self.host = host
        self.port = port
        self.server_socket = None
        self.client_socket = None
        self.is_connected = False

        # Incoming command buffers
        self.last_drive_frame = None
        self.last_command = ""
        self.selected_opmode = ""

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
            "poseFrame": [x, y, heading_rad, x, y, heading_rad, x, y, heading_rad, self.sequence],
            "poseX": x,
            "poseY": y,
            "heading": heading_rad,
            "battery": battery_volts,
            "mode": mode,
            "timestamp": time.time()
        }

        try:
            line = json.dumps(payload) + "\n"
            self.client_socket.send(line.encode('utf-8'))
        except Exception:
            self.close_client()

    def get_drive_frame(self):
        """Returns latest (vx, vy, omega) drive frame from Studio, or None."""
        frame = self.last_drive_frame
        self.last_drive_frame = None
        return frame

    def get_command(self):
        """Returns pending Driver Station command ('INIT', 'START', 'STOP'), or None."""
        cmd = self.last_command
        self.last_command = ""
        return cmd

    def close_client(self):
        self.is_connected = False
        if self.client_socket:
            try:
                self.client_socket.close()
            except Exception:
                pass
            self.client_socket = None

    def _process_buffer(self):
        while "\n" in self._recv_buffer:
            line, self._recv_buffer = self._recv_buffer.split("\n", 1)
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
                if "driveFrame" in msg:
                    self.last_drive_frame = msg["driveFrame"]
                if "command" in msg:
                    self.last_command = msg["command"]
                if "selectedOpMode" in msg:
                    self.selected_opmode = msg["selectedOpMode"]
            except Exception:
                pass
