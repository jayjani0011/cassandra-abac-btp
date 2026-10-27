#!/usr/bin/env python3
"""Hardware sealing, restart, and persistence-failure checks in isolated directories."""
import os
import pathlib
import struct
import concurrent.futures
import subprocess
import tempfile
import time
import unittest

from test_service import SERVICE, ENCLAVE, ROOT, exchange


class SealingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Python closes inherited jobserver descriptors; do not forward their flags.
        environment = {key: value for key, value in os.environ.items() if key not in ("MAKEFLAGS", "MFLAGS")}
        subprocess.run(["make", "-C", str(ROOT), "sealing-fixture"], env=environment, check=True, timeout=60)

    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="catee-sealing-")
        self.addCleanup(directory.cleanup)
        self.path = pathlib.Path(directory.name) / "service.sock"
        self.state = pathlib.Path(directory.name) / "admin.sealed"
        self.log = tempfile.TemporaryFile()
        self.addCleanup(self.log.close)

    def command(self, initialize=False, path=None):
        return [str(SERVICE), str(path or self.path), str(ENCLAVE), str(self.state)] + (["--init"] if initialize else [])

    def start(self, initialize=False, environment=None):
        process = subprocess.Popen(self.command(initialize), stdout=self.log, stderr=self.log, env=environment)
        self.addCleanup(self.stop, process)
        for _ in range(100):
            if process.poll() is not None:
                self.fail("Service failed to start")
            try:
                if exchange(self.path, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]) in (b"ALLOW HW DEBUG\n", b"DENY HW DEBUG\n"):
                    return process
            except OSError:
                time.sleep(0.05)
        self.fail("Service did not become ready")

    def stop(self, process):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

    def rejected_start(self, initialize=False, path=None):
        result = subprocess.run(self.command(initialize, path), capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((path or self.path).exists())

    def test_grant_and_revoke_survive_restart(self):
        process = self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 3\n"]))
        self.stop(process)
        self.assertEqual(0o600, self.state.stat().st_mode & 0o777)
        process = self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"REVOKE_ADMIN_PRIV 616c696365 1\n"]))
        self.stop(process)
        process = self.start()
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 2\n"]))

    def test_missing_state_requires_explicit_initialization(self):
        self.rejected_start()
        self.assertFalse(self.state.exists())
        process = self.start(True)
        self.stop(process)
        original = self.state.read_bytes()
        self.rejected_start(True)
        self.assertEqual(original, self.state.read_bytes())
        self.state.unlink()
        self.rejected_start()

    def test_corrupt_truncated_and_symlink_state_rejected(self):
        process = self.start(True)
        self.stop(process)
        original = self.state.read_bytes()
        corrupt = bytearray(original)
        corrupt[-1] ^= 1
        for contents in [bytes(corrupt), original[:-1], b""]:
            self.state.write_bytes(contents)
            self.rejected_start()
            self.assertEqual(contents, self.state.read_bytes())
        target = self.state.with_name("target")
        target.write_bytes(original)
        self.state.unlink()
        self.state.symlink_to(target)
        self.rejected_start()
        self.assertEqual(original, target.read_bytes())

    def test_same_state_cannot_have_two_service_writers(self):
        self.start(True)
        self.rejected_start(path=self.path.with_name("second.sock"))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]))

    def test_failed_persistence_stops_service_without_acknowledging_grant(self):
        process = self.start(True)
        original = self.state.read_bytes()
        pending = pathlib.Path(str(self.state) + ".tmp")
        pending.write_text("preserve")
        self.assertEqual(b"ERROR persistence_failure\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertNotEqual(0, process.wait(timeout=3))
        self.assertEqual(original, self.state.read_bytes())
        self.assertEqual("preserve", pending.read_text())
        pending.unlink()
        self.start()
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 1\n"]))

    def test_failed_persistence_does_not_acknowledge_revoke(self):
        process = self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        original = self.state.read_bytes()
        pending = pathlib.Path(str(self.state) + ".tmp")
        pending.write_text("preserve")
        self.assertEqual(b"ERROR persistence_failure\n", exchange(self.path, [b"REVOKE_ADMIN_PRIV 616c696365 1\n"]))
        self.assertNotEqual(0, process.wait(timeout=3))
        self.assertEqual(original, self.state.read_bytes())
        pending.unlink()
        self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 1\n"]))

    def lifecycle(self, operation, role="alice"):
        return exchange(self.path, [f"{operation}_ROLE_DELETE {role.encode().hex()}\n".encode()])

    def test_pending_deletion_survives_sigkill_and_finish_frees_slot(self):
        process = self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 3\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", self.lifecycle("FINISH"))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN"))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN"))
        process.kill()
        process.wait(timeout=3)
        # SIGKILL leaves the socket behind; remove only this isolated test socket.
        self.path.unlink()
        process = self.start()
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 3\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"REVOKE_ADMIN_PRIV 616c696365 3\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("FINISH"))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("FINISH"))
        self.stop(process)
        self.start()
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 3\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 4\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 3\n"]))

    def test_failed_begin_and_finish_do_not_acknowledge_or_lose_durable_marker(self):
        process = self.start(True)
        pending = pathlib.Path(str(self.state) + ".tmp")
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        original = self.state.read_bytes()
        pending.write_text("preserve")
        self.assertEqual(b"ERROR persistence_failure\n", self.lifecycle("BEGIN"))
        self.assertNotEqual(0, process.wait(timeout=3))
        self.assertEqual(original, self.state.read_bytes())
        pending.unlink()
        process = self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN"))
        original = self.state.read_bytes()
        pending.write_text("preserve")
        self.assertEqual(b"ERROR persistence_failure\n", self.lifecycle("FINISH"))
        self.assertNotEqual(0, process.wait(timeout=3))
        self.assertEqual(original, self.state.read_bytes())
        pending.unlink()
        self.start()
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("FINISH"))

    def test_begin_at_capacity_and_slot_reuse(self):
        self.start(True)
        for i in range(32):
            self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN", f"role{i}"))
        self.assertEqual(b"ERROR admin_operation_failed\n", self.lifecycle("BEGIN", "overflow"))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN", "role0"))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("FINISH", "role0"))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN", "overflow"))

    def test_concurrent_grants_cannot_clear_a_durable_marker(self):
        self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN"))
        def attempt(i):
            operation = "GRANT" if i % 2 else "REVOKE"
            return operation, exchange(self.path, [f"{operation}_ADMIN_PRIV 616c696365 1\n".encode()])
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
            for operation, response in workers.map(attempt, range(64)):
                self.assertEqual(b"ERROR admin_operation_failed\n" if operation == "GRANT" else b"OK HW DEBUG\n", response)
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 616c696365 1\n"]))

    def seal_payload(self, payload):
        payload_file = self.state.with_name("payload.bin")
        payload_file.write_bytes(payload)
        self.state.unlink(missing_ok=True)
        subprocess.run([str(ROOT / "build/fixture/seal-fixture"),
                        str(ROOT / "build/fixture/enclave.signed.so"), str(payload_file), str(self.state)],
                       check=True, capture_output=True, timeout=5)

    @staticmethod
    def payload(version=4, entries=(), count=None, window=(540, 1020), days=(0,1,1,1,1,1,0)):
        legacy = version == 1
        packed = bytearray(struct.pack("<I", version))
        for role, mask, pending in entries:
            packed.extend(role.ljust(256, b"\0") + struct.pack("<I", mask))
            if not legacy:
                packed.extend(struct.pack("<I", pending))
        packed.extend(bytes((32 - len(entries)) * (260 if legacy else 264)))
        packed.extend(struct.pack("<I", len(entries) if count is None else count))
        if version >= 3:
            packed.extend(struct.pack("<ii", *window))
        if version >= 4:
            packed.extend(bytes(days) + b"\0")
        return bytes(packed)

    def test_version_one_migration_preserves_all_grants(self):
        entries = [(f"role{i}".encode(), i + 1, 0) for i in range(32)]
        self.seal_payload(self.payload(version=1, entries=entries))
        original = self.state.read_bytes()
        process = self.start()
        for role, mask, _ in entries:
            for bit in [1 << i for i in range(6)] + [1 << 31]:
                expected = b"ALLOW HW DEBUG\n" if mask & bit else b"DENY HW DEBUG\n"
                self.assertEqual(expected, exchange(self.path, [f"CHECK_ADMIN_PRIV {role.hex()} {bit}\n".encode()]))
        self.assertEqual(original, self.state.read_bytes()) # Read-only restoration does not rewrite.
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN", "role0"))
        self.assertGreater(self.state.stat().st_size, len(original)) # First mutation writes current version.
        self.stop(process)
        self.start()
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 726f6c6530 1\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 726f6c6531 2\n"]))

    def test_version_two_preserves_grants_and_pending_markers_with_default_window(self):
        self.seal_payload(self.payload(version=2, entries=[(b"alice", 0, 1), (b"bob", 3, 0)]))
        original = self.state.read_bytes()
        process = self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 0 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 626f62 3\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(original, self.state.read_bytes())
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 600 660\n"]))
        self.assertGreater(self.state.stat().st_size, len(original))
        self.stop(process)
        self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 626f62 3\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))

    def test_window_grants_and_deletion_markers_survive_crash_together(self):
        process = self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 600 660\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 626f62 2\n"]))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("BEGIN"))
        process.kill()
        process.wait(timeout=3)
        self.path.unlink()
        process = self.start()
        for minute in range(1440):
            expected = b"ALLOW HW DEBUG\n" if 600 <= minute < 660 else b"DENY HW DEBUG\n"
            self.assertEqual(expected, exchange(self.path, [f"CHECK_ENV 0 {minute} 0 - 0 1 0 1 0\n".encode()]), minute)
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 626f62 2\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", self.lifecycle("FINISH"))
        self.stop(process)
        self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 626f62 2\n"]))

    def test_failed_window_persistence_stops_service_and_restores_previous_window(self):
        process = self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 600 660\n"]))
        original = self.state.read_bytes()
        pending = pathlib.Path(str(self.state) + ".tmp")
        pending.write_text("preserve")
        self.assertEqual(b"ERROR persistence_failure\n", exchange(self.path, [b"SET_TIMEWINDOW 720 780\n"]))
        self.assertNotEqual(0, process.wait(timeout=3))
        self.assertEqual(original, self.state.read_bytes())
        self.assertEqual("preserve", pending.read_text())
        pending.unlink()
        self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 0 1 0 1 0\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 750 0 - 0 1 0 1 0\n"]))

    def test_invalid_window_updates_do_not_reseal_or_modify_state(self):
        self.start(True)
        original = self.state.read_bytes()
        for request in [b"SET_TIMEWINDOW 660 600\n", b"SET_TIMEWINDOW -1 600\n",
                        b"SET_TIMEWINDOW 600 1440\n", b"SET_TIMEWINDOW 600 600\n",
                        b"SET_TIMEWINDOW invalid 600\n", b"SET_WORKDAY 0 1 1 1 1 1 2\n",
                        b"SET_WORKDAY 0 1 1\n"]:
            self.assertTrue(exchange(self.path, [request]).startswith(b"ERROR "))
            self.assertEqual(original, self.state.read_bytes())
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 840 0 - 0 1 0 1 0\n"]))

    def test_version_three_migrates_with_default_days_and_preserves_window(self):
        self.seal_payload(self.payload(version=3, entries=[(b"alice",0,1),(b"bob",3,0)], window=(600,660)))
        original = self.state.read_bytes()
        process = self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 1 630 0 - 1 1 1 1 0\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 1 1 1 1 0\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 626f62 3\n"]))
        self.assertEqual(b"ERROR admin_operation_failed\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 616c696365 1\n"]))
        self.assertEqual(original, self.state.read_bytes())
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_WORKDAY 1 0 0 0 0 0 1\n"]))
        self.assertGreater(self.state.stat().st_size, len(original))
        self.stop(process)
        self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 1 1 1 1 0\n"]))

    def test_workdays_survive_crash_and_failed_update(self):
        process = self.start(True)
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_WORKDAY 1 0 0 0 0 0 1\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"SET_TIMEWINDOW 600 660\n"]))
        self.assertEqual(b"OK HW DEBUG\n", exchange(self.path, [b"GRANT_ADMIN_PRIV 626f62 3\n"]))
        process.kill()
        process.wait(timeout=3)
        self.path.unlink()
        process = self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 1 1 1 1 0\n"]))
        self.assertEqual(b"DENY HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 1 630 0 - 1 1 1 1 0\n"]))
        original = self.state.read_bytes()
        pending = pathlib.Path(str(self.state) + ".tmp")
        pending.write_text("preserve")
        self.assertEqual(b"ERROR persistence_failure\n", exchange(self.path, [b"SET_WORKDAY 0 1 1 1 1 1 0\n"]))
        self.assertNotEqual(0, process.wait(timeout=3))
        self.assertEqual(original, self.state.read_bytes())
        pending.unlink()
        self.start()
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ENV 0 630 0 - 1 1 1 1 0\n"]))
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK_ADMIN_PRIV 626f62 3\n"]))

    def test_authenticated_invalid_payloads_rejected_without_reset(self):
        cases = [self.payload(version=5), self.payload(days=(0,1,1,1,1,1,2)), self.payload(count=33),
                 self.payload(entries=[(b"alice", 1, 1)]),
                 self.payload(entries=[(b"alice", 0, 2)]),
                 self.payload(entries=[(b"", 0, 1)]),
                 self.payload(entries=[(b"x" * 256, 0, 1)]),
                 self.payload(entries=[(b"alice", 0, 1), (b"alice", 1, 0)]),
                 self.payload(version=1, count=33),
                 self.payload(version=1, entries=[(b"x" * 256, 1, 0)]),
                 self.payload(version=1, entries=[(b"alice", 1, 0), (b"alice", 2, 0)]),
                 self.payload(version=2, count=33),
                 self.payload(version=2, entries=[(b"alice", 1, 1)]),
                 self.payload(version=2, entries=[(b"alice", 0, 2)]),
                 self.payload(version=2, entries=[(b"alice", 0, 1), (b"alice", 0, 1)]),
                 self.payload(version=1)[:-1], b"short",
                 struct.pack("<I", 1) + self.payload()[4:],
                 struct.pack("<I", 2) + self.payload(version=1)[4:]]
        cases += [self.payload(window=window) for window in
                  [(-1, 660), (600, 600), (660, 600), (600, 1440), (0, -1), (0, 2147483647)]]
        for payload in cases:
            with self.subTest(size=len(payload), prefix=payload[:4]):
                self.seal_payload(payload)
                original = self.state.read_bytes()
                self.rejected_start()
                self.assertEqual(original, self.state.read_bytes())



if __name__ == "__main__":
    unittest.main(verbosity=2)
