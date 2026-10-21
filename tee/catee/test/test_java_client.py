#!/usr/bin/env python3
"""Java IPC checks against the hardware service and controlled failing peers."""
import pathlib
import socket
import subprocess
import tempfile
import threading
import time
import unittest

from test_service import HardwareServiceTest as HardwareBase, ROOT


def java_client(path, minute=840, timeout=500):
    return subprocess.run(["java", "-cp", f"{ROOT}/build/java:{ROOT}/build/java-deps/*",
                           "org.apache.cassandra.auth.catee.CateeClientDriver",
                           str(path), str(minute), str(timeout)],
                          capture_output=True, text=True, timeout=5)


class JavaHardwareTest(HardwareBase):
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

    def test_response_validation_and_deadline(self):
        for response, expected in [(b"ALLOW HW DEBUG\n", 0), (b"DENY HW DEBUG\n", 0),
                                   (b"ERROR enclave_failure\n", 1), (b"ALLOW\n", 1),
                                   (b"ALLOW HW DEBUG", 1), (b"\xff\n", 1),
                                   (b"x" * 65, 1), (None, 1), ("drip", 1)]:
            with self.subTest(response=response), tempfile.TemporaryDirectory() as directory:
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
                                if request != b"CHECK 840\n":
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
                        result = java_client(path)
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
