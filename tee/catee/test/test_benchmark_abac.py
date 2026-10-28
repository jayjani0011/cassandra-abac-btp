#!/usr/bin/env python3
"""Reject incomplete or incorrect benchmark data before comparing performance."""
import csv
import datetime
from unittest.mock import patch
import pathlib
import tempfile
import unittest

from benchmark_abac import validate_samples, within_worktime


class BenchmarkSamplesTest(unittest.TestCase):
    def rows(self):
        return [[1, "native", scenario, 1, 1, 100, decision] for scenario, decision in
                [("control", "true"), ("worktime_true", "false"), ("worktime_false", "true")]]

    def validate(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "raw.csv"
            with path.open("w", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(["repeat", "mode", "scenario", "batch", "sample", "elapsed_ns", "select_granted"])
                writer.writerows(rows)
            return validate_samples(path, "native", 1, 1, 1)

    def test_benchmark_window_includes_the_whole_ending_minute(self):
        for hour, minute, second, expected in [(8,59,59,False),(9,0,0,True),(16,59,59,True),
                                                (17,0,0,True),(17,0,59,True),(17,1,0,False)]:
            with self.subTest(hour=hour, minute=minute, second=second), patch("benchmark_abac.datetime") as clock:
                clock.datetime.now.return_value = datetime.datetime(2026,10,28,hour,minute,second)
                self.assertEqual(expected, within_worktime())

    def test_accepts_complete_consistent_results(self):
        self.assertEqual("true", self.validate(self.rows())["control"])

    def test_rejects_missing_or_duplicate_samples(self):
        rows = self.rows()
        for invalid in [rows[:-1], rows + [rows[0]], []]:
            with self.subTest(rows=invalid), self.assertRaises(ValueError):
                self.validate(invalid)

    def test_rejects_invalid_sample_fields(self):
        for field, value in [(0, 2), (1, "sgx"), (2, "unknown"), (3, 0), (4, 2), (5, 0), (6, "error")]:
            rows = self.rows()
            rows[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(rows)

    def test_rejects_incorrect_decisions(self):
        for index in [0, 1]:
            rows = self.rows()
            rows[index][6] = "false" if rows[index][6] == "true" else "true"
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.validate(rows)


if __name__ == "__main__":
    unittest.main(verbosity=2)
