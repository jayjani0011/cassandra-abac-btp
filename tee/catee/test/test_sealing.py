#!/usr/bin/env python3
"""Hardware sealing, restart, and persistence-failure checks in isolated directories."""
import pathlib
import subprocess
import tempfile
import time
import unittest

from test_service import SERVICE, ENCLAVE, exchange


class SealingTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="catee-sealing-")
        self.addCleanup(directory.cleanup)
        self.path = pathlib.Path(directory.name) / "service.sock"
        self.state = pathlib.Path(directory.name) / "admin.sealed"
        self.log = tempfile.TemporaryFile()
        self.addCleanup(self.log.close)

    def command(self, initialize=False, path=None):
        return [str(SERVICE), str(path or self.path), str(ENCLAVE), str(self.state)] + (["--init"] if initialize else [])

    def start(self, initialize=False):
        process = subprocess.Popen(self.command(initialize), stdout=self.log, stderr=self.log)
        self.addCleanup(self.stop, process)
        for _ in range(100):
            if process.poll() is not None:
                self.fail("Service failed to start")
            try:
                if exchange(self.path, [b"CHECK 840\n"]) == b"ALLOW HW DEBUG\n":
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
        self.assertEqual(b"ALLOW HW DEBUG\n", exchange(self.path, [b"CHECK 840\n"]))

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
