#include "Enclave_t.h"
#include "sgx_utils.h"
#include "sgx_tseal.h"
#include "AdminPrivileges.h"

// Administrative state starts with no grants in each enclave instance.
static AdminPrivileges admin_privileges;

struct SealedAdminState {
    uint32_t version;
    AdminPrivileges privileges;
};

int enclave_get_sealed_size(uint32_t *sealed_size)
{
    if (sealed_size == nullptr)
        return 1;
    *sealed_size = sgx_calc_sealed_data_size(0, sizeof(SealedAdminState));
    return *sealed_size == UINT32_MAX ? 1 : 0;
}

int enclave_seal_admin(uint8_t *sealed_data, uint32_t sealed_size)
{
    uint32_t required = 0;
    if (enclave_get_sealed_size(&required) != 0 || sealed_data == nullptr || sealed_size != required)
        return 1;
    SealedAdminState state{1, admin_privileges};
    return sgx_seal_data(0, nullptr, sizeof(state), reinterpret_cast<const uint8_t *>(&state),
                         sealed_size, reinterpret_cast<sgx_sealed_data_t *>(sealed_data)) == SGX_SUCCESS ? 0 : 1;
}

int enclave_unseal_admin(const uint8_t *sealed_data, uint32_t sealed_size)
{
    uint32_t required = 0;
    if (enclave_get_sealed_size(&required) != 0 || sealed_data == nullptr || sealed_size != required)
        return 1;
    const auto *sealed = reinterpret_cast<const sgx_sealed_data_t *>(sealed_data);
    if (sgx_get_encrypt_txt_len(sealed) != sizeof(SealedAdminState) || sgx_get_add_mac_txt_len(sealed) != 0)
        return 1;
    SealedAdminState state{};
    uint32_t size = sizeof(state), additional_size = 0;
    if (sgx_unseal_data(sealed, nullptr, &additional_size, reinterpret_cast<uint8_t *>(&state), &size) != SGX_SUCCESS
        || size != sizeof(state) || state.version != 1 || state.privileges.count > AdminPrivileges::max_admins)
        return 1;
    for (unsigned i = 0; i < state.privileges.count; ++i)
    {
        if (!AdminPrivileges::valid_role(state.privileges.entries[i].role_name))
            return 1;
        for (unsigned j = 0; j < i; ++j)
            if (strcmp(state.privileges.entries[i].role_name, state.privileges.entries[j].role_name) == 0)
                return 1;
    }
    admin_privileges = state.privileges;
    return 0;
}

int enclave_grant_admin_priv(const char *role_name, uint32_t priv_mask)
{
    return admin_privileges.grant(role_name, priv_mask) ? 0 : 1;
}

int enclave_revoke_admin_priv(const char *role_name, uint32_t priv_mask)
{
    return admin_privileges.revoke(role_name, priv_mask) ? 0 : 1;
}

int enclave_check_admin_priv(const char *role_name, uint32_t priv_mask, int *allowed)
{
    if (allowed == nullptr)
        return 1;
    *allowed = 0;
    if (!AdminPrivileges::valid_role(role_name) || priv_mask == 0)
        return 1;
    *allowed = admin_privileges.check(role_name, priv_mask);
    return 0;
}

// Development fixture: configuration and comparison both live in the enclave.
struct WorktimeWindow {
    int start_minute;
    int end_minute;
};

static constexpr WorktimeWindow worktime_window{9 * 60, 17 * 60};

int check_worktime(int minute, int *allowed, int *debug)
{
    if (allowed == nullptr || debug == nullptr)
        return 1;
    *allowed = 0;
    *debug = (sgx_self_report()->body.attributes.flags & SGX_FLAGS_DEBUG) != 0;
    if (minute < 0 || minute >= 24 * 60)
        return 1;

    *allowed = minute >= worktime_window.start_minute && minute < worktime_window.end_minute;
    return 0;
}
