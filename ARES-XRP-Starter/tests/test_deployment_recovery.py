import builtins
import importlib.util
import io
import pathlib
import tempfile
import types
import unittest
from unittest import mock
from test_xrp_device import xrp_device, ROOT

class PowerLoss(BaseException): pass

class Device:
    def __init__(self, cut=None):
        self.files = {"/ares_active_slot.txt": b"/ares_slots/slot-old", "/ares_active_slot.prev": b"/ares_slots/slot-older",
                      "/ares_slots/slot-old/main.py": b"pass", "/ares_slots/slot-older/main.py": b"pass"}
        self.dirs = {"/", "/ares_slots", "/ares_slots/slot-old", "/ares_slots/slot-older"}
        self.ops = 0; self.cut = cut
        self.os = types.SimpleNamespace(rename=self.rename, sync=self.mutation, mkdir=self.mkdir, ilistdir=self.ilistdir)
    def mutation(self):
        self.ops += 1
        if self.ops == self.cut: raise PowerLoss()
    def mkdir(self, path):
        if path in self.dirs: raise OSError(17, "exists")
        self.dirs.add(path); self.mutation()
    def rename(self, old, new):
        self.files[new] = self.files.pop(old); self.mutation()
    def ilistdir(self, root):
        for path in sorted(self.files.keys() | self.dirs):
            if path.startswith(root + "/") and "/" not in path[len(root)+1:]:
                yield path[len(root)+1:], 0x4000 if path in self.dirs else 0x8000, 0
    def open(self, path, mode="r"):
        if "w" not in mode and path not in self.files: raise OSError(2, "missing")
        if "w" in mode: self.files[path] = b""; self.mutation()
        binary = "b" in mode
        device = self
        base = io.BytesIO if binary else io.StringIO
        class Stream(base):
            def write(self, value):
                count = super().write(value)
                data = self.getvalue()
                device.files[path] = data if binary else data.encode()
                device.mutation()
                return count
            def flush(self): device.mutation()
        data = self.files[path]
        return Stream(data if binary else data.decode())
    def execute(self, code):
        def importer(name, *args):
            return self.os if name == "os" else builtins.__import__(name, *args)
        namespace = {"__builtins__": dict(vars(builtins), open=self.open, __import__=importer), "__name__": "test"}
        exec(code, namespace)
        return namespace
    def boot_slot(self):
        module = self.execute((ROOT / "deploy/ares_boot.py").read_text())
        return module["_active_slot"]()
    def mpremote(self, args, capture=False):
        if args[0] == "exec":
            from contextlib import redirect_stdout
            output = io.StringIO()
            with redirect_stdout(output): self.execute(args[1])
            return types.SimpleNamespace(stdout=output.getvalue())
        if args[:2] == ["fs", "cp"]:
            source = pathlib.Path(args[-2]); destination = args[-1].removeprefix(":").rstrip("/")
            if source.is_dir():
                base = destination + "/" + source.name
                self.dirs.add(base)
                for item in source.rglob("*"):
                    path = base + "/" + item.relative_to(source).as_posix()
                    if item.is_dir(): self.dirs.add(path)
                    else: self.files[path] = item.read_bytes(); self.mutation()
            else:
                path = destination + "/" + source.name if destination in self.dirs else destination
                self.files[path] = source.read_bytes(); self.mutation()
        return types.SimpleNamespace(stdout="")

class RecoveryTest(unittest.TestCase):
    def test_activation_survives_interruption_after_every_filesystem_operation(self):
        complete = Device(); complete.files["/ares_slots/slot-new/main.py"] = b"pass"
        script = xrp_device._activation_script("/ares_slots/slot-new")
        complete.execute(script)
        for cut in range(1, complete.ops + 1):
            with self.subTest(cut=cut):
                device = Device(cut); device.files["/ares_slots/slot-new/main.py"] = b"pass"
                with self.assertRaises(PowerLoss): device.execute(script)
                self.assertIn(device.boot_slot(), ("/ares_slots/slot-old", "/ares_slots/slot-new"))
                self.assertEqual(b"pass", device.files["/ares_slots/slot-old/main.py"])

    def test_boot_falls_back_from_missing_or_invalid_current(self):
        for marker in (None, b"garbage", b"/ares_slots/slot-missing"):
            device = Device()
            if marker is None: del device.files["/ares_active_slot.txt"]
            else: device.files["/ares_active_slot.txt"] = marker
            self.assertEqual("/ares_slots/slot-older", device.boot_slot())

    def test_same_content_redeploy_never_reuses_or_deletes_the_active_slot(self):
        device = Device()
        with mock.patch.object(xrp_device, "preflight", return_value={"machine": "fake"}), mock.patch.object(xrp_device, "run_mpremote", side_effect=device.mpremote):
            xrp_device.deploy()
            first = device.boot_slot()
            payload = dict(device.files)
            xrp_device.deploy()
            self.assertNotEqual(first, device.boot_slot())
            self.assertEqual(first.encode(), device.files["/ares_active_slot.prev"])
            for path, data in payload.items():
                if path.startswith(first + "/"): self.assertEqual(data, device.files[path])

    def test_partial_copy_leaves_previous_deployment_bootable(self):
        device = Device(cut=4)
        with mock.patch.object(xrp_device, "preflight", return_value={"machine": "fake"}), mock.patch.object(xrp_device, "run_mpremote", side_effect=device.mpremote):
            with self.assertRaises(PowerLoss): xrp_device.deploy()
        self.assertEqual("/ares_slots/slot-old", device.boot_slot())

    def test_runtime_only_changes_change_full_payload_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = pathlib.Path(directory)
            (stage / "main.py").write_text("pass")
            first = xrp_device._seal_payload(stage)
            (stage / "ares-files.json").unlink()
            (stage / "main.py").write_text("print('new runtime')")
            self.assertNotEqual(first, xrp_device._seal_payload(stage))

    def test_validation_rejects_tampered_or_missing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = pathlib.Path(directory)
            (stage / "main.py").write_text("pass")
            digest = xrp_device._seal_payload(stage)
            for tampered in (None, b"print('tampered')"):
                device = Device()
                device.files["/ares_slots/slot-old/ares-files.json"] = (stage / "ares-files.json").read_bytes()
                if tampered is None: del device.files["/ares_slots/slot-old/main.py"]
                else: device.files["/ares_slots/slot-old/main.py"] = tampered
                with self.assertRaises(AssertionError): device.execute(xrp_device._validation_script("/ares_slots/slot-old", digest))
