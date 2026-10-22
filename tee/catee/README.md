# CATEE: working-hours prototype

This small prototype follows PATEE's process layout:

```text
Python test client -> local Unix socket -> native service -> ECALL -> SGX enclave
```

The enclave contains a **fixed development fixture**, 09:00 inclusive to 17:00
exclusive. Both the configuration and comparison live inside the enclave. The
client supplies a time; this is not a trusted clock or a complete authorization
decision. Cassandra can opt into this service for policies requiring `worktime`.

## File layout

```text
tee/catee/
├── src/
│   ├── sgx_service.cpp
│   └── Enclave/
│       ├── Enclave.cpp
│       ├── Enclave.edl
│       ├── Enclave.config.xml
│       └── Enclave.lds
├── test/
│   ├── client.py
│   ├── test_service.py
│   ├── test_java_client.py
│   └── java/org/apache/cassandra/auth/catee/CateeClientDriver.java
├── Makefile
└── README.md
```

## Build and test on the lab PC

Use the existing SDK, driver and AESM service; no installation changes are needed:

```bash
source ~/sgxsdk/environment
cd /home/jayjani/cassandra-abac-btp/tee/catee
make -j2
make test
```

This first slice supports only **x86-64 hardware mode with a debug enclave**.
It does not silently use simulation. A debug enclave is suitable for this
functional prototype, not a production confidentiality claim. Responses report
`HW DEBUG`; the debug flag is read inside the enclave, while HW is the build mode.
This reporting is not remote attestation.

Build rules follow the Intel SGX SDK's SampleEnclave linking pattern. All generated
objects, generated EDL bindings and binaries are ignored under `build/`. A local
development signing key is generated under ignored `.local/`, with restricted
permissions. **Do not commit build outputs or the key.** `make clean` removes build
outputs but preserves that key. Preserve the key when restoring sealed state
across rebuilds; SDK sealing binds state to the platform and enclave identity.

## Try it manually

In the first SSH terminal:

```bash
source ~/sgxsdk/environment
cd /home/jayjani/cassandra-abac-btp/tee/catee
catee_run_dir=$(mktemp -d /tmp/catee-demo.XXXXXX)
printf 'Socket path: %s/catee.sock\n' "$catee_run_dir"
./build/catee-service "$catee_run_dir/catee.sock" "$PWD/build/enclave.signed.so" "$catee_run_dir/admin.sealed" --init
```

In a second SSH terminal, use the printed socket path:

```bash
cd /home/jayjani/cassandra-abac-btp/tee/catee
python3 test/client.py /tmp/catee-demo.REPLACE/catee.sock 14:00
python3 test/client.py /tmp/catee-demo.REPLACE/catee.sock 20:00
```

Expected outputs: `14:00 -> true (HW DEBUG)` and `20:00 -> false (HW DEBUG)`.
Stop the service with Ctrl+C. It removes its own socket on normal shutdown.
Keep the directory and sealed state to restart with the same grants; omit
`--init` on subsequent starts. Following a forced kill, confirm the old process
has stopped before removing its stale socket; preserve the state and lock files.

## Java socket client

`../../src/java/org/apache/cassandra/auth/catee/CateeClient.java` is the database-side
IPC helper. It follows PATEE's `send_request_to_sgx_service()` flow: open a Unix
socket, send one request, read one response, and close the connection. It currently
uses this service's existing text protocol rather than PATEE's C binary structs.
The same transport source is compiled by Cassandra and the standalone test harness.

Java 11 Unix sockets use [junixsocket](https://kohlschutter.github.io/junixsocket/dependency.html)
2.11.1. The Makefile downloads its two JARs to ignored `build/java-deps/` and checks
pinned SHA-256 hashes before installing them. Only the first build needs a download.
There is no project-specific JNI bridge or JDK upgrade.

```bash
# From tee/catee, with the SGX SDK environment loaded:
make test-java
# With the native service running, test 14:00 (840 minutes) and a 2000 ms timeout:
java -cp 'build/java:build/java-deps/*' org.apache.cassandra.auth.catee.CateeClientDriver /tmp/catee-demo.REPLACE/catee.sock 840 2000
```

The test driver lives under `test/java/`. The reusable API is
`new CateeClient(socketPath, timeoutMillis).checkWorktime(minute)`.
It returns a Boolean only for an exact ALLOW/DENY reply; connection, read and
protocol errors throw exceptions. Connections and reads share a deadline, replies
are limited to 64 bytes, and slowly arriving bytes do not reset the deadline.
Requests are a single small blocking write; general write-deadline handling is
not implemented. Each call closes its socket, including on errors, without retry.
The supplied minute remains an untrusted observation, and the socket reply is not
cryptographic proof of enclave identity.

`make test-java` runs the existing 11 native tests, Java checks against the real
hardware service, and controlled-peer tests for malformed replies and timeouts.
It compiles with `javac --release 11`. Cassandra's Ant dependency POM includes the
same two transport JARs for normal builds and runtime packaging.

## Cassandra integration

Leave `cassandra.catee.socket` unset for the existing native ABAC behavior. To opt
in, pass these JVM properties when starting Cassandra (for example through
`JVM_EXTRA_OPTS` in a source checkout):

```text
-Dcassandra.catee.socket=/absolute/private/directory/catee.sock
-Dcassandra.catee.timeout_ms=2000
```

Run the native service under the same OS user, supplying its state file as shown
above. Settings are captured when
`CassandraAuthorizer` is constructed. The socket path must be absolute and the
timeout must be 1–60000 milliseconds. These are connection settings, not policy
configuration; the enclave window remains fixed at 09:00–17:00.

Use the exact environment condition `{'worktime': 'true'}` (or `'false'`) in an
ABAC rule. This uses the existing CREATE RULE syntax and `system_auth.abac_rules`
map; no CQL grammar or schema changes are needed.

The flow follows PATEE's `check_abac_env_conditions()`: the database observes local
time and asks the service to check it. The host clock and JVM timezone are trusted
inputs. For working-hours evaluation, Cassandra only requests SGX when user/resource conditions and
other environmental conditions match a rule requiring worktime. It reuses the
result across applicable rules in that authorization call.

In CATEE mode, worktime comes from SGX rather than a Java environment provider and
never uses attribute hierarchy edges. Required service failures, invalid worktime
values and ABAC execution errors abort authorization rather than falling back to
RBAC. Successful evaluation preserves the existing result: RBAC union (ABAC grants
minus ABAC denies). A healthy nonmatch does not revoke an RBAC grant. Policies
without a required worktime check do not need a running service. Other environment
attributes still use the existing providers; the superuser shortcut is unchanged.

Final permission caching is bypassed when CATEE is enabled so a cached grant or an
RBAC-only warmup entry cannot skip a newly required SGX check. This also makes
non-worktime authorizations uncached in that mode. Role metadata caches remain.

### Run all correctness checks

From the repository root, with Java 11 and the SGX SDK environment available:

```bash
python3 tee/catee/test/validate_correctness.py
```

This command builds Cassandra's tests and the native prototype, runs the store,
real-hardware ECALL, service, sealing, and Java-client suites, then runs the
administration, authorizer, network CQL, loader, and environment-provider tests.
Hardware tests are explicitly enabled. Fresh JUnit reports must contain executed
tests, no failures/errors/skips, and the required hardware test methods; old
reports cannot satisfy a new run. Any failed step exits nonzero.

Each run keeps logs and JUnit reports in a new ignored
`tee/catee/build/validation/run-*/` directory and prints their location. Test
fixtures use temporary sockets and sealed state; no live Cassandra configuration
or historical benchmark data is changed. Cassandra tests run sequentially because
they share `build/test/cassandra`; do not run another Ant test job concurrently.
The local enclave signing key is preserved. This validates the hardware debug
prototype, not attestation or production enclave security. No performance sweeps
are run by this command.

Focused checks from the repository root, after building the native service:

```bash
ant build-test
ant testsome -Dtest.name=org.apache.cassandra.auth.CateeAdministrationTest
ant testsome -Dtest.name=org.apache.cassandra.auth.CateeAuthorizerTest -Dtest.jvm.args=-Dcatee.test.hardware=true
catee_cql_dir=$(mktemp -d /tmp/catee-cql.XXXXXX)
ant testsome -Dtest.name=org.apache.cassandra.auth.CateeCqlTest -Dtest.jvm.args="-Dcatee.test.hardware=true -Dcassandra.catee.socket=$catee_cql_dir/catee.sock"
rmdir "$catee_cql_dir"
```

The hardware tests are explicitly opt-in. The network CQL test creates a policy
using the existing grammar, checks allow/deny after policy updates, stops the
service to verify failure despite an RBAC grant, and verifies that removing the
worktime condition permits access without the service. All Cassandra test data is
isolated under the test build directory; historical benchmarks are not rerun.

## ABAC versus CATEE benchmark

```bash
# Validation only: 1 warmup batch, 2 retained batches, 3 calls per batch.
python3 tee/catee/test/benchmark_abac.py tee/catee/build/benchmark-smoke-NEW --smoke
# Measurement protocol: 3 independent pairs of fresh JVMs by default.
python3 tee/catee/test/benchmark_abac.py benchmarks/catee/comparison-NEW
```

Choose a new output path without whitespace. Existing directories and paths
under historical `benchmarks/abac-experiments` are refused. The command builds
the test harness and native service, then uses isolated Cassandra test databases
and temporary SGX service state. Do not run other Ant tests concurrently.

Each JVM evaluates three fixed one-rule workloads: a control without an
environment condition, `worktime=true`, and `worktime=false`. User/resource
conditions match the same anchor request. Native ABAC uses a Java provider with
the same local-clock 09:00–17:00 predicate; CATEE uses the real enclave. Every
returned permission set is checked outside the timed interval, and modes must
produce identical decisions. Runs crossing the working-hours boundary fail.

Full mode uses the historical experiment protocol: load configuration once per
point, discard 10 warmup batches, retain 100 batches of 100 calls (10,000 samples
per scenario/mode/repetition). Scenario order is seeded and shared across each
mode pair; mode order is seeded separately. Each mode/repetition has a fresh JVM.
`--repeats N` changes the repetition count, not the per-point batch protocol.

Only `getAbacPermissions()` is timed, including its internal authorization-table
reads. Final permission caching, RBAC union, end-user CQL execution, assertions,
CSV output, loading, enclave startup, and sealing are outside the measurement.
Logging is reduced equally for both modes. This measures
direct authorization overhead, not deployed CQL throughput or cache behavior.
Historical measurements are a protocol reference, not directly interchangeable
data for this fixed workload.

Each mode/repetition writes raw per-call nanoseconds and decisions to CSV. JSON
metadata records protocol, execution order, revision, dirty paths, host/software
details, and service/enclave hashes. Only `status=complete` is valid; a failed run
retains partial output marked incomplete. `kind=smoke` data is validation only.
Logs and fresh JUnit reports are also retained.

### Summaries and graphs

The analysis uses Matplotlib in an isolated environment. The pinned dependency
below was validated with the lab's Python 3.14 interpreter:

```bash
python3 -m venv tee/catee/.local/analysis-venv
tee/catee/.local/analysis-venv/bin/python -m pip install -r tee/catee/test/requirements-analysis.txt
python3 tee/catee/test/test_analyze_benchmark.py
tee/catee/.local/analysis-venv/bin/python tee/catee/test/analyze_benchmark.py benchmarks/catee/comparison-NEW benchmarks/catee/analysis-NEW
```

The output directory must be new and outside historical ABAC results. Inputs
must be complete, follow the recorded batch protocol, contain every sample
exactly once, and have identical decisions across modes/repetitions. Smoke input
is refused unless `--allow-smoke` is supplied; its CSVs and graphs are labeled as
validation data. A successful analysis writes `analysis.json` with
`status=complete`; partial output from a failed analysis must not be used.

Outputs are `batch_summary.csv`, `repeat_summary.csv`, `point_summary.csv`, and
`comparison.csv`, plus latency/overhead graphs in SVG and PNG. Tables use
nanoseconds; latency figures use microseconds. Batch and repetition tables include
mean, median, and nearest-rank p95. Point means average independent repetition
means. Error bars are sample standard deviations across repetitions, not
confidence intervals or variation estimated by treating all calls as independent.
With one repetition, SD is left blank and error bars are omitted.

Overhead is paired by repetition: compute SGX/native mean-latency ratios, then
average those ratios. Reported overhead is `(ratio - 1) * 100`; its SD is computed
across the paired percentages. This differs from dividing the two overall means.
The comparison also reports the mean paired latency difference. Interpretation
and recorded full-protocol results are available in
[the 22 October 2026 comparison](../../benchmarks/catee/comparison-2026-10-22/README.md).

## Administrative privilege store and ECALLs

`src/Enclave/AdminPrivileges.h` follows PATEE's `admin_priv_entry_t` table:
32 entries, privilege masks combined with OR on grant, cleared with AND-NOT on
revoke, and checked for any matching bit. Callers should check one privilege bit
at a time. Revocation retains the entry, as in PATEE; existing roles can be
regranted even when the table is full. New roles fail when all slots are used.

Cassandra role names replace PostgreSQL role OIDs. Names are case-sensitive and
limited to 255 bytes in this prototype, with no truncation; empty names and zero
masks are rejected. This is a CATEE bound, not a Cassandra role-name limit.
`CateeAdminPermission.mask(Permission)` defines stable bits independently of Java
enum ordinals:

| Bit | Existing administrative permission |
| --- | --- |
| 0 | CREATE_ATTRIBUTE |
| 1 | DROP_ATTRIBUTE |
| 2 | ALTER_ATTRIBUTE (also hierarchy-edge creation) |
| 3 | ASSIGN_USER_ATTRIBUTE |
| 4 | REVOKE_USER_ATTRIBUTE |
| 5 | ASSIGN_RESOURCE_ATTRIBUTE |
| 6 | REVOKE_RESOURCE_ATTRIBUTE |
| 7 | CREATE_POLICY |
| 8 | DROP_POLICY |

The existing eight ABAC statement handlers use `CateeAdministration` for these
checks. In CATEE mode it follows PATEE's superuser-or-enclave decision: superusers
retain authority, other authenticated users need a direct global enclave grant,
and service errors reject the operation. The primary role name is used; role
inheritance and target-resource scoping do not apply to these enclave grants.
Ordinary RBAC/ABAC administrative grants cannot substitute for them. In native
mode the original scoped `ClientState.ensurePermission` check is used unchanged.
General data-access authorization and ordinary permission GRANT/REVOKE are not
changed by this hook.

**Deferred pending a further decision:** new CQL administration syntax, grammar
changes, and a CQL interface for granting/revoking CATEE privileges. Existing CQL
ABAC commands keep their syntax. For now, grants are provisioned through the Java
client (as the integration test does), using the mask mapping above; this is not
an authenticated end-user administration interface. ALTER/ENABLE/DISABLE_POLICY
have no existing statement handlers and are not exposed as CATEE operations.

The enclave exposes PATEE's `enclave_grant_admin_priv`,
`enclave_revoke_admin_priv`, and `enclave_check_admin_priv` operations over this
store. Each role argument is a readable 256-byte buffer containing a terminated
name; Edger8r copies that fixed buffer into the enclave. Unterminated names are
rejected. The ECALL application result is 0 on success or 1 on invalid input or
capacity failure, separate from the SGX transport status. Check initializes its
output to false before validating input.

As in PATEE, these ECALLs do not authenticate the administrator. A caller able to
invoke them can change grants; database-side checks do not authenticate direct
service clients.
Socket operations and the existing Cassandra administration checks now invoke
these ECALLs. The service restores the sealed administrative table before
listening; explicit first-time initialization starts with no grants.
`make test-admin` runs host-side C++ tests against the same store implementation;
these test data-structure behavior, not SGX isolation. `make test-admin-hw` invokes
the real ECALLs on SGX hardware, covering grant/revoke/check, invalid inputs,
capacity, sealing/unsealing, and rejection of tampered state. `make test` runs both
suites, the existing service tests, and sealing/restart tests.

The socket follows PATEE's grant/revoke/check request dispatch, retaining CATEE's
text framing rather than PATEE's binary C structures:

```text
GRANT_ADMIN_PRIV <hex-role-bytes> <unsigned-mask>\n
REVOKE_ADMIN_PRIV <hex-role-bytes> <unsigned-mask>\n
CHECK_ADMIN_PRIV <hex-role-bytes> <unsigned-mask>\n
```

Role bytes are hex-encoded so spaces, newlines, and Unicode do not change message
framing; embedded NUL bytes are rejected. Java uses UTF-8 and rejects malformed
Unicode. Masks are nonzero unsigned 32-bit values on the wire; Java uses `int`
bitmasks, including the high bit. Grant/revoke return `OK HW DEBUG`, check returns
`ALLOW HW DEBUG` or `DENY HW DEBUG`; errors throw on the Java side. Use
`grantAdminPrivilege(role, mask)`, `revokeAdminPrivilege(role, mask)`, and
`checkAdminPrivilege(role, mask)` on `CateeClient`. All operations share the same
connection/read deadline and 64-byte response limit. No automatic retries occur;
a lost mutation response does not prove that the mutation did not happen.

These operations are currently available to any process accepted by the existing
same-UID socket check, matching the trusted database/service caller assumption.
They are not authenticated administrator commands. Cassandra checks callers of
the existing ABAC statements, but a same-UID process can call the service directly.
`make test-java` additionally
checks Java-to-enclave operations, role encoding, capacity errors, fragmented and
malformed requests, and strict response handling against controlled peers.

## Sealed administrative state

The service takes `SOCKET_PATH ENCLAVE_PATH STATE_FILE [--init]`. Like PATEE's
reseal/load flow, it uses enclave ECALLs backed by `sgx_seal_data` and
`sgx_unseal_data`; the service stores sealed bytes, not a plaintext table. The sealed payload
contains a format version and the bounded administrative table. Working hours
remain compiled constants and do not require mutable persisted configuration.

Unlike PATEE's automatic initialization on a missing file, `--init` explicitly
creates a new empty state file and refuses an existing file. Normal startup
rejects missing, corrupted, truncated, symlinked, or incompatible state before
opening the socket. The state file and socket need separate paths in owned
private directories. State files are mode 0600. Keep runtime state outside Git.

Successful grant/revoke operations seal the updated table, write an exclusively
created `.tmp` file, sync it, atomically replace the state file, and sync its parent
directory before replying `OK`. A persistence failure rejects the request and
stops the service so modified but unpersisted memory cannot drive later checks.
The last acknowledged state survives a failure before replacement; a lost reply
or failure after replacement can leave the mutation persisted. There is no retry
or transaction protocol across Cassandra and the service.

A `.lock` file is held with an exclusive process lock to prevent cooperating
services from writing the same state concurrently. Locks are released on process
exit; retain the lock file between starts. Failed temporary/partial files are
preserved for inspection rather than silently reset. Do not promote a leftover
`.tmp` file automatically; recovery requires an explicit decision.

Sealing detects altered bytes, but does not prevent deletion or replay of an
older valid sealed file. An explicit reinitialization after deletion creates a
new empty instance, not recovery. There is no rollback protection, remote
attestation, state migration, or production-enclave security claim. Debug mode
and the trusted database/service caller assumptions remain unchanged.

## Deliberately small scope

- One request per connection: ASCII `CHECK <minute>\n` (minute 0–1439), or an
  administrative operation above.
- Replies: `ALLOW HW DEBUG\n`, `DENY HW DEBUG\n`, `OK HW DEBUG\n`, or `ERROR <reason>\n`.
- Requests are limited to 544 bytes including the newline, with a one-second
  request deadline. Inputs outside the minute range are rejected inside SGX.
- The socket parent must be owned by the service user and private. Only the same
  OS UID can connect. This does not authenticate a particular program or protect
  against another process using that account.
- Every ECALL checks both transport and enclave return values. Errors are never
  reported as an allow result; an enclave failure stops further decisions.
- `make test` starts a real service in a temporary directory, tests boundaries,
  invalid/fragmented requests, timeout and startup failures, and stops it afterward.

This is an intentionally temporary text protocol, **not** the proposed full v1
binary protocol in the design draft. There are no new CQL administration commands,
enclave policy processing, or remote attestation in this
slice. Add those incrementally after reviewing this working path.

Architecture reference: [PATEE's pinned implementation](https://github.com/harshit-jain52/postgres/tree/79d3f29576419d457c516bcda5d757d99f104b14/tee).
