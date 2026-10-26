#!/usr/bin/env python3
"""Check statistics against known values and reject invalid benchmark inputs."""
import csv
import json
import math
import pathlib
import tempfile
import subprocess
import sys
import unittest

from analyze_benchmark import analyze, sample_statistics


class AnalysisTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = pathlib.Path(directory.name)
        self.metadata = dict(status="complete", kind="smoke", repeats=2, warmup_batches=1,
                             retained_batches=2, calls_per_batch=3,
                             decisions={"control": "true", "worktime_true": "true", "worktime_false": "false"})
        self.save_metadata()
        for repeat, native, sgx in [(1, 100, 200), (2, 200, 300)]:
            for mode, elapsed in [("native", native), ("sgx", sgx)]:
                with (self.path / f"repeat-{repeat:03d}-{mode}.csv").open("w", newline="") as stream:
                    writer = csv.writer(stream)
                    writer.writerow(["repeat", "mode", "scenario", "batch", "sample", "elapsed_ns", "select_granted"])
                    for scenario, decision in self.metadata["decisions"].items():
                        for batch in range(1, 3):
                            for sample in range(1, 4):
                                writer.writerow([repeat, mode, scenario, batch, sample, elapsed, decision])

    def save_metadata(self):
        (self.path / "metadata.json").write_text(json.dumps(self.metadata))

    def test_known_means_variability_and_paired_overhead(self):
        _, batches, repeats, points, comparisons = analyze(self.path, allow_smoke=True)
        self.assertEqual(24, len(batches))
        self.assertEqual(12, len(repeats))
        self.assertTrue(all(row["samples"] == 3 for row in batches))
        self.assertTrue(all(row["samples"] == 6 for row in repeats))
        for row in points:
            self.assertEqual(150 if row["mode"] == "native" else 250, row["mean_ns"])
            self.assertAlmostEqual(math.sqrt(5000), row["repetition_mean_sd_ns"])
        for row in comparisons:
            self.assertEqual(1.75, row["paired_mean_ratio"])
            self.assertEqual(75, row["overhead_percent"])
            self.assertEqual(100, row["paired_mean_delta_ns"])
            self.assertAlmostEqual(math.sqrt(1250), row["overhead_sd_percent"])

    def test_nearest_rank_p95(self):
        stats = sample_statistics(list(range(1, 101)))
        self.assertEqual(50.5, stats["mean_ns"])
        self.assertEqual(50.5, stats["median_ns"])
        self.assertEqual(95, stats["p95_ns"])

    def test_smoke_requires_explicit_opt_in(self):
        with self.assertRaises(ValueError):
            analyze(self.path)

    def test_incomplete_or_wrong_protocol_is_rejected(self):
        for field, value in [("status", "incomplete"), ("kind", "unknown"), ("retained_batches", 100), ("repeats", 0)]:
            original = self.metadata[field]
            self.metadata[field] = value
            self.save_metadata()
            with self.subTest(field=field), self.assertRaises(ValueError):
                analyze(self.path, allow_smoke=True)
            self.metadata[field] = original

    def test_missing_repetition_is_rejected(self):
        (self.path / "repeat-002-sgx.csv").unlink()
        with self.assertRaises(ValueError):
            analyze(self.path, allow_smoke=True)

    def test_duplicate_sample_is_rejected(self):
        path = self.path / "repeat-001-sgx.csv"
        with path.open("a") as stream:
            stream.write(path.read_text().splitlines()[1] + "\n")
        with self.assertRaises(ValueError):
            analyze(self.path, allow_smoke=True)

    def test_single_repetition_does_not_invent_variability(self):
        self.metadata["repeats"] = 1
        self.save_metadata()
        for mode in ("native", "sgx"):
            (self.path / f"repeat-002-{mode}.csv").unlink()
        _, _, _, points, comparisons = analyze(self.path, allow_smoke=True)
        self.assertTrue(all(row["repetition_mean_sd_ns"] is None for row in points))
        self.assertTrue(all(row["overhead_sd_percent"] is None for row in comparisons))

    def test_cli_exports_figures_and_refuses_overwrite(self):
        output = self.path / "analysis-output"
        command = [sys.executable, str(pathlib.Path(__file__).with_name("analyze_benchmark.py")),
                   str(self.path), str(output), "--allow-smoke"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads((output / "analysis.json").read_text())
        self.assertEqual("complete", report["status"])
        self.assertEqual("smoke", report["kind"])
        for figure in ["latency", "overhead"]:
            self.assertTrue((output / (figure + ".png")).read_bytes().startswith(b"\x89PNG\r\n\x1a\n"))
            self.assertIn("SMOKE", (output / (figure + ".svg")).read_text())
        for name in ["batch_summary.csv", "repeat_summary.csv", "point_summary.csv", "comparison.csv"]:
            self.assertGreater(len((output / name).read_text().splitlines()), 1)
        original = {path.name: path.read_bytes() for path in output.iterdir()}
        self.assertNotEqual(0, subprocess.run(command, capture_output=True, timeout=30).returncode)
        self.assertEqual(original, {path.name: path.read_bytes() for path in output.iterdir()})

    def test_cli_refuses_historical_output(self):
        from validate_correctness import ROOT
        # Use the existing directory: even a missing guard must not create a
        # test directory inside historical results before the assertion fails.
        output = ROOT / "benchmarks/abac-experiments"
        self.assertTrue(output.is_dir())
        result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("analyze_benchmark.py")),
                                 str(self.path), str(output), "--allow-smoke"], capture_output=True, timeout=30)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"Historical ABAC results cannot be an analysis output", result.stderr)
        self.assertTrue(output.is_dir())


if __name__ == "__main__":
    unittest.main(verbosity=2)
