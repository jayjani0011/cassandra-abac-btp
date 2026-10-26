#!/usr/bin/env python3
"""Inject host I/O failures and abrupt process exits at named save boundaries."""
import os
import pathlib
import subprocess
import unittest

import test_sealing
from test_service import ROOT, exchange


class PersistenceFaultTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        environment = {key: value for key, value in os.environ.items() if key not in ("MAKEFLAGS", "MFLAGS")}
        subprocess.run(["make", "-C", str(ROOT), "all", "persistence-faults"],
                       check=True, env=environment, timeout=60)

    def exercise(self, fault, operation):
        fixture = test_sealing.SealingTest()
        try:
            fixture.setUp()
            self.exercise_fixture(fixture, fault, operation)
        finally:
            fixture.doCleanups()

    def exercise_fixture(self, fixture, fault, operation):
        process = fixture.start(True)
        def request(command):
            return exchange(fixture.path, [command + b"\n"])
        self.assertEqual(b"OK HW DEBUG\n", request(b"SET_TIMEWINDOW 600 660"))
        self.assertEqual(b"OK HW DEBUG\n", request(b"GRANT_ADMIN_PRIV 626f62 8"))
        self.assertEqual(b"OK HW DEBUG\n", request(b"GRANT_ADMIN_PRIV 616c696365 " + (b"1" if operation == "grant" else b"3")))
        if operation == "finish":
            self.assertEqual(b"OK HW DEBUG\n", fixture.lifecycle("BEGIN"))
        fixture.stop(process)
        acknowledged = fixture.state.read_bytes()
        environment = dict(os.environ, LD_PRELOAD=str(ROOT / "build/persistence-faults.so"),
                           CATEE_TEST_STATE_FILE=str(fixture.state), CATEE_TEST_PERSISTENCE_FAULT=fault)
        process = fixture.start(environment=environment)
        commands = dict(grant=b"GRANT_ADMIN_PRIV 616c696365 2", revoke=b"REVOKE_ADMIN_PRIV 616c696365 1",
                        begin=b"BEGIN_ROLE_DELETE 616c696365", finish=b"FINISH_ROLE_DELETE 616c696365",
                        window=b"SET_TIMEWINDOW 720 780")
        if fault.startswith("crash_"):
            with self.assertRaises((OSError, RuntimeError)):
                request(commands[operation])
            self.assertEqual(86, process.wait(timeout=3))
        else:
            self.assertEqual(b"ERROR persistence_failure\n", request(commands[operation]))
            self.assertNotEqual(0, process.wait(timeout=3))
        replaced = fault in {"directory_fsync", "directory_close", "crash_after_rename", "crash_after_directory_fsync"}
        if replaced:
            self.assertNotEqual(acknowledged, fixture.state.read_bytes())
        else:
            self.assertEqual(acknowledged, fixture.state.read_bytes())
            pending = pathlib.Path(str(fixture.state) + ".tmp")
            self.assertTrue(pending.exists())
            if fault == "partial_write": self.assertEqual(31, pending.stat().st_size)
        # Explicit recovery: keep the acknowledged file; never promote a leftover temp.
        pathlib.Path(str(fixture.state) + ".tmp").unlink(missing_ok=True)
        fixture.path.unlink(missing_ok=True) # Process is verified dead above.
        fixture.start()
        new_window = operation == "window" and replaced
        self.assertEqual(b"DENY HW DEBUG\n" if new_window else b"ALLOW HW DEBUG\n", request(b"CHECK 630"))
        self.assertEqual(b"ALLOW HW DEBUG\n" if new_window else b"DENY HW DEBUG\n", request(b"CHECK 750"))
        self.assertEqual(b"ALLOW HW DEBUG\n", request(b"CHECK_ADMIN_PRIV 626f62 8"))
        if operation == "finish":
            self.assertEqual(b"OK HW DEBUG\n" if replaced else b"ERROR admin_operation_failed\n",
                             request(b"GRANT_ADMIN_PRIV 616c696365 1"))
        else:
            bit1 = not (replaced and operation in {"revoke", "begin"})
            bit2 = operation != "grant" or replaced
            if operation == "begin" and replaced: bit2 = False
            for bit, allowed in [(1, bit1), (2, bit2)]:
                self.assertEqual(b"ALLOW HW DEBUG\n" if allowed else b"DENY HW DEBUG\n",
                                 request(f"CHECK_ADMIN_PRIV 616c696365 {bit}".encode()))
        if operation == "begin" and replaced:
            self.assertEqual(b"ERROR admin_operation_failed\n", request(b"GRANT_ADMIN_PRIV 616c696365 1"))

    def test_io_errors_never_acknowledge_unpersisted_mutations(self):
        for fault in ["partial_write", "file_fsync", "file_close", "rename", "directory_fsync", "directory_close"]:
            for operation in ["grant", "revoke", "begin", "finish", "window"]:
                with self.subTest(fault=fault, operation=operation):
                    self.exercise(fault, operation)

    def test_abrupt_exits_leave_a_valid_old_or_replaced_snapshot(self):
        for fault in ["crash_after_file_fsync", "crash_after_rename", "crash_after_directory_fsync"]:
            for operation in ["grant", "revoke", "begin", "finish", "window"]:
                with self.subTest(fault=fault, operation=operation):
                    self.exercise(fault, operation)


if __name__ == "__main__":
    unittest.main(verbosity=2)
