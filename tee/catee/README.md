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
│   └── Enclave/
│       ├── Enclave.cpp
│       ├── Enclave.edl
│       ├── Enclave.config.xml
│       └── Enclave.lds
├── test/
│   ├── client.py
│   └── test_service.py
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
policy processing, Java client, Cassandra changes, or remote attestation in this
slice. Add those incrementally after reviewing this working path.

Architecture reference: [PATEE's pinned implementation](https://github.com/harshit-jain52/postgres/tree/79d3f29576419d457c516bcda5d757d99f104b14/tee).
