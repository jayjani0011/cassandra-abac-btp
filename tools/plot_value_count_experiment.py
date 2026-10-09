#!/usr/bin/env python3
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: plot_value_count_experiment.py <experiment-output-directory>")

    output = Path(sys.argv[1])
    grouped = defaultdict(list)
    with (output / "run_summary.csv").open(newline="") as stream:
        for row in csv.DictReader(stream):
            grouped[(row["profile"], int(row["values_per_attribute"]))].append(float(row["mean_ns"]) / 1000.0)
    profiles = sorted({profile for profile, _ in grouped}, key=lambda profile: int(profile[1:]))

    plt.figure(figsize=(10, 6))
    for profile in profiles:
        value_counts = sorted(count for item_profile, count in grouped if item_profile == profile)
        means = [statistics.mean(grouped[(profile, count)]) for count in value_counts]
        deviations = [statistics.pstdev(grouped[(profile, count)]) for count in value_counts]
        plt.errorbar(value_counts, means, yerr=deviations, marker="o", capsize=3, label=profile)
    plt.xscale("symlog", linthresh=1)
    plt.xticks([2, 4, 8, 16, 32, 64], [2, 4, 8, 16, 32, 64])
    plt.xlabel("Values per attribute")
    plt.ylabel("Authorization time per call (microseconds)")
    plt.title("ABAC authorization time versus attribute-value domain size")
    plt.grid(True, alpha=0.25)
    plt.legend(title="Base profile")
    plt.tight_layout()
    plt.savefig(output / "authorization_time_vs_value_count.png", dpi=200)
    plt.close()

    figure, axes = plt.subplots(2, 3, figsize=(14, 8), sharey=True)
    for axis, profile in zip(axes.flat, profiles):
        value_counts = sorted(count for item_profile, count in grouped if item_profile == profile)
        axis.boxplot([grouped[(profile, count)] for count in value_counts], tick_labels=value_counts, showmeans=True)
        axis.set_title(profile)
        axis.set_xlabel("Values per attribute")
        axis.grid(axis="y", alpha=0.25)
    for axis in axes[:, 0]:
        axis.set_ylabel("Authorization time per call (microseconds)")
    figure.suptitle("Variation across retained timed runs")
    figure.tight_layout()
    figure.savefig(output / "authorization_time_run_distribution.png", dpi=200)


if __name__ == "__main__":
    main()
