"""Stable A/B-slot launcher installed as /main.py by ARES deployment."""

import sys


def _active_program():
    for marker_path in ("/ares_active_slot.txt", "/ares_active_slot.prev"):
        try:
            with open(marker_path, "r") as marker:
                slot = marker.read().strip()
            if not slot.startswith("/ares_slots/slot-") or ".." in slot or "/" in slot[len("/ares_slots/"):]:
                continue
            with open(slot + "/main.py", "r") as source:
                code = compile(source.read(), slot + "/main.py", "exec")
            return slot, code
        except (OSError, SyntaxError, ValueError):
            pass
    raise RuntimeError("No valid ARES deployment marker; deploy or restore a verified slot")


def _active_slot():
    return _active_program()[0]


def _run():
    slot, code = _active_program()
    if slot in sys.path:
        sys.path.remove(slot)
    sys.path.insert(0, slot)
    source_path = slot + "/main.py"
    exec(code, {"__name__": "__main__", "__file__": source_path})


if __name__ == "__main__":
    _run()
