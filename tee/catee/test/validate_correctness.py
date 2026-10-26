#!/usr/bin/env python3
"""Build and run CATEE correctness checks; hardware tests must not be skipped."""
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[3]


def check_report(path, class_name, required_method=None):
    suite = ET.parse(path).getroot()
    cases = suite.findall("testcase")
    count = int(suite.get("tests", "0"))
    if suite.tag != "testsuite" or count <= 0 or count != len(cases):
        raise ValueError("Missing or incomplete test results")
    if any(int(suite.get(field, "0")) for field in ("errors", "failures", "skipped")):
        raise ValueError("Test suite contains errors, failures, or skipped tests")
    if any(case.get("classname") != class_name or any(case.find(tag) is not None
           for tag in ("error", "failure", "skipped")) for case in cases):
        raise ValueError("Unexpected, failed, or skipped test case")
    if required_method and not any(case.get("name", "").split("-")[0] == required_method for case in cases):
        raise ValueError("Required hardware test did not execute")
    return count


def check_python_report(text):
    counts = [int(value) for value in re.findall(r"Ran (\d+) tests?", text)]
    if not counts or any(count <= 0 for count in counts) or re.search(r"skipped=[1-9]\d*", text):
        raise ValueError("Missing, empty or skipped Python test results")
    return sum(counts)


def run(label, command, output):
    log = output / (label + ".log")
    print(f"Running {label} (log: {log})", flush=True)
    with log.open("w") as stream:
        subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                       check=True, timeout=1800)
    return log


def main():
    runs = ROOT / "tee/catee/build/validation"
    runs.mkdir(parents=True, exist_ok=True)
    output = pathlib.Path(tempfile.mkdtemp(prefix="run-", dir=runs))
    print(f"Validation output: {output}", flush=True)
    try:
        for label, script in (("runner-tests", "test_validate_correctness.py"),
                              ("benchmark-tests", "test_benchmark_abac.py")):
            log = run(label, [sys.executable, "tee/catee/test/" + script], output)
            check_python_report(log.read_text())
        analysis_python = ROOT / "tee/catee/.local/analysis-venv/bin/python"
        if not analysis_python.exists(): analysis_python = pathlib.Path(sys.executable)
        log = run("analysis-tests", [str(analysis_python), "tee/catee/test/test_analyze_benchmark.py"], output)
        check_python_report(log.read_text())
        run("build-test", ["ant", "build-test"], output)
        for target in ("test", "test-java"):
            log = run("native-" + target, ["make", "-C", "tee/catee", target], output)
            check_python_report(log.read_text())
        run("native-sanitizers", ["make", "-C", "tee/catee", "test-admin-sanitized"], output)
        reports = output / "junit"
        total = 0
        suites = [
            ("CateeAdministrationTest", None),
            ("CateeEnvironmentConfigurationTest", None),
            ("CateeRoleLifecycleTest", None),
            ("CateeAuthorizerTest", "hardwareServiceDrivesPolicyAndFailureBlocksRbac"),
            ("CateeCqlTest", "cqlUsesEnclaveAndDoesNotReuseStalePermissions"),
            ("CateeConcurrentCqlTest", "concurrentQueriesObservePolicyChanges"),
            ("org.apache.cassandra.cql3.statements.CreateRoleStatementTest", None),
            ("org.apache.cassandra.audit.AuditLogEntryTest", None),
            ("AbacConfigurationLoaderTest", None),
            ("FixedEnvironmentAttributeProviderTest", None),
        ]
        # Cassandra tests share build/test/cassandra: run sequentially, never concurrently.
        with tempfile.TemporaryDirectory(prefix="catee-validate-", dir="/tmp") as directory:
            for short_name, required_method in suites:
                class_name = short_name if "." in short_name else "org.apache.cassandra.auth." + short_name
                short_name = class_name.rsplit(".", 1)[-1]
                options = "-Dcatee.test.hardware=true"
                if short_name in ("CateeCqlTest", "CateeConcurrentCqlTest"):
                    options += f" -Dcassandra.catee.socket={directory}/catee.sock"
                run(short_name, ["ant", "testsome", "-Dno-build-test=true", f"-Dtest.name={class_name}",
                                 f"-Dbuild.test.output.dir={reports}", f"-Dtest.jvm.args={options}"], output)
                count = check_report(reports / f"TEST-{class_name}.xml", class_name, required_method)
                total += count
                print(f"PASS {short_name}: {count} tests, no skips", flush=True)
        smoke = output / "benchmark-smoke"
        run("benchmark-smoke", [sys.executable, "tee/catee/test/benchmark_abac.py", str(smoke), "--smoke", "--repeats", "1"], output)
        run("analysis-smoke", [str(analysis_python), "tee/catee/test/analyze_benchmark.py", str(smoke),
                               str(output / "smoke-analysis"), "--allow-smoke"], output)
        print(f"PASS: build, native/hardware/Java suites, and {total} Cassandra tests; no skips.")
        print(f"Logs and fresh JUnit reports: {output}")
        return 0
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}\nInspect logs: {output}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
