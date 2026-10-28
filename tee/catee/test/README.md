# CATEE correctness coverage

Run from the repository root:

```bash
source ~/sgxsdk/environment
python3 tee/catee/test/validate_correctness.py
```

The runner requires Java 11 and the analysis dependencies documented in the main
README. It uses the existing `.local/analysis-venv` for plotting tests when
available. Missing dependencies or skipped/empty/failed suites fail validation;
nothing is silently skipped. Cassandra suites run sequentially because they share
the test data directory. Do not run another Ant test job at the same time.

| Area | Checks |
|---|---|
| Native and Java transport | Framing, fragmentation, invalid values, response types, size limits, deadlines, missing service and socket ownership/startup rules |
| Environmental configuration | CQL parsing and ranges, caller privileges, disabled mode, invalid updates preserving state, every minute across four windows |
| Authorization | RBAC/ABAC union, ABAC deny behavior, expected true/false, cache bypass and required-service failure |
| Administration and role lifecycle | Supported commands, direct grants, validation, ordering, failures at each cleanup stage, retries, recreation and concurrent mutations |
| Sequential hardware CQL | Real Cassandra requests through IPC/ECALLs, configuration affecting data access, restart persistence, failed saves, existing sessions and service failure |
| Concurrent hardware CQL | Eight independent authenticated sessions; 2,400 reads over allow/revoke/regrant phases; 320 concurrent administrative updates; abrupt service death and recovery |
| Sealed state | Versions 1/2/3/4/5/6/7, grant/marker/window/workday restoration, invalid authenticated payloads, corruption, capacity, concurrent requests and process crashes |
| Persistence fault injection | All seven mutable operations at nine named host I/O/error/exit boundaries (63 subcases) |
| Evaluation tooling | Bad/missing samples, statistical fixtures, smoke labeling, CSV/image export, overwrite refusal and historical-output protection |
| Benchmark smoke | One native/SGX pair with tiny batches, followed by analysis; all generated output is validation-only and isolated |

`CateeCqlTestSupport` shares service startup/restart/cleanup between the original
multi-step CQL scenario and the independently reported concurrent/recovery tests.
All hardware paths use real SGX, not simulation. Host clock, caller identity and
honest database enforcement retain the prototype's existing trust assumptions.

## Persistence fault matrix

The test-only `persistence_faults.so` is preloaded into an isolated native service,
never into Cassandra or the production enclave. It acts only on the explicit
`CATEE_TEST_STATE_FILE` temporary path. It injects:

- A partial temporary-file write followed by EIO.
- Temporary-file fsync and close errors.
- Rename failure.
- Parent-directory fsync and close errors.
- Abrupt process exit after file fsync, rename, or directory fsync.

Each boundary is exercised for grant, revoke, BEGIN, FINISH, window, working-day and subnet updates.
Tests reject false success, verify whether replacement occurred, and restart from
the actual saved snapshot. They check the relevant permissions/marker/window and
unrelated grants. Leftover temporary files are never promoted automatically.

## What this establishes

This is bounded single-node prototype correctness coverage, not a claim of
exhaustive testing of every possible execution. Concurrent policy changes are
checked in phases, not as a linearizability proof. Fault injection models syscall
errors and process termination; it does not simulate disk-controller behavior or
physical power loss. It does not test multi-node state synchronization, production
enclave security, attestation or side channels, which are outside this prototype.

No historical experiment directories are overwritten. The smoke run is not a
replacement for the separate full evaluation protocol or sustained deployment
load testing.

Environmental checks use PATEE-style observations, expectations and required-check
flags. Direct hardware ECALL, socket, Java and authorizer tests cover expected
true/false, per-rule evaluation, invalid inputs and fail-closed rejection of
subnet membership and missing/IPv6 context. Working-day tests cover all 128
selections, combined day/time conditions, CQL configuration and sealed restoration. The old CHECK wire command
is replaced by CHECK_ENV; persistence and benchmark readiness probes use it too.

Subnet tests cover existence, replacement, /0–/32 masks, boundary addresses,
32-entry capacity, malformed configuration, UTF-8/name framing, sealed v4 migration,
crash recovery and failed saves. CQL checks all four PATEE rule names together,
reconfiguration, invalid thresholds, finite connection capacity and load-only
operation while SGX is unavailable. Cassandra's load numerator is open native
connections, unlike PostgreSQL's STATE_RUNNING backends; this is documented in
README. The connection limit is restored after tests.

SET_EA_ADMIN coverage includes protected-only CQL grant/revoke, exact bit mapping,
all three settings, denial without a direct primary-role grant, revocation on an
existing session, restart, failed grant persistence, role deletion/recreation and
unavailable service. Old versions 1–5 never acquire authority from raw bit 9;
version 6 preserves explicit new grants. The delegation CQL suite is required and
must execute without skips in the validation runner.

Time semantics match PATEE: full-day fresh default and inclusive ending minutes.
Hardware tests check every valid minute, CQL checks the current ending minute
(with a bounded retry if the clock crosses a minute), and full-day CQL access
survives restart. Versions 3–6 keep their window numbers; versions 1–2 receive
the full-day default. Version-6 SET_EA_ADMIN remains intact. Benchmarks explicitly
configure 09:00–17:00 inclusive outside timing, record that window in metadata,
and use matching Java/Python/native comparisons. Historical results are unchanged.
