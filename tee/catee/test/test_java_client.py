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


def java_admin(path, operation, role="alice", mask=1, timeout=2000):
    return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                           "org.apache.cassandra.auth.catee.CateeClientDriver",
                           str(path), operation, role, str(mask), str(timeout)],
                          capture_output=True, text=True, timeout=5)


def java_lifecycle(path, operation, role="alice", timeout=2000):
    return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                           "org.apache.cassandra.auth.catee.CateeClientDriver",
                           str(path), operation, role, str(timeout)],
                          capture_output=True, text=True, timeout=5)


def java_set_window(path, start=600, end=660, timeout=500):
    return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                           "org.apache.cassandra.auth.catee.CateeClientDriver",
                           str(path), "SET_TIMEWINDOW", str(start), str(end), str(timeout)],
                          capture_output=True, text=True, timeout=5)


class JavaHardwareTest(HardwareBase):
    def test_java_admin_operations(self):
        for role in ["alice", "Research team जय", "a" * 255]:
            for operation, mask, expected in [("CHECK", 1, "DENY"), ("GRANT", 1, "OK"),
                                               ("GRANT", 2147483648, "OK"), ("CHECK", 1, "ALLOW"),
                                               ("REVOKE", 1, "OK"), ("CHECK", 1, "DENY"),
                                               ("CHECK", 2147483648, "ALLOW"),
                                               ("REVOKE", 2147483648, "OK")]:
                with self.subTest(role=role, operation=operation, mask=mask):
                    result = java_admin(self.path, operation, role, mask)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(f"{expected} HW DEBUG\n", result.stdout)
        self.assertEqual("DENY HW DEBUG\n", java_admin(self.path, "CHECK", "Alice").stdout)
        self.assertEqual(0, java_admin(self.path, "REVOKE", "missing").returncode)

    def test_java_role_lifecycle(self):
        for role in ["alice", "Research team जय", "a" * 255]:
            self.assertEqual(0, java_admin(self.path, "GRANT", role, 3).returncode)
            self.assertNotEqual(0, java_lifecycle(self.path, "FINISH_ROLE_DELETE", role).returncode)
            for _ in range(2):
                result = java_lifecycle(self.path, "BEGIN_ROLE_DELETE", role)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("OK HW DEBUG\n", result.stdout)
            self.assertEqual("DENY HW DEBUG\n", java_admin(self.path, "CHECK", role).stdout)
            self.assertNotEqual(0, java_admin(self.path, "GRANT", role).returncode)
            self.assertEqual(0, java_admin(self.path, "REVOKE", role).returncode)
            self.assertNotEqual(0, java_admin(self.path, "GRANT", role).returncode)
            for _ in range(2):
                self.assertEqual(0, java_lifecycle(self.path, "FINISH_ROLE_DELETE", role).returncode)
            self.assertEqual("DENY HW DEBUG\n", java_admin(self.path, "CHECK", role).stdout)
            self.assertEqual(0, java_admin(self.path, "GRANT", role).returncode)
        for operation in [b"BEGIN_ROLE_DELETE", b"FINISH_ROLE_DELETE"]:
            for suffix in [b"", b" ", b" 00", b" 6", b" zz", b" 61 1", b" " + b"61" * 256]:
                self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [operation + suffix + b"\n"]))

    def test_admin_malformed_requests(self):
        for request in [b"GRANT_ADMIN_PRIV 61 0\n", b"GRANT_ADMIN_PRIV 00 1\n",
                        b"GRANT_ADMIN_PRIV 6 1\n", b"GRANT_ADMIN_PRIV zz 1\n",
                        b"GRANT_ADMIN_PRIV 61 -1\n", b"GRANT_ADMIN_PRIV 61 4294967296\n",
                        b"CHECK_ADMIN_PRIV 61 1 extra\n", b"REVOKE_ADMIN_PRIV 61\n",
                        b"GRANT_ADMIN_PRIV " + b"61" * 256 + b" 1\n",
                        b"GRANT_ADMIN_PRIV " + b"x" * 544 + b"\n"]:
            with self.subTest(request=request):
                self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [request]))
        self.assertEqual(b"OK HW DEBUG\n",
                         exchange(self.path, [b"GRANT_ADMIN_", b"PRIV 667261676d656e74", b" 1\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 667261676d656e74 1\n"]))

    def test_java_z_admin_capacity(self):
        failed_role = None
        for i in range(33):
            role = f"capacity{i}"
            result = java_admin(self.path, "GRANT", role)
            if result.returncode:
                self.assertEqual("", result.stdout)
                failed_role = role
                break
        self.assertIsNotNone(failed_role)
        self.assertEqual("DENY HW DEBUG\n", java_admin(self.path, "CHECK", failed_role).stdout)
        self.assertEqual(0, java_admin(self.path, "GRANT", "capacity0", 2).returncode)

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

    def test_java_worktime_boundaries(self):
        for minute, expected in [(0, "DENY"), (539, "DENY"), (540, "ALLOW"),
                                 (840, "ALLOW"), (1019, "ALLOW"), (1020, "DENY"),
                                 (1200, "DENY"), (1439, "DENY")]:
            with self.subTest(minute=minute):
                result = java_client(self.path, minute, 2000)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(f"{expected} HW DEBUG\n", result.stdout)


class JavaFailureTest(unittest.TestCase):
    def test_admin_invalid_inputs_and_absent_service(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "absent.sock"
            for operation in ["GRANT", "REVOKE", "CHECK"]:
                for role, mask in [("alice", 1), ("", 1), ("a" * 256, 1), ("é" * 128, 1), ("alice", 0)]:
                    result = java_admin(path, operation, role, mask)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", result.stdout)

    def test_lifecycle_invalid_inputs_and_absent_service(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "absent.sock"
            for operation in ["BEGIN_ROLE_DELETE", "FINISH_ROLE_DELETE"]:
                for role in ["alice", "", "a" * 256, "é" * 128]:
                    result = java_lifecycle(path, operation, role)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", result.stdout)

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
        cases = [(b"ALLOW HW DEBUG\n", 0), (b"DENY HW DEBUG\n", 0),
                                   (b"ERROR enclave_failure\n", 1), (b"ALLOW\n", 1),
                                   (b"ALLOW HW DEBUG", 1), (b"\xff\n", 1),
                                   (b"x" * 65, 1), (None, 1), ("drip", 1)]
        cases = [(None, response, expected) for response, expected in cases] + [
            ("GRANT", b"OK HW DEBUG\n", 0), ("REVOKE", b"OK HW DEBUG\n", 0),
            ("GRANT", b"ALLOW HW DEBUG\n", 1), ("REVOKE", b"DENY HW DEBUG\n", 1),
            ("CHECK", b"OK HW DEBUG\n", 1), ("CHECK", b"ERROR enclave_failure\n", 1),
            ("GRANT", None, 1), ("REVOKE", b"ERROR admin_operation_failed\n", 1)]
        cases += [(operation, response, expected)
                  for operation in ["BEGIN_ROLE_DELETE", "FINISH_ROLE_DELETE"]
                  for response, expected in [(b"OK HW DEBUG\n", 0), (b"ALLOW HW DEBUG\n", 1),
                                             (b"DENY HW DEBUG\n", 1), (b"ERROR persistence_failure\n", 1),
                                             (b"OK HW DEBUG", 1), (b"x" * 65, 1), (None, 1)]]
        cases += [("SET_TIMEWINDOW", response, expected) for response, expected in [
            (b"OK HW DEBUG\n", 0), (b"ALLOW HW DEBUG\n", 1), (b"DENY HW DEBUG\n", 1),
            (b"ERROR invalid_timewindow\n", 1), (b"OK\n", 1), (b"OK HW DEBUG", 1),
            (b"\xff\n", 1), (b"x" * 65, 1), (None, 1), ("drip", 1)]]
        for operation, response, expected in cases:
            with self.subTest(operation=operation, response=response), tempfile.TemporaryDirectory() as directory:
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
                                expected_request = (f"{operation}_ADMIN_PRIV 616c696365 1\n".encode()
                                                    if operation else b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n")
                                if operation in ["BEGIN_ROLE_DELETE", "FINISH_ROLE_DELETE"]:
                                    expected_request = f"{operation} 616c696365\n".encode()
                                if operation == "SET_TIMEWINDOW":
                                    expected_request = b"SET_TIMEWINDOW 600 660\n"
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
                        if operation == "SET_TIMEWINDOW":
                            result = java_set_window(path, timeout=500)
                        elif operation in ["BEGIN_ROLE_DELETE", "FINISH_ROLE_DELETE"]:
                            result = java_lifecycle(path, operation, timeout=500)
                        else:
                            result = java_admin(path, operation, timeout=500) if operation else java_client(path)
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
