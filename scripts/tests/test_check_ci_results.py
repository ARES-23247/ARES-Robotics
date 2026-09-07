import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "check_ci_results", Path(__file__).resolve().parents[1] / "check_ci_results.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiResultsTest(unittest.TestCase):
    def test_success_and_intentional_skips_pass(self):
        self.assertEqual([], MODULE.unsuccessful_jobs({
            "changes": {"result": "success"}, "selected": {"result": "success"},
            "unaffected": {"result": "skipped"},
        }))

    def test_classifier_must_have_run_successfully(self):
        for result in ("failure", "cancelled", "skipped", None):
            with self.subTest(result=result):
                self.assertEqual(["changes"], MODULE.unsuccessful_jobs({
                    "changes": {"result": result}, "downstream": {"result": "skipped"},
                }))
        self.assertEqual(["changes"], MODULE.unsuccessful_jobs({}))

    def test_failure_cannot_hide_behind_skipped_downstream_jobs(self):
        for result in ("failure", "cancelled", "unexpected"):
            self.assertEqual(["candidate"], MODULE.unsuccessful_jobs({
                "changes": {"result": "success"}, "candidate": {"result": result},
                "consumer": {"result": "skipped"},
            }))
