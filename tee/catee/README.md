# CATEE: first standalone slice

This small prototype follows PATEE's process layout:

```text
Python test client -> local Unix socket -> native service -> ECALL -> SGX enclave
```

The enclave contains a **fixed development fixture**, 09:00 inclusive to 17:00
exclusive. Both the configuration and comparison live inside the enclave. The
client supplies a time; this is not a trusted clock or a complete authorization
decision. Cassandra is not connected to this service yet.

## File layout

```text
tee/catee/
├── src/
│   ├── sgx_service.cpp
│   ├── java/org/apache/cassandra/auth/catee/CateeClient.java
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

`src/java/org/apache/cassandra/auth/catee/CateeClient.java` is the database-side
IPC helper. It follows PATEE's `send_request_to_sgx_service()` flow: open a Unix
socket, send one request, read one response, and close the connection. It currently
uses this service's existing text protocol rather than PATEE's C binary structs.
Cassandra's authorization code is not connected yet.

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
It compiles with `javac --release 11` independently of Cassandra's Ant build.

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
policy processing, Cassandra integration, or remote attestation in this
slice. Add those incrementally after reviewing this working path.

Architecture reference: [PATEE's pinned implementation](https://github.com/harshit-jain52/postgres/tree/79d3f29576419d457c516bcda5d757d99f104b14/tee).
