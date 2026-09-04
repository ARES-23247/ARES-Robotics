"""Stable A/B-slot launcher installed as /main.py by ARES deployment."""

import sys


def _active_slot():
    for marker_path in ("/ares_active_slot.txt", "/ares_active_slot.prev"):
        try:
            with open(marker_path, "r") as marker:
                slot = marker.read().strip()
            if not slot.startswith("/ares_slots/slot-") or ".." in slot or "/" in slot[len("/ares_slots/"):]:
                continue
            with open(slot + "/main.py", "r") as source:
                compile(source.read(), slot + "/main.py", "exec")
            return slot
        except (OSError, SyntaxError):
            pass
    raise RuntimeError("No valid ARES deployment marker; deploy or restore a verified slot")


def _run():
    slot = _active_slot()
    if slot not in sys.path:
        sys.path.insert(0, slot)
    source_path = slot + "/main.py"
    with open(source_path, "r") as source:
        code = compile(source.read(), source_path, "exec")
    exec(code, {"__name__": "__main__", "__file__": source_path})


if __name__ == "__main__":
    _run()
