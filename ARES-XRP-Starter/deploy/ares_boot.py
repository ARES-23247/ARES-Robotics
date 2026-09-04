"""Stable A/B-slot launcher installed as /main.py by ARES deployment."""

import sys


def _active_slot():
    with open("/ares_active_slot.txt", "r") as marker:
        slot = marker.read().strip()
    if not slot.startswith("/ares_slots/") or ".." in slot:
        raise RuntimeError("Invalid ARES active-slot marker")
    return slot


def _run():
    slot = _active_slot()
    if slot not in sys.path:
        sys.path.insert(0, slot)
    source_path = slot + "/main.py"
    with open(source_path, "r") as source:
        code = compile(source.read(), source_path, "exec")
    exec(code, {"__name__": "__main__", "__file__": source_path})


_run()
