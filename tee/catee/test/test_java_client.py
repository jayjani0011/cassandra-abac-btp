#!/usr/bin/env python3
"""Java IPC checks against the hardware service and controlled failing peers."""
import pathlib
import socket
import subprocess
import tempfile
import threading
import time
import unittest

from test_service import HardwareServiceTest as HardwareBase, ROOT, exchange


def java_client(path, minute=840, timeout=500):
    return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                           "org.apache.cassandra.auth.catee.CateeClientDriver",
                           str(path), str(minute), str(timeout)],
                          capture_output=True, text=True, timeout=5)


def java_set_window(path, start=600, end=660, timeout=500):
    return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                           "org.apache.cassandra.auth.catee.CateeClientDriver",
                           str(path), "SET_TIMEWINDOW", str(start), str(end), str(timeout)],
                          capture_output=True, text=True, timeout=5)


class JavaHardwareTest(HardwareBase):
    def test_java_configures_worktime_window(self):
        try:
            result = java_set_window(self.path, timeout=2000)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("OK HW DEBUG\n", result.stdout)
            for minute, expected in [(599, "DENY"), (600, "ALLOW"), (659, "ALLOW"), (660, "DENY"), (840, "DENY")]:
                result = java_client(self.path, minute, 2000)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(f"{expected} HW DEBUG\n", result.stdout)
            for start, end in [(-1, 60), (60, 60), (61, 60), (600, 1440)]:
                self.assertNotEqual(0, java_set_window(self.path, start, end).returncode)
            self.assertEqual("ALLOW HW DEBUG\n", java_client(self.path, 630, 2000).stdout)
        finally:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 540 1020\n"]))

    def test_java_passes_expected_value_to_enclave(self):
        for minute in [539, 540, 1019, 1020]:
            for expected in [False, True]:
                result = subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                                         "org.apache.cassandra.auth.catee.CateeClientDriver", str(self.path),
                                         "ENV", str(minute), str(expected).lower(), "2000"],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                matched = expected == (540 <= minute < 1020)
                self.assertEqual("ALLOW HW DEBUG\n" if matched else "DENY HW DEBUG\n", result.stdout)

    def test_java_sets_workdays(self):
        def configure(days):
            return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                                   "org.apache.cassandra.auth.catee.CateeClientDriver", str(self.path),
                                   "SET_WORKDAY", days, "2000"], capture_output=True, text=True, timeout=5)
        try:
            result = configure("1000001")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 840 0 - 1 1 1 0 0\n"]))
            self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 1 840 0 - 1 1 1 0 0\n"]))
            for invalid in ["", "111111", "11111111", "0000002"]:
                self.assertNotEqual(0, configure(invalid).returncode)
        finally:
            self.assertEqual(0, configure("0111110").returncode)

    def test_java_configures_named_ipv4_subnet(self):
        def configure(cidr):
            return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                                   "org.apache.cassandra.auth.catee.CateeClientDriver", str(self.path),
                                   "SET_SUBNET", "Java subnet", cidr, "2000"], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, configure("127.0.0.0/8").returncode)
        for cidr in ["localhost/8", "::1/128", "127.0.0.1/8", "256.0.0.0/8", "0.0.0.0/33", "1.2.3.4", "01.0.0.0/8"]:
            self.assertNotEqual(0, configure(cidr).returncode, cidr)

    def test_java_worktime_boundaries(self):
        for minute, expected in [(0, "DENY"), (539, "DENY"), (540, "ALLOW"),
                                 (840, "ALLOW"), (1019, "ALLOW"), (1020, "DENY"),
                                 (1200, "DENY"), (1439, "DENY")]:
            with self.subTest(minute=minute):
                result = java_client(self.path, minute, 2000)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(f"{expected} HW DEBUG\n", result.stdout)


class JavaFailureTest(unittest.TestCase):
    def test_absent_service_and_invalid_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "absent.sock"
            for minute, timeout in [(840, 500), (-1, 500), (1440, 500), (840, 0)]:
                result = java_client(path, minute, timeout)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)
            self.assertNotEqual(0, java_client("relative.sock").returncode)

    def test_window_update_absent_service_and_invalid_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "absent.sock"
            for start, end, timeout in [(600, 660, 500), (-1, 60, 500), (60, 60, 500),
                                        (61, 60, 500), (600, 1440, 500), (600, 660, 0)]:
                result = java_set_window(path, start, end, timeout)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def test_response_validation_and_deadline(self):
        cases = [(False, response, expected) for response, expected in [(b"ALLOW HW DEBUG\n", 0), (b"DENY HW DEBUG\n", 0),
                                   (b"ERROR enclave_failure\n", 1), (b"ALLOW\n", 1),
                                   (b"ALLOW HW DEBUG", 1), (b"\xff\n", 1),
                                   (b"x" * 65, 1), (None, 1), ("drip", 1)]]
        cases += [(True, response, expected) for response, expected in [
            (b"OK HW DEBUG\n", 0), (b"ALLOW HW DEBUG\n", 1), (b"DENY HW DEBUG\n", 1),
            (b"ERROR invalid_timewindow\n", 1), (b"OK\n", 1), (b"OK HW DEBUG", 1),
            (b"\xff\n", 1), (b"x" * 65, 1), (None, 1), ("drip", 1)]]
        for setting, response, expected in cases:
            with self.subTest(setting=setting, response=response), tempfile.TemporaryDirectory() as directory:
                path = pathlib.Path(directory) / "mock.sock"
                errors = []
                with socket.socket(socket.AF_UNIX) as server:
                    server.bind(str(path))
                    server.listen(1)
                    server.settimeout(4)

                    def serve():
                        try:
                            with server.accept()[0] as connection:
                                connection.settimeout(2)
                                request = bytearray()
                                while not request.endswith(b"\n"):
                                    chunk = connection.recv(32)
                                    if not chunk:
                                        raise RuntimeError("Missing Java request")
                                    request.extend(chunk)
                                expected_request = b"SET_TIMEWINDOW 600 660\n" if setting else b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"
                                if request != expected_request:
                                    raise RuntimeError("Incorrect Java request")
                                if response is None:
                                    time.sleep(0.8)
                                elif response == "drip":
                                    for ch in b"ALLOW HW DEBUG\n":
                                        connection.sendall(bytes([ch]))
                                        time.sleep(0.1)
                                else:
                                    for ch in response:
                                        connection.sendall(bytes([ch]))
                        except (BrokenPipeError, ConnectionResetError):
                            pass
                        except Exception as error:
                            errors.append(error)

                    worker = threading.Thread(target=serve)
                    worker.start()
                    try:
                        result = java_set_window(path) if setting else java_client(path)
                    finally:
                        worker.join(timeout=4)
                    self.assertFalse(worker.is_alive())
                    self.assertEqual([], errors)
                    self.assertEqual(expected, result.returncode, result.stderr)
                    if expected:
                        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    del HardwareBase  # Avoid running a duplicate copy of the imported suite.
    unittest.main(verbosity=2)
