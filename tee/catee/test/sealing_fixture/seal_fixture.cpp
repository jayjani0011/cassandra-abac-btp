#include "Fixture_u.h"
#include <sgx_urts.h>
#include <sgx_tseal.h>
#include <assert.h>
#include <fstream>
#include <iterator>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    assert(argc == 4);
    std::ifstream input(argv[2], std::ios::binary);
    assert(input.good());
    std::vector<uint8_t> payload((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    // sgx_calc_sealed_data_size is trusted-side; this ABI has a fixed header and no AAD.
    std::vector<uint8_t> sealed(sizeof(sgx_sealed_data_t) + payload.size());
    sgx_enclave_id_t enclave = 0;
    assert(sgx_create_enclave(argv[1], SGX_DEBUG_FLAG, nullptr, nullptr, &enclave, nullptr) == SGX_SUCCESS);
    int result = -1;
    assert(seal_fixture(enclave, &result, payload.data(), payload.size(), sealed.data(), sealed.size()) == SGX_SUCCESS);
    assert(result == 0);
    int fd = open(argv[3], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    assert(fd >= 0);
    size_t offset = 0;
    while (offset < sealed.size())
    {
        ssize_t written = write(fd, sealed.data() + offset, sealed.size() - offset);
        assert(written > 0);
        offset += written;
    }
    assert(close(fd) == 0);
    assert(sgx_destroy_enclave(enclave) == SGX_SUCCESS);
}
