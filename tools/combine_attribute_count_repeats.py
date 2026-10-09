#!/usr/bin/env python3
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def read_rows(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def main():
    if len(sys.argv) < 4:
        raise SystemExit("Usage: combine_attribute_count_repeats.py <output-directory> <repeat-directory>...")

    output = Path(sys.argv[1])
    repeats = [Path(value) for value in sys.argv[2:]]
    output.mkdir(parents=True, exist_ok=True)

    raw_rows = []
    run_rows = []
    for repeat, directory in enumerate(repeats, start=1):
        for row in read_rows(directory / "raw_authorizations.csv"):
            row["repeat"] = repeat
            raw_rows.append(row)
        for row in read_rows(directory / "run_summary.csv"):
            row["repeat"] = repeat
            run_rows.append(row)

    if not raw_rows or not run_rows:
        raise SystemExit("No timed measurements were found")

    raw_fields = ["repeat"] + [field for field in raw_rows[0] if field != "repeat"]
    run_fields = ["repeat"] + [field for field in run_rows[0] if field != "repeat"]
    write_rows(output / "raw_authorizations.csv", raw_fields, raw_rows)
    write_rows(output / "run_summary.csv", run_fields, run_rows)

    grouped = defaultdict(list)
    for row in run_rows:
        grouped[(row["profile"], row["attributes_per_entity"], row["point_id"])].append(float(row["mean_ns"]))

    summary_rows = []
    for (profile, attributes, point_id), values in sorted(grouped.items(), key=lambda item: (int(item[0][0][1:]), int(item[0][1]))):
        summary_rows.append({
            "profile": profile,
            "attributes_per_entity": attributes,
            "point_id": point_id,
            "measured_runs": len(values),
            "mean_ns": f"{statistics.mean(values):.3f}",
            "median_ns": f"{statistics.median(values):.3f}",
            "stddev_ns": f"{statistics.pstdev(values):.3f}",
            "min_ns": f"{min(values):.3f}",
            "max_ns": f"{max(values):.3f}",
        })
    summary_fields = ["profile", "attributes_per_entity", "point_id", "measured_runs", "mean_ns", "median_ns", "stddev_ns", "min_ns", "max_ns"]
    write_rows(output / "point_summary.csv", summary_fields, summary_rows)

    (output / "metadata.txt").write_text(
        "experiment=ABAC attribute-count repeated runs\n"
        f"repeat_count={len(repeats)}\n"
        "retained_batches_per_point_per_repeat=8\n"
        f"retained_batches_per_point_total={len(repeats) * 8}\n"
        "authorizations_per_batch=100\n"
        "timed_interval=one getAbacPermissions call; loading and warm-up calls excluded\n"
        "sources=\n" + "\n".join(str(directory) for directory in repeats) + "\n"
    )


def write_rows(path, fields, rows):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
