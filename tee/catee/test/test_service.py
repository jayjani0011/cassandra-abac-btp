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
                    if not check_time(str(cls.path), "00:00") or not check_time(str(cls.path), "23:59"):
                        raise RuntimeError("Fresh enclave must use PATEE's full-day default")
                    if exchange(cls.path, [b"SET_TIMEWINDOW 540 1020\n"]) != b"OK HW DEBUG\n":
                        raise RuntimeError("Cannot configure the explicit test window")
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
                                ("14:00", True), ("16:59", True), ("17:00", True), ("17:01", False),
                                ("20:00", False), ("23:59", False)]:
            with self.subTest(time=clock):
                self.assertEqual(expected, check_time(str(self.path), clock))

    def test_configurable_windows_match_all_minutes(self):
        try:
            for start, end in [(600, 660), (0, 1), (1438, 1439), (0, 1439), (0, 1440)]:
                self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [f"SET_TIMEWINDOW {start} {end}\n".encode()]))
                for minute in range(1440):
                    expected = b"ALLOW HW DEBUG\n" if start <= minute <= end else b"DENY HW DEBUG\n"
                    self.assertEqual(expected, exchange(self.path, [f"CHECK_ENV 0 {minute} 0 - 0 1 0 1 0\n".encode()]),
                                     (start, end, minute))
        finally:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 540 1020\n"]))

    def test_rejected_window_updates_preserve_configuration(self):
        try:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 600 660\n"]))
            for start, end in [(-1, 60), (60, 60), (61, 60), (1440, 1450), (600, 1441),
                               (-2147483648, 2147483647), (0, -1)]:
                self.assertEqual(b"ERROR invalid_timewindow\n",
                                 exchange(self.path, [f"SET_TIMEWINDOW {start} {end}\n".encode()]))
            for request in [b"SET_TIMEWINDOW\n", b"SET_TIMEWINDOW \n", b"SET_TIMEWINDOW 600\n",
                            b"SET_TIMEWINDOW 600 660 extra\n", b"SET_TIMEWINDOW nope 660\n",
                            b"SET_TIMEWINDOW 600 660 \n", b"SET_TIMEWINDOW  600 660\n",
                            b"SET_TIMEWINDOW 600  660\n", b"SET_TIMEWINDOW +600 660\n",
                            b"SET_TIMEWINDOW 600 660.0\n", b"SET_TIMEWINDOW 600 2147483648\n",
                            b"SET_TIMEWINDOW -2147483649 660\n", b"SET_TIMEWINDOW 600 66\x000\n"]:
                self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [request]), request)
            self.assertTrue(check_time(str(self.path), "10:30"))
            self.assertFalse(check_time(str(self.path), "14:00"))
        finally:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 540 1020\n"]))

    def test_fragmented_window_update(self):
        try:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIME", b"WINDOW 720", b" 780\n"]))
            self.assertTrue(check_time(str(self.path), "12:00"))
            self.assertTrue(check_time(str(self.path), "13:00"))
            self.assertFalse(check_time(str(self.path), "13:01"))
        finally:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 540 1020\n"]))

    def test_enclave_rejects_out_of_range_minutes(self):
        for minute in [-1, 1440]:
            with self.subTest(minute=minute):
                self.assertEqual(b"ERROR invalid_environment\n",
                                 exchange(self.path, [f"CHECK_ENV 0 {minute} 0 - 0 1 0 1 0\n".encode()]))

    def test_malformed_requests_do_not_kill_service(self):
        for request in [b"\n", b"HELLO\n", b"CHECK_ENV 0  0 - 0 1 0 1 0\n", b"CHECK_ENV 0 nope 0 - 0 1 0 1 0\n",
                        b"CHECK_ENV 0 840 extra 0 - 0 1 0 1 0\n", b"CHECK_ENV 0 999999999999999999999 0 - 0 1 0 1 0\n",
                        b"CHECK_ENV 0 840 -1 - 0 1 0 1 0\n",
                        b"CHECK_ENV 0 840 4294967296 - 0 1 0 1 0\n",
                        b"CHECK_ENV 0 840 0 - 0 1 0 1 0 extra\n",
                        b"x" * 256 + b"\n"]:
            with self.subTest(request=request):
                self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [request]))
        self.assertTrue(check_time(str(self.path), "14:00"))

    def test_combined_environment_checks(self):
        for minute in [0, 539, 540, 840, 1019, 1020, 1439]:
            for expected in [0, 1]:
                matched = expected == (540 <= minute <= 1020)
                response = exchange(self.path, [f"CHECK_ENV 0 {minute} 0 - 0 {expected} 0 1 0\n".encode()])
                self.assertEqual(b"ALLOW HW DEBUG\n" if matched else b"DENY HW DEBUG\n", response)
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 0 0 - 0 0 0 0 0\n"]))
        for flags in ["0 0 1", "1 1 1"]:
            self.assertEqual(b"DENY HW DEBUG\n",
                             exchange(self.path, [f"CHECK_ENV 0 840 0 6e616d6564 1 1 {flags}\n".encode()]))
        for fields in ["7 840 0 - 0 1 0 1 0", "0 -1 0 - 0 1 0 1 0",
                       "0 1440 0 - 0 1 0 1 0", "0 840 0 - 0 2 0 1 0",
                       "0 840 0 - 0 1 0 2 0"]:
            self.assertEqual(b"ERROR invalid_environment\n",
                             exchange(self.path, [f"CHECK_ENV {fields}\n".encode()]))
        self.assertTrue(check_time(str(self.path), "14:00"))

    def test_workday_configuration_and_combined_checks(self):
        try:
            for mask in ["0 1 1 1 1 1 0", "1 0 0 0 0 0 1", "1 1 1 1 1 1 1", "0 0 0 0 0 0 0"]:
                self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [f"SET_WORKDAY {mask}\n".encode()]))
                for day, selected in enumerate(map(int, mask.split())):
                    for expected in [0, 1]:
                        for minute in [0, 840]:
                            matched = expected == selected and minute == 840
                            self.assertEqual(b"ALLOW HW DEBUG\n" if matched else b"DENY HW DEBUG\n",
                                             exchange(self.path, [f"CHECK_ENV {day} {minute} 0 - {expected} 1 1 1 0\n".encode()]))
            for request in [b"SET_WORKDAY 1 1 1 1 1 1\n", b"SET_WORKDAY 1 1 1 1 1 1 2\n",
                            b"SET_WORKDAY 1 1 1 1 1 1 1 extra\n", b"SET_WORKDAY -1 0 0 0 0 0 0\n"]:
                self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [request]))
            self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 1 840 0 - 0 1 1 0 0\n"]))
        finally:
            self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_WORKDAY 0 1 1 1 1 1 0\n"]))

    def test_subnet_configuration_and_boundaries(self):
        name = "campus space".encode().hex()
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [f"SUBNET_EXISTS {name}\n".encode()]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [f"SET_SUBNET {name} 167772160 8\n".encode()]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [f"SUBNET_EXISTS {name}\n".encode()]))
        for ip, matched in [(167772159,False),(167772160,True),(184549375,True),(184549376,False)]:
            self.assertEqual(b"ALLOW HW DEBUG\n" if matched else b"DENY HW DEBUG\n",
                             exchange(self.path, [f"CHECK_ENV 1 840 {ip} {name} 1 1 1 1 1\n".encode()]))
        for request in [f"SET_SUBNET {name} 1 0\n", f"SET_SUBNET {name} 0 33\n",
                        "SET_SUBNET zz 0 0\n", "SET_SUBNET 00 0 0\n", "SUBNET_EXISTS 61 extra\n"]:
            self.assertTrue(exchange(self.path, [request.encode()]).startswith(b"ERROR "))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [f"SET_SUBNET {name} 0 0\n".encode()]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [f"CHECK_ENV 1 840 4294967295 {name} 0 0 0 0 1\n".encode()]))

    def test_z_subnet_capacity_and_updates(self):
        # Earlier tests may have one named subnet; fill until capacity rejects.
        inserted = []
        for i in range(33):
            name = f"capacity{i}".encode().hex()
            response = exchange(self.path, [f"SET_SUBNET {name} 0 0\n".encode()])
            if response.startswith(b"ERROR "): break
            inserted.append(name)
        self.assertTrue(inserted)
        self.assertLess(len(inserted),33)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [f"SET_SUBNET {inserted[0]} 2130706433 32\n".encode()]))

    def test_fragmented_request(self):
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CH", b"ECK_ENV ", b"0 840 0 - 0 1 0 1 0", b"\n"]))

    def test_incomplete_request_times_out(self):
        self.assertEqual(b"ERROR invalid_request\n", exchange(self.path, [b"CHECK_ENV "]))
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
