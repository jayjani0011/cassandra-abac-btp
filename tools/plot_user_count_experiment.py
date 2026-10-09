#!/usr/bin/env python3
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: plot_user_count_experiment.py <experiment-output-directory>")

    output = Path(sys.argv[1])
    grouped = defaultdict(list)
    with (output / "run_summary.csv").open(newline="") as stream:
        for row in csv.DictReader(stream):
            grouped[(row["profile"], int(row["users"]))].append(float(row["mean_ns"]) / 1000.0)
    profiles = sorted({profile for profile, _ in grouped}, key=lambda profile: int(profile[1:]))

    plt.figure(figsize=(10, 6))
    for profile in profiles:
        user_counts = sorted(count for item_profile, count in grouped if item_profile == profile)
        means = [statistics.mean(grouped[(profile, count)]) for count in user_counts]
        deviations = [statistics.pstdev(grouped[(profile, count)]) for count in user_counts]
        plt.errorbar(user_counts, means, yerr=deviations, marker="o", capsize=3, label=profile)
    plt.xlabel("Number of users")
    plt.ylabel("Authorization time per call (microseconds)")
    plt.title("ABAC authorization time versus user count")
    plt.grid(True, alpha=0.25)
    plt.legend(title="Base profile")
    plt.tight_layout()
    plt.savefig(output / "authorization_time_vs_user_count.png", dpi=200)
    plt.close()

    figure, axes = plt.subplots(2, 3, figsize=(14, 8), sharey=True)
    for axis, profile in zip(axes.flat, profiles):
        user_counts = sorted(count for item_profile, count in grouped if item_profile == profile)
        axis.boxplot([grouped[(profile, count)] for count in user_counts], tick_labels=user_counts, showmeans=True)
        axis.set_title(profile)
        axis.set_xlabel("Users")
        axis.grid(axis="y", alpha=0.25)
    for axis in axes[:, 0]:
        axis.set_ylabel("Authorization time per call (microseconds)")
    figure.suptitle("Variation across retained timed runs")
    figure.tight_layout()
    figure.savefig(output / "authorization_time_run_distribution.png", dpi=200)


if __name__ == "__main__":
    main()
