# SPDX-License-Identifier: Apache-2.0
"""Exercise the report writer with real unittest outcomes, including failures."""

import importlib.util
import io
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest import mock


spec = importlib.util.spec_from_file_location(
    "xrp_junit_audit_tool", pathlib.Path(__file__).resolve().parents[1] / "tools/ares_project.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class JunitReportingAuditTest(unittest.TestCase):
    def report(self, case_type):
        result = unittest.TextTestRunner(stream=io.StringIO(), resultclass=tool.RecordingTestResult).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(case_type))
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(tool, "TEST_RESULTS", pathlib.Path(directory)):
            tool.write_junit_report(result)
            root = ET.parse(pathlib.Path(directory) / "TEST-ares-xrp.xml").getroot()
        self.assertEqual(int(root.get("tests")), len(root.findall("testcase")))
        for kind, counter in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            self.assertEqual(int(root.get(counter)), len(root.findall("testcase/" + kind)))
        return result, root

    def test_expected_failure_and_unexpected_success_are_not_omitted(self):
        class Cases(unittest.TestCase):
            @unittest.expectedFailure
            def test_expected(self):
                self.fail("known issue")

            @unittest.expectedFailure
            def test_unexpected(self):
                pass

        result, root = self.report(Cases)
        self.assertFalse(result.wasSuccessful())
        self.assertEqual(root.get("tests"), "2")
        self.assertEqual(root.get("failures"), "1")
        self.assertEqual(root.get("skipped"), "1")
        self.assertIn("unexpected", root.find("testcase[failure]").get("name"))

    def test_repeated_subtest_identity_retains_each_outcome(self):
        class Cases(unittest.TestCase):
            def test_subcases(self):
                for _ in range(2):
                    with self.subTest(value=1.5):
                        self.fail("repeated failure")

        _, root = self.report(Cases)
        self.assertEqual(root.get("tests"), "2")
        self.assertEqual(root.get("failures"), "2")
        for case in root.findall("testcase"):
            self.assertIn("value=1.5", case.get("name"))
            self.assertTrue(case.get("classname").endswith("Cases"))

    def test_failure_text_with_invalid_xml_characters_remains_readable(self):
        class Cases(unittest.TestCase):
            def test_bad_text(self):
                self.fail("before\x00middle\uffffafter")

        _, root = self.report(Cases)
        detail = root.find("testcase/failure").text
        self.assertIn("before", detail)
        self.assertIn("after", detail)
        self.assertNotIn("\x00", detail)

    def test_normal_success_failure_error_and_skip_are_reported(self):
        class Cases(unittest.TestCase):
            def test_pass(self):
                pass

            def test_fail(self):
                self.fail("assertion")

            def test_error(self):
                raise RuntimeError("error")

            @unittest.skip("unavailable hardware")
            def test_skip(self):
                pass

        _, root = self.report(Cases)
        self.assertEqual([root.get(k) for k in ("tests", "failures", "errors", "skipped")], ["4", "1", "1", "1"])
