#!/usr/bin/env python3

"""Integration checks against the real hardware enclave, in a temporary directory."""
import pathlib
import socket
import subprocess
import tempfile
import time
import unittest

from client import check_time

ROOT = pathlib.Path(__file__).resolve().parents[1]
SERVICE = ROOT / "build/catee-service"
ENCLAVE = ROOT / "build/enclave.signed.so"


def exchange(path, fragments):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(3)
        connection.connect(str(path))
        for fragment in fragments:
            connection.sendall(fragment)
        result = bytearray()
        while len(result) < 64:
            ch = connection.recv(1)
            if not ch:
                raise RuntimeError("Incomplete service response")
            result.extend(ch)
            if ch == b"\n":
                return bytes(result)
        raise RuntimeError("Oversized service response")


class HardwareServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="catee-service-")
        cls.addClassCleanup(cls.directory.cleanup)
        cls.path = pathlib.Path(cls.directory.name) / "catee.sock"
        cls.log = tempfile.TemporaryFile(mode="w+")
        cls.addClassCleanup(cls.log.close)
        cls.service = subprocess.Popen([str(SERVICE), str(cls.path), str(ENCLAVE)],
                                       stdout=cls.log, stderr=subprocess.STDOUT)
        cls.addClassCleanup(cls.stop_service)
        for _ in range(100):
            if cls.service.poll() is not None:
                cls.log.seek(0)
                raise RuntimeError(cls.log.read())
            try:
                if check_time(str(cls.path), "14:00"):
                    return
            except OSError:
                time.sleep(0.05)
        raise RuntimeError("Service did not become ready")

    @classmethod
    def stop_service(cls):
        if cls.service.poll() is None:
            cls.service.terminate()
            try:
                cls.service.wait(timeout=3)
            except subprocess.TimeoutExpired:
                cls.service.kill()
                cls.service.wait()

    def test_working_hours_and_boundaries(self):
        for clock, expected in [("00:00", False), ("08:59", False), ("09:00", True),
                                ("14:00", True), ("16:59", True), ("17:00", False),
                                ("20:00", False), ("23:59", False)]:
            with self.subTest(time=clock):
                self.assertEqual(expected, check_time(str(self.path), clock))

    def test_enclave_rejects_out_of_range_minutes(self):
        for minute in [-1, 1440]:
            with self.subTest(minute=minute):
                self.assertEqual(b"ERROR invalid_time\n",
                                 exchange(self.path, [f"CHECK {minute}\n".encode()]))

    def test_malformed_requests_do_not_kill_service(self):
        for request in [b"\n", b"HELLO\n", b"CHECK \n", b"CHECK nope\n",
                        b"CHECK 840 extra\n", b"CHECK 999999999999999999999\n",
                        b"x" * 64 + b"\n"]:
            with self.subTest(request=request):
                self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [request]))
        self.assertTrue(check_time(str(self.path), "14:00"))

    def test_fragmented_request(self):
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CH", b"ECK ", b"840", b"\n"]))

    def test_incomplete_request_times_out(self):
        self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [b"CHECK "]))
        self.assertTrue(check_time(str(self.path), "14:00"))

    def test_existing_file_is_not_overwritten(self):
        existing = pathlib.Path(self.directory.name) / "existing"
        existing.write_text("keep me")
        result = subprocess.run([str(SERVICE), str(existing), str(ENCLAVE)],
                                capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("keep me", existing.read_text())

    def test_second_listener_does_not_replace_first(self):
        result = subprocess.run([str(SERVICE), str(self.path), str(ENCLAVE)],
                                capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(check_time(str(self.path), "14:00"))

    def test_missing_enclave_prevents_startup(self):
        path = pathlib.Path(self.directory.name) / "missing-enclave.sock"
        result = subprocess.run([str(SERVICE), str(path), str(ENCLAVE) + ".missing"],
                                capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(path.exists())

    def test_client_rejects_invalid_time(self):
        for invalid in ["24:00", "14:60", "-1:00", "14", "9:00"]:
            with self.subTest(time=invalid):
                with self.assertRaises(ValueError):
                    check_time(str(self.path), invalid)

    def test_client_does_not_allow_when_service_is_absent(self):
        with self.assertRaises(OSError):
            check_time(str(pathlib.Path(self.directory.name) / "absent.sock"), "14:00")

    def test_shutdown_removes_only_its_socket(self):
        path = pathlib.Path(self.directory.name) / "shutdown.sock"
        with subprocess.Popen([str(SERVICE), str(path), str(ENCLAVE)],
                              stdout=self.log, stderr=subprocess.STDOUT) as process:
            try:
                for _ in range(100):
                    if process.poll() is not None:
                        self.fail("Second test service failed to start")
                    try:
                        if check_time(str(path), "14:00"):
                            break
                    except OSError:
                        time.sleep(0.05)
                else:
                    self.fail("Second test service did not become ready")
                process.terminate()
                self.assertEqual(0, process.wait(timeout=3))
                self.assertFalse(path.exists())
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    unittest.main(verbosity=2)
