"""Exercise the installed Windows MCP launcher as an external stdio client."""

import json
from pathlib import Path
from queue import Queue
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread


def check_handshake(command: list[str]) -> None:
    with subprocess.Popen(
        command,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8",
    ) as server:
        replies = Queue()

        def receive():
            for line in server.stdout:
                replies.put(line)
            replies.put("")

        Thread(target=receive, daemon=True).start()

        def send(method, params, number=None):
            packet = {"jsonrpc": "2.0", "method": method, "params": params}
            if number is not None:
                packet["id"] = number
            server.stdin.write(json.dumps(packet) + "\n")
            server.stdin.flush()
            if number is not None:
                reply = json.loads(replies.get(timeout=120))
                if reply.get("id") != number or "result" not in reply:
                    raise RuntimeError(f"Invalid reply to {method}: {reply}")
                return reply["result"]

        try:
            hello = send("initialize", {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "windows-launcher-test", "version": "1"},
            }, 1)
            send("notifications/initialized", {})
            resources = send("resources/list", {}, 2)
            if not any(entry["uri"] == "ares://runs" for entry in resources["resources"]):
                raise RuntimeError("Missing recorded-run resource")
            print("External stdio handshake passed for", hello["serverInfo"]["name"])
        finally:
            server.stdin.close()
            server.wait(timeout=15)
        if server.returncode:
            raise RuntimeError(f"Launcher exited with status {server.returncode}")


def verify(distribution: Path, developer_script: Path | None) -> None:
    with TemporaryDirectory(prefix="ares-mcp-") as temporary:
        root = Path(temporary) / "Student robotics programming workspace" / "Long project directory for the Windows launcher"
        shutil.copytree(distribution, root)
        bridge_args = ["--bridge-dir", str(root / "offline")]
        check_handshake([str(root / "bin" / "mcp.bat"), "--mcp-stdio", *bridge_args])
        if developer_script is not None:
            check_handshake(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                             "-File", str(developer_script), *bridge_args])


if __name__ == "__main__":
    verify(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else None)
