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
ant testsome -Dtest.name=org.apache.cassandra.auth.CateeRoleLifecycleTest
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

## CQL administrative grants

Following PATEE's `GRANT/REVOKE ABAC_ADMIN` interface, Cassandra superusers can run:

```cql
GRANT ABAC_ADMIN { CREATE_POLICY, DROP_POLICY } TO alice;
REVOKE ABAC_ADMIN { CREATE_POLICY } FROM alice;
```

Supported operations are `CREATE_ATTRIBUTE`, `DROP_ATTRIBUTE`, `ALTER_ATTRIBUTE`,
`ASSIGN_USER_ATTRIBUTE`, `REVOKE_USER_ATTRIBUTE`, `ASSIGN_RESOURCE_ATTRIBUTE`,
`REVOKE_RESOURCE_ATTRIBUTE`, `CREATE_POLICY`, and `DROP_POLICY`.
The target must exist. Grants additionally require `LOGIN = true`, because CATEE
checks direct primary-role privileges; it does not inherit these grants through
role membership. Revocation still works after login is disabled.
These commands use the existing Java client and enclave ECALLs. They reject
requests when CATEE is disabled or the service update fails; success follows
sealed-state persistence. Existing native ABAC commands retain their behavior.
The new grammar does not configure working hours.

In CATEE mode, role creation, alteration, deletion and protected provisioning
share a single node-local lifecycle lock. Target checks are repeated under that
lock. The native socket continues to trust the permitted OS UID; these CQL checks
do not authenticate direct socket callers.

## Protected role-deletion operations

The Java client now exposes `beginRoleDeletion(role)` and `finishRoleDeletion(role)`.
Their socket requests use hex-encoded role names, like the existing admin requests:

```text
BEGIN_ROLE_DELETE <hex-role>\n
FINISH_ROLE_DELETE <hex-role>\n
```

BEGIN clears all privileges and records a deletion-pending marker. Checks return
DENY and grants fail while that marker exists; ordinary revocation cannot remove
it. FINISH requires a pending marker and removes the entry completely, freeing
its slot. Repeated BEGIN on a pending role and FINISH on an absent role succeed.
FINISH on an active entry fails. Beginning deletion of an absent role reserves a
slot; a full 32-entry table rejects it without changing state.

Successful operations use the same seal/write/fsync/rename/directory-fsync path as
admin updates. Persistence failure returns ERROR and stops the service. The new
version-2 sealed state includes deletion markers. Version-1 state is restored
with its existing grants intact and no markers; the next mutation writes version
2. Keep the same development signing key and compatible SGX sealing identity.
Older binaries cannot read version 2; do not downgrade them onto updated state.
The `--init` option is never a migration or recovery mechanism.

`DROP ROLE` and `DROP USER` now BEGIN deletion before removing Cassandra role
metadata and ordinary permissions, then FINISH only after cleanup returns.
Role-permission cleanup errors propagate in this CATEE path instead of being
logged and ignored. Required service failures reject deletion, including for
superusers. After a partial failure, retry with an authorized administrator:

```cql
DROP ROLE IF EXISTS alice;
```

The retry performs cleanup even when the role row is already absent. After BEGIN,
a failure leaves the sealed marker blocking protected grants/checks. A missing
role created through CREATE ROLE/CREATE USER first completes the same cleanup;
its new identity starts without the old protected grants. CREATE IF NOT EXISTS
on an existing role leaves its grants intact. A failed preparation prevents
creation. New role creation and even DROP IF EXISTS on an absent role require the
SGX service in CATEE mode; an exhausted marker table rejects preparation safely.
Native mode keeps its existing role-management behavior.

This coordination covers supported single-node CQL paths, not raw system_auth
writes, direct role-manager calls or another Cassandra node. Existing sessions
are not disconnected: following Cassandra behavior, they can receive explicit
new grants to a recreated name. In-flight operations authorized before deletion
are not cancelled. Superuser bypass and ordinary authentication/role caches remain
unchanged. Ordinary ABAC user-attribute assignments are not removed by the existing
role-deletion path; cleaning those is a separate unresolved issue, not a guarantee
of this protected-administration lifecycle change.

A restart restores durable markers but does not automatically finish Cassandra
deletion. The native service trusts its permitted OS caller to request FINISH
only after cleanup. SIGKILL can leave a stale socket that must be removed after
verifying the service has stopped. Sealing still does not prevent replay of an
older valid snapshot.

Tests use a separate fixture enclave under `test/sealing_fixture`, signed with the
local development key, to generate real version-1 state and authenticated invalid
payloads. Its ECALL is not included in the service enclave. `make test` builds it;
`test_sealing.py` also ensures this fixture is built when invoked directly.

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

**Still deferred:** configurable environmental settings and their CQL syntax.
Protected administrative grants now use the CQL commands above; the Java client
remains a lower-level transport interface.

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
attestation, general state migration, or production-enclave security claim. Debug mode
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
binary protocol in the design draft. There is no enclave policy processing or remote attestation in this
slice. Add those incrementally after reviewing this working path.

Architecture reference: [PATEE's pinned implementation](https://github.com/harshit-jain52/postgres/tree/79d3f29576419d457c516bcda5d757d99f104b14/tee).
