#include "Enclave_u.h"
#include <sgx_urts.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <vector>

static void expect_check(sgx_enclave_id_t enclave, const char *role, uint32_t mask, int expected)
{
    int result = -1, allowed = -1;
    assert(enclave_check_admin_priv(enclave, &result, role, mask, &allowed) == SGX_SUCCESS);
    assert(result == 0 && allowed == expected);
}

int main(int argc, char **argv)
{
    assert(argc == 2);
    sgx_enclave_id_t enclave = 0;
    sgx_status_t status = sgx_create_enclave(argv[1], SGX_DEBUG_FLAG, nullptr, nullptr, &enclave, nullptr);
    if (status != SGX_SUCCESS)
    {
        fprintf(stderr, "Cannot create hardware enclave: 0x%x\n", status);
        return 1;
    }
    char alice[256] = "alice", bob[256] = "bob";
    int result = -1, allowed = -1;
    expect_check(enclave, alice, 1, 0);
    assert(enclave_grant_admin_priv(enclave, &result, alice, 1) == SGX_SUCCESS && result == 0);
    assert(enclave_grant_admin_priv(enclave, &result, alice, 2) == SGX_SUCCESS && result == 0);
    expect_check(enclave, alice, 1, 1);
    expect_check(enclave, alice, 6, 1);
    expect_check(enclave, alice, 4, 0);
    expect_check(enclave, bob, 1, 0);
    assert(enclave_revoke_admin_priv(enclave, &result, alice, 1) == SGX_SUCCESS && result == 0);
    expect_check(enclave, alice, 1, 0);
    expect_check(enclave, alice, 2, 1);
    assert(enclave_revoke_admin_priv(enclave, &result, bob, 1) == SGX_SUCCESS && result == 0);

    char empty[256] = {}, unterminated[256];
    memset(unterminated, 'x', sizeof(unterminated));
    const char *invalid_roles[] = {nullptr, empty, unterminated};
    for (const char *role : invalid_roles)
    {
        assert(enclave_grant_admin_priv(enclave, &result, role, 1) == SGX_SUCCESS && result != 0);
        assert(enclave_revoke_admin_priv(enclave, &result, role, 1) == SGX_SUCCESS && result != 0);
        assert(enclave_begin_role_delete(enclave, &result, role) == SGX_SUCCESS && result != 0);
        assert(enclave_finish_role_delete(enclave, &result, role) == SGX_SUCCESS && result != 0);
        allowed = -1;
        assert(enclave_check_admin_priv(enclave, &result, role, 1, &allowed) == SGX_SUCCESS);
        assert(result != 0 && allowed == 0);
    }
    assert(enclave_grant_admin_priv(enclave, &result, alice, 0) == SGX_SUCCESS && result != 0);
    assert(enclave_revoke_admin_priv(enclave, &result, alice, 0) == SGX_SUCCESS && result != 0);
    assert(enclave_check_admin_priv(enclave, &result, alice, 0, &allowed) == SGX_SUCCESS);
    assert(result != 0 && allowed == 0);
    assert(enclave_check_admin_priv(enclave, &result, alice, 1, nullptr) == SGX_SUCCESS && result != 0);
    expect_check(enclave, alice, 2, 1);

    char longest[256];
    memset(longest, 'a', sizeof(longest));
    longest[255] = '\0';
    assert(enclave_grant_admin_priv(enclave, &result, longest, 1) == SGX_SUCCESS && result == 0);
    expect_check(enclave, longest, 1, 1);
    char role[256] = {};
    for (unsigned i = 0; i < 30; ++i)
    {
        snprintf(role, sizeof(role), "role%u", i);
        assert(enclave_grant_admin_priv(enclave, &result, role, 1) == SGX_SUCCESS && result == 0);
    }
    assert(enclave_grant_admin_priv(enclave, &result, bob, 1) == SGX_SUCCESS && result != 0);
    expect_check(enclave, bob, 1, 0);
    assert(enclave_grant_admin_priv(enclave, &result, alice, 4) == SGX_SUCCESS && result == 0);
    expect_check(enclave, alice, 4, 1);
    assert(enclave_revoke_admin_priv(enclave, &result, alice, 6) == SGX_SUCCESS && result == 0);
    expect_check(enclave, alice, 6, 0);

    assert(enclave_finish_role_delete(enclave, &result, longest) == SGX_SUCCESS && result != 0);
    assert(enclave_begin_role_delete(enclave, &result, bob) == SGX_SUCCESS && result != 0); // full
    assert(enclave_begin_role_delete(enclave, &result, alice) == SGX_SUCCESS && result == 0);
    assert(enclave_begin_role_delete(enclave, &result, alice) == SGX_SUCCESS && result == 0);
    expect_check(enclave, alice, UINT32_MAX, 0);
    assert(enclave_grant_admin_priv(enclave, &result, alice, 1) == SGX_SUCCESS && result != 0);
    uint32_t sealed_size = 0;
    assert(enclave_get_sealed_size(enclave, &result, &sealed_size) == SGX_SUCCESS && result == 0);
    std::vector<uint8_t> sealed(sealed_size);
    assert(enclave_seal_admin(enclave, &result, sealed.data(), sealed_size) == SGX_SUCCESS && result == 0);
    assert(sgx_destroy_enclave(enclave) == SGX_SUCCESS);
    assert(sgx_create_enclave(argv[1], SGX_DEBUG_FLAG, nullptr, nullptr, &enclave, nullptr) == SGX_SUCCESS);
    // Fresh state is empty until a valid sealed snapshot is restored.
    expect_check(enclave, longest, 1, 0);
    assert(enclave_unseal_admin(enclave, &result, sealed.data(), sealed_size) == SGX_SUCCESS && result == 0);
    expect_check(enclave, longest, 1, 1);
    expect_check(enclave, alice, UINT32_MAX, 0);
    assert(enclave_grant_admin_priv(enclave, &result, alice, 1) == SGX_SUCCESS && result != 0);
    assert(enclave_finish_role_delete(enclave, &result, alice) == SGX_SUCCESS && result == 0);
    assert(enclave_finish_role_delete(enclave, &result, alice) == SGX_SUCCESS && result == 0);
    assert(enclave_grant_admin_priv(enclave, &result, bob, 1) == SGX_SUCCESS && result == 0);
    expect_check(enclave, bob, 1, 1);
    sealed.back() ^= 1;
    assert(enclave_unseal_admin(enclave, &result, sealed.data(), sealed_size) == SGX_SUCCESS && result != 0);
    expect_check(enclave, longest, 1, 1);
    assert(enclave_unseal_admin(enclave, &result, sealed.data(), sealed_size - 1) == SGX_SUCCESS && result != 0);
    assert(sgx_destroy_enclave(enclave) == SGX_SUCCESS);
    puts("Administrative ECALL hardware tests passed");
}
