#include "Fixture_t.h"
#include "sgx_tseal.h"

// Test-only enclave: produce authenticated legacy and malformed payloads.
int seal_fixture(const uint8_t *payload, uint32_t payload_size, uint8_t *sealed, uint32_t sealed_size)
{
    return sgx_seal_data(0, nullptr, payload_size, payload, sealed_size,
                         reinterpret_cast<sgx_sealed_data_t *>(sealed)) == SGX_SUCCESS ? 0 : 1;
}
