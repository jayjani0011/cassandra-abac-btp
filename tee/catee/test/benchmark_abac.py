#!/usr/bin/env python3
"""Compare direct ABAC authorization with/without CATEE in fresh JVMs."""
import argparse
import csv
import datetime
import hashlib
import json
import os
import pathlib
import platform
import random
import subprocess
import sys
import tempfile
import time

from test_service import SERVICE, ENCLAVE, exchange
from validate_correctness import ROOT, check_report, run

CLASS = "org.apache.cassandra.auth.CateeBenchmarkTest"
SCENARIOS = {"control", "worktime_true", "worktime_false"}
SEED = 20261022


def within_worktime():
    return 9 <= datetime.datetime.now().hour < 17


def validate_samples(path, mode, repeat, batches, calls):
    seen = set()
    decisions = {}
    with path.open(newline="") as stream:
        for row in csv.DictReader(stream):
            key = (row["scenario"], int(row["batch"]), int(row["sample"]))
            if (row["mode"] != mode or int(row["repeat"]) != repeat or key[0] not in SCENARIOS
                    or not 1 <= key[1] <= batches or not 1 <= key[2] <= calls or key in seen
                    or int(row["elapsed_ns"]) <= 0 or row["select_granted"] not in {"true", "false"}):
                raise ValueError("Invalid or duplicate benchmark sample")
            seen.add(key)
            previous = decisions.setdefault(key[0], row["select_granted"])
            if previous != row["select_granted"]:
                raise ValueError("Authorization decisions changed within a point")
    if len(seen) != len(SCENARIOS) * batches * calls:
        raise ValueError("Incomplete benchmark samples")
    if decisions["control"] != "true" or decisions["worktime_true"] == decisions["worktime_false"]:
        raise ValueError("Incorrect benchmark decisions")
    return decisions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=pathlib.Path, help="new output directory; never overwritten")
    parser.add_argument("--smoke", action="store_true", help="validation only: 1 warmup, 2 batches, 3 calls")
    parser.add_argument("--repeats", type=int, help="independent JVM pairs (default: 3 full, 1 smoke)")
    args = parser.parse_args()
    repeats = args.repeats if args.repeats is not None else (1 if args.smoke else 3)
    if not 1 <= repeats <= 100:
        parser.error("repeats must be between 1 and 100")
    output = args.output.resolve()
    historical = ROOT / "benchmarks/abac-experiments"
    if output == historical or historical in output.parents or any(ch.isspace() for ch in str(output)):
        parser.error("use a new path without whitespace outside historical ABAC results")
    output.mkdir(parents=True, exist_ok=False)
    batches, calls = (2, 3) if args.smoke else (100, 100)
    metadata = dict(status="incomplete", kind="smoke" if args.smoke else "measurement", repeats=repeats,
                    warmup_batches=1 if args.smoke else 10, retained_batches=batches, calls_per_batch=calls,
                    method="CassandraAuthorizer.getAbacPermissions", configuration_loads_per_point=1,
                    policy=dict(rules=1, user_conditions=1, resource_conditions=1, scenarios=sorted(SCENARIOS)),
                    timed_interval="one getAbacPermissions call; setup, assertions, CSV writes and warmup excluded",
                    logging="root and CassandraAuthorizer WARN",
                    cache="direct method; final permissions cache and RBAC excluded", seed=SEED,
                    time_window="09:00 inclusive to 17:00 exclusive; host local time", sgx="HW DEBUG",
                    host=platform.node(), kernel=platform.release(), architecture=platform.machine(),
                    started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    timezone=time.tzname, sgx_sdk=os.environ.get("SGX_SDK", str(pathlib.Path.home() / "sgxsdk")))
    metadata_path = output / "metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
    try:
        metadata["git_commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        metadata["git_status"] = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
        metadata["java_version"] = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True)
        metadata["ant_version"] = subprocess.check_output(["ant", "-version"], text=True)
        metadata["python_version"] = platform.python_version()
        sdk_config = pathlib.Path(metadata["sgx_sdk"]) / "pkgconfig/libsgx_urts.pc"
        metadata["sgx_sdk_version"] = next(line.split(":", 1)[1].strip() for line in sdk_config.read_text().splitlines()
                                           if line.startswith("Version:"))
        metadata["sgx_packages"] = subprocess.check_output(
            ["dpkg-query", "-W", "-f=${Package}=${Version}\n", "libsgx-urts", "sgx-aesm-service"], text=True)
        metadata["cpu"] = next(line.split(":", 1)[1].strip() for line in pathlib.Path("/proc/cpuinfo").read_text().splitlines()
                               if line.startswith("model name"))
        run("benchmark-tests", [sys.executable, "tee/catee/test/test_benchmark_abac.py"], output)
        run("build-test", ["ant", "build-test"], output)
        run("native-build", ["make", "-C", "tee/catee", "all"], output)
        metadata["enclave_sha256"] = hashlib.sha256(ENCLAVE.read_bytes()).hexdigest()
        metadata["service_sha256"] = hashlib.sha256(SERVICE.read_bytes()).hexdigest()
        initial_worktime = within_worktime()
        metadata["within_worktime"] = initial_worktime
        metadata["execution_order"] = []
        randomizer = random.Random(SEED)
        reference = None
        with tempfile.TemporaryDirectory(prefix="catee-bench-", dir="/tmp") as directory, (output / "service.log").open("w") as log:
            socket = pathlib.Path(directory) / "catee.sock"
            state = pathlib.Path(directory) / "admin.sealed"
            process = subprocess.Popen([str(SERVICE), str(socket), str(ENCLAVE), str(state), "--init"],
                                       cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if process.poll() is not None:
                        raise RuntimeError("Hardware service failed to start")
                    try:
                        if exchange(socket, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]) == b"ALLOW HW DEBUG\n":
                            break
                    except OSError:
                        time.sleep(0.05)
                else:
                    raise RuntimeError("Hardware service did not become ready")
                for repeat in range(1, repeats + 1):
                    modes = ["native", "sgx"]
                    randomizer.shuffle(modes)
                    for mode in modes:
                        metadata["execution_order"].append(dict(repeat=repeat, mode=mode))
                        stem = f"repeat-{repeat:03d}-{mode}"
                        raw = output / (stem + ".csv")
                        reports = output / (stem + "-junit")
                        options = (f"-Dcatee.benchmark=true -Dcatee.benchmark.mode={mode} "
                                   f"-Dcatee.benchmark.smoke={str(args.smoke).lower()} -Dcatee.benchmark.repeat={repeat} "
                                   f"-Dcatee.benchmark.seed={SEED} -Dcatee.benchmark.output={raw}")
                        if mode == "sgx":
                            options += f" -Dcassandra.catee.socket={socket}"
                        run(stem, ["ant", "testsome", "-Dno-build-test=true", f"-Dtest.name={CLASS}",
                                   f"-Dbuild.test.output.dir={reports}", f"-Dtest.jvm.args={options}"], output)
                        check_report(reports / f"TEST-{CLASS}.xml", CLASS, "measureEquivalentAbacAndCateePolicies")
                        decisions = validate_samples(raw, mode, repeat, batches, calls)
                        if decisions["worktime_true"] != str(initial_worktime).lower():
                            raise ValueError("Working-hours observation changed; discard this run")
                        if reference is not None and decisions != reference:
                            raise ValueError("Modes/repetitions produced different decisions")
                        reference = decisions
                        if within_worktime() != initial_worktime:
                            raise ValueError("Working-hours boundary crossed; discard this run")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        metadata["status"] = "complete"
        metadata["decisions"] = reference
        print(f"PASS: {metadata['kind']} benchmark; output: {output}")
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        metadata["error"] = str(error)
        print(f"FAIL: {error}; partial output: {output}", file=sys.stderr)
        return 1
    finally:
        metadata["finished_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    sys.exit(main())
