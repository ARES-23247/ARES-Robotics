"""Inventory verification must measure source rather than trust a saved PASS label."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "generate_codebase_ledger.py"
SPEC = importlib.util.spec_from_file_location("codebase_ledger", SCRIPT)
ledger = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ledger)


class CodebaseLedgerTest(unittest.TestCase):
    def test_source_edit_or_new_file_invalidates_saved_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = "ARESLib-Kotlin/core/src/main/kotlin/A.kt"
            source = root / name
            source.parent.mkdir(parents=True)
            source.write_text("class A\n", encoding="utf-8")
            with patch.object(ledger.subprocess, "check_output", return_value=name + "\0"):
                saved = ledger.compute_ledger(root)
                self.assertEqual(saved, ledger.compute_ledger(root))
                ledger.verify_ledger(saved, root)
                source.write_text("class A\n// Changed\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "differs"):
                    ledger.verify_ledger(saved, root)
            other = "ARESLib-Kotlin/core/src/main/kotlin/B.kt"
            (root / other).write_text("class B\n", encoding="utf-8")
            source.write_text("class A\n", encoding="utf-8")
            with patch.object(ledger.subprocess, "check_output", return_value=name + "\0" + other + "\0"):
                with self.assertRaisesRegex(ValueError, "differs"):
                    ledger.verify_ledger(saved, root)

    def test_current_over_limit_source_cannot_pass_by_regenerating_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = "ARESLib-Kotlin/core/src/main/kotlin/A.kt"
            source = root / name
            source.parent.mkdir(parents=True)
            source.write_text("// line\n" * 501, encoding="utf-8")
            with patch.object(ledger.subprocess, "check_output", return_value=name + "\0"):
                saved = ledger.compute_ledger(root)
                with self.assertRaisesRegex(ValueError, "limits exceeded"):
                    ledger.verify_ledger(saved, root)
                saved["summary"]["maintainabilityRatchet"] = "PASS"
                saved["summary"]["violationsOverLimit"] = 0
                with self.assertRaisesRegex(ValueError, "differs"):
                    ledger.verify_ledger(saved, root)

    def test_missing_tracked_source_is_an_error_not_silently_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(ledger.subprocess, "check_output", return_value="core/src/main/kotlin/A.kt\0"):
                with self.assertRaises(FileNotFoundError):
                    ledger.compute_ledger(Path(directory))


if __name__ == "__main__":
    unittest.main()
