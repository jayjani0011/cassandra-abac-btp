#!/usr/bin/env python3
"""Summarize paired CATEE benchmarks and export latency/overhead figures."""
import argparse
from collections import defaultdict
import csv
import json
import math
import pathlib
import statistics
import sys

from benchmark_abac import SCENARIOS, validate_samples
from validate_correctness import ROOT


def sample_statistics(values):
    ordered = sorted(values)
    return dict(samples=len(values), mean_ns=statistics.mean(values), median_ns=statistics.median(values),
                p95_ns=ordered[math.ceil(0.95 * len(values)) - 1])


def deviation(values):
    return statistics.stdev(values) if len(values) > 1 else None


def analyze(directory, allow_smoke=False):
    metadata = json.loads((directory / "metadata.json").read_text())
    if metadata.get("status") != "complete" or metadata.get("kind") not in {"measurement", "smoke"}:
        raise ValueError("Only complete benchmark runs can be analyzed")
    if metadata["kind"] == "smoke" and not allow_smoke:
        raise ValueError("Smoke data is validation only; pass --allow-smoke explicitly")
    expected_protocol = (1, 2, 3) if metadata["kind"] == "smoke" else (10, 100, 100)
    protocol = tuple(metadata.get(key) for key in ("warmup_batches", "retained_batches", "calls_per_batch"))
    if protocol != expected_protocol or not isinstance(metadata.get("repeats"), int) or not 1 <= metadata["repeats"] <= 100:
        raise ValueError("Unexpected benchmark protocol")
    repeats, batches, calls = metadata["repeats"], protocol[1], protocol[2]
    expected_files = {f"repeat-{repeat:03d}-{mode}.csv" for repeat in range(1, repeats + 1) for mode in ("native", "sgx")}
    if {path.name for path in directory.glob("repeat-*-*.csv")} != expected_files:
        raise ValueError("Missing or unexpected repetition files")
    grouped = defaultdict(list)
    reference = metadata["decisions"]
    for repeat in range(1, repeats + 1):
        for mode in ("native", "sgx"):
            path = directory / f"repeat-{repeat:03d}-{mode}.csv"
            if validate_samples(path, mode, repeat, batches, calls) != reference:
                raise ValueError("Decisions differ between metadata, modes, or repetitions")
            with path.open(newline="") as stream:
                for row in csv.DictReader(stream):
                    grouped[(repeat, mode, row["scenario"], int(row["batch"]))].append(int(row["elapsed_ns"]))

    batch_rows, repeat_values = [], defaultdict(list)
    for (repeat, mode, scenario, batch), values in sorted(grouped.items()):
        batch_rows.append(dict(repeat=repeat, mode=mode, scenario=scenario, batch=batch, **sample_statistics(values)))
        repeat_values[(repeat, mode, scenario)].extend(values)
    repeat_rows, means = [], {}
    for (repeat, mode, scenario), values in sorted(repeat_values.items()):
        stats = sample_statistics(values)
        repeat_rows.append(dict(repeat=repeat, mode=mode, scenario=scenario, **stats))
        means[(repeat, mode, scenario)] = stats["mean_ns"]
    point_rows, comparison_rows = [], []
    for scenario in sorted(SCENARIOS):
        for mode in ("native", "sgx"):
            values = [means[(repeat, mode, scenario)] for repeat in range(1, repeats + 1)]
            point_rows.append(dict(scenario=scenario, mode=mode, repetitions=repeats,
                                   mean_ns=statistics.mean(values), repetition_mean_sd_ns=deviation(values)))
        native = [means[(repeat, "native", scenario)] for repeat in range(1, repeats + 1)]
        sgx = [means[(repeat, "sgx", scenario)] for repeat in range(1, repeats + 1)]
        ratios = [trusted / baseline for baseline, trusted in zip(native, sgx)]
        overheads = [(ratio - 1) * 100 for ratio in ratios]
        comparison_rows.append(dict(scenario=scenario, repetitions=repeats, paired_mean_ratio=statistics.mean(ratios),
                                    overhead_percent=statistics.mean(overheads), overhead_sd_percent=deviation(overheads),
                                    paired_mean_delta_ns=statistics.mean([trusted - baseline for baseline, trusted in zip(native, sgx)])))
    return metadata, batch_rows, repeat_rows, point_rows, comparison_rows


def write_csv(path, rows):
    with path.open("x", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def plot(output, metadata, points, comparisons):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    scenarios = sorted(SCENARIOS)
    labels = ["No worktime\ncondition", "worktime=false", "worktime=true"]
    footer = ("SMOKE: validation only. " if metadata["kind"] == "smoke" else "")
    footer += f"{metadata['repeats']} independent JVM pair(s); "
    footer += "error bars: sample SD across repetitions." if metadata["repeats"] > 1 else "SD unavailable with one repetition."
    plt.rcParams.update({"font.size": 10, "svg.fonttype": "none"})
    prefix = "SMOKE: " if metadata["kind"] == "smoke" else ""
    fig, ax = plt.subplots(figsize=(7.5, 4.5))
    for mode, offset, label, color in [("native", -0.19, "Native ABAC", "#4477AA"), ("sgx", 0.19, "CATEE (SGX HW debug)", "#EE7733")]:
        rows = [next(row for row in points if row["scenario"] == scenario and row["mode"] == mode) for scenario in scenarios]
        errors = [row["repetition_mean_sd_ns"] / 1000 for row in rows] if metadata["repeats"] > 1 else None
        ax.bar([index + offset for index in range(3)], [row["mean_ns"] / 1000 for row in rows],
               width=0.38, yerr=errors, capsize=4, label=label, color=color)
    ax.set_xticks(range(3), labels)
    ax.set_ylabel("Mean authorization latency (µs)")
    ax.set_title(prefix + "Direct getAbacPermissions() latency")
    ax.legend()
    ax.grid(axis="y", alpha=0.2)
    ax.set_axisbelow(True)
    fig.text(0.5, 0.02, footer, ha="center", fontsize=8)
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    for extension in ("svg", "png"):
        fig.savefig(output / f"latency.{extension}", dpi=180)
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(7.5, 4.5))
    errors = [row["overhead_sd_percent"] for row in comparisons] if metadata["repeats"] > 1 else None
    ax.bar(range(3), [row["overhead_percent"] for row in comparisons], yerr=errors, capsize=4, color="#228833")
    ax.set_xticks(range(3), labels)
    ax.set_ylabel("Paired mean overhead (%)")
    ax.set_title(prefix + "CATEE overhead (SGX HW debug)")
    ax.axhline(0, color="black", linewidth=0.8)
    ax.grid(axis="y", alpha=0.2)
    ax.set_axisbelow(True)
    fig.text(0.5, 0.02, footer, ha="center", fontsize=8)
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    for extension in ("svg", "png"):
        fig.savefig(output / f"overhead.{extension}", dpi=180)
    plt.close(fig)
    return matplotlib.__version__


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path, help="new analysis directory")
    parser.add_argument("--allow-smoke", action="store_true", help="label all output as validation only")
    args = parser.parse_args()
    try:
        output = args.output.resolve()
        historical = ROOT / "benchmarks/abac-experiments"
        if output == historical or historical in output.parents:
            raise ValueError("Historical ABAC results cannot be an analysis output")
        metadata, batches, repeats, points, comparisons = analyze(args.input.resolve(), args.allow_smoke)
        output.mkdir(parents=True, exist_ok=False)
        for name, rows in [("batch_summary", batches), ("repeat_summary", repeats), ("point_summary", points), ("comparison", comparisons)]:
            write_csv(output / (name + ".csv"), [dict(kind=metadata["kind"], **row) for row in rows])
        matplotlib_version = plot(output, metadata, points, comparisons)
        (output / "analysis.json").write_text(json.dumps(dict(status="complete", kind=metadata["kind"],
            source=str(args.input.resolve()), repetitions=metadata["repeats"], matplotlib_version=matplotlib_version,
            statistics="means and sample SD across independent repetitions; overhead is mean of paired ratios; no confidence intervals"), indent=2) + "\n")
        print(f"PASS: {metadata['kind']} analysis; output: {output}")
        return 0
    except (OSError, ValueError, KeyError, ImportError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
