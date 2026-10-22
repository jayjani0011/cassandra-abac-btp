#!/usr/bin/env python3
"""Prevent false validation success on missing, failed, or skipped JUnit results."""
import pathlib
import tempfile
import unittest

from validate_correctness import check_report


class ReportValidationTest(unittest.TestCase):
    def report(self, attributes='tests="1"', case='<testcase classname="Example" name="hardware-_jdk11"/>'):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = pathlib.Path(directory.name) / "report.xml"
        path.write_text(f"<testsuite {attributes}>{case}</testsuite>")
        return path

    def test_accepts_executed_hardware_case(self):
        self.assertEqual(1, check_report(self.report(), "Example", "hardware"))

    def test_rejects_skipped_or_failed_suite(self):
        for field in ["skipped", "failures", "errors"]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                check_report(self.report(f'tests="1" {field}="1"'), "Example")

    def test_rejects_case_failure_even_without_suite_counters(self):
        for tag in ["skipped", "failure", "error"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                check_report(self.report(case=f'<testcase classname="Example" name="hardware"><{tag}/></testcase>'), "Example")

    def test_rejects_empty_incomplete_or_wrong_results(self):
        for attributes, case in [('tests="0"', ''), ('tests="2"', '<testcase classname="Example"/>'),
                                 ('tests="1"', '<testcase classname="Other"/>')]:
            with self.subTest(attributes=attributes, case=case), self.assertRaises(ValueError):
                check_report(self.report(attributes, case), "Example")

    def test_rejects_missing_hardware_method_or_report(self):
        with self.assertRaises(ValueError):
            check_report(self.report(), "Example", "missingHardwareTest")
        with self.assertRaises(FileNotFoundError):
            check_report(self.report().with_name("missing.xml"), "Example")


if __name__ == "__main__":
    unittest.main(verbosity=2)
