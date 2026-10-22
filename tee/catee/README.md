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
outputs but preserves that key. No sealing or persistent trusted state exists yet.

## Try it manually

In the first SSH terminal:

```bash
source ~/sgxsdk/environment
cd /home/jayjani/cassandra-abac-btp/tee/catee
catee_run_dir=$(mktemp -d /tmp/catee-demo.XXXXXX)
printf 'Socket path: %s/catee.sock\n' "$catee_run_dir"
./build/catee-service "$catee_run_dir/catee.sock" "$PWD/build/enclave.signed.so"
```

In a second SSH terminal, use the printed socket path:

```bash
cd /home/jayjani/cassandra-abac-btp/tee/catee
python3 test/client.py /tmp/catee-demo.REPLACE/catee.sock 14:00
python3 test/client.py /tmp/catee-demo.REPLACE/catee.sock 20:00
```

Expected outputs: `14:00 -> true (HW DEBUG)` and `20:00 -> false (HW DEBUG)`.
Stop the service with Ctrl+C. It removes its own socket on normal shutdown;
remove the now-empty temporary directory yourself. Following a forced kill,
choose a fresh private directory; the service never overwrites an existing path.

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

Run the existing native service under the same OS user. Settings are captured when
`CassandraAuthorizer` is constructed. The socket path must be absolute and the
timeout must be 1–60000 milliseconds. These are connection settings, not policy
configuration; the enclave window remains fixed at 09:00–17:00.

Use the exact environment condition `{'worktime': 'true'}` (or `'false'`) in an
ABAC rule. This uses the existing CREATE RULE syntax and `system_auth.abac_rules`
map; no CQL grammar, administration handlers, or schema were changed.

The flow follows PATEE's `check_abac_env_conditions()`: the database observes local
time and asks the service to check it. The host clock and JVM timezone are trusted
inputs. Cassandra only requests SGX evaluation when user/resource conditions and
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

Focused checks from the repository root, after building the native service:

```bash
ant build-test
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

## Administrative privilege store (first step)

`src/Enclave/AdminPrivileges.h` follows PATEE's `admin_priv_entry_t` table:
32 entries, privilege masks combined with OR on grant, cleared with AND-NOT on
revoke, and checked for any matching bit. Callers should check one privilege bit
at a time. Revocation retains the entry, as in PATEE; existing roles can be
regranted even when the table is full. New roles fail when all slots are used.

Cassandra role names replace PostgreSQL role OIDs. Names are case-sensitive and
limited to 255 bytes in this prototype, with no truncation; empty names and zero
masks are rejected. This is a CATEE bound, not a Cassandra role-name limit.
Privilege-bit assignments and administration commands are not defined yet.

An empty store is declared in the enclave source, but no ECALL or socket command
uses it yet; the compiler may eliminate this unused state. This step does not
protect or change Cassandra administration. There is no persistence yet.
`make test-admin` runs host-side C++ tests against the same store implementation;
these test data-structure behavior, not SGX isolation. `make test` also runs them
before the existing hardware-service tests.

## Deliberately small scope

- One request per connection: ASCII `CHECK <minute>\n`, where minute is 0–1439.
- Replies: `ALLOW HW DEBUG\n`, `DENY HW DEBUG\n`, or `ERROR <reason>\n`.
- Requests are limited to 64 bytes including the newline, with a one-second
  request deadline. Inputs outside the minute range are rejected inside SGX.
- The socket parent must be owned by the service user and private. Only the same
  OS UID can connect. This does not authenticate a particular program or protect
  against another process using that account.
- Every ECALL checks both transport and enclave return values. Errors are never
  reported as an allow result; an enclave failure stops further decisions.
- `make test` starts a real service in a temporary directory, tests boundaries,
  invalid/fragmented requests, timeout and startup failures, and stops it afterward.

This is an intentionally temporary text protocol, **not** the proposed full v1
binary protocol in the design draft. There are no administrative updates, sealing,
enclave policy processing, or remote attestation in this
slice. Add those incrementally after reviewing this working path.

Architecture reference: [PATEE's pinned implementation](https://github.com/harshit-jain52/postgres/tree/79d3f29576419d457c516bcda5d757d99f104b14/tee).
