import importlib.util
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("audit_inventory", Path(__file__).resolve().parents[1] / "audit_inventory.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AuditInventoryTest(unittest.TestCase):
    def test_new_changed_missing_and_deleted_files_cannot_keep_completed_credit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "same.py").write_bytes(b"pass\n")
            (root / "changed.py").write_bytes(b"changed\n")
            record = {"sha256": MODULE.fingerprint(root / "same.py"), "review": "reviewed",
                      "validation": "passed", "scope": "full file", "evidence": ["test run"]}
            result = MODULE.inventory(root, ["same.py", "changed.py", "new.py", "missing.py"],
                                      {name: record for name in ("same.py", "changed.py", "missing.py", "deleted.py")})
            self.assertEqual(result["total"], 4)
            self.assertEqual(result["complete"], 1)
            self.assertEqual(result["reviewCounts"], {"pending": 1, "reviewed": 1, "stale": 2})
            self.assertEqual(result["orphanedRecords"], ["deleted.py"])

    def test_suite_success_never_substitutes_for_review_or_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "robot.py"
            source.write_bytes(b"pass\n")
            for review, validation, evidence in (("partial", "passed", ["run"]),
                                                   ("reviewed", "failed", ["run"]),
                                                   ("reviewed", "passed", [])):
                with self.subTest(review=review, validation=validation, evidence=evidence):
                    result = MODULE.inventory(root, ["robot.py"], {"robot.py": {
                        "sha256": MODULE.fingerprint(source), "review": review,
                        "validation": validation, "scope": "full file", "evidence": evidence}})
                    self.assertEqual(result["complete"], 0)

    def test_checkout_line_endings_do_not_invalidate_text_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "source.py"
            file.write_bytes(b"a\r\nb\r\n")
            digest = MODULE.fingerprint(file)
            file.write_bytes(b"a\nb\n")
            self.assertEqual(MODULE.fingerprint(file), digest)
            file.write_bytes(b"a\0\r\n")
            digest = MODULE.fingerprint(file)
            file.write_bytes(b"a\0\n")
            self.assertNotEqual(MODULE.fingerprint(file), digest)


if __name__ == "__main__":
    unittest.main()
