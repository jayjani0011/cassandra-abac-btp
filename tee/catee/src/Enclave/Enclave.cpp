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

// Version 1 used the same fixed table without deletion markers.
struct LegacyAdminEntry {
    char role_name[256];
    uint32_t priv_mask;
};
struct LegacySealedAdminState {
    uint32_t version;
    LegacyAdminEntry entries[AdminPrivileges::max_admins];
    uint32_t count;
};
static_assert(sizeof(LegacySealedAdminState) == 8328, "Unexpected version-1 layout");
static_assert(sizeof(SealedAdminState) == 8456, "Unexpected version-2 layout");

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
    SealedAdminState state{2, admin_privileges};
    return sgx_seal_data(0, nullptr, sizeof(state), reinterpret_cast<const uint8_t *>(&state),
                         sealed_size, reinterpret_cast<sgx_sealed_data_t *>(sealed_data)) == SGX_SUCCESS ? 0 : 1;
}

int enclave_unseal_admin(const uint8_t *sealed_data, uint32_t sealed_size)
{
    if (sealed_data == nullptr || sealed_size < sizeof(sgx_sealed_data_t)) return 1;
    const auto *sealed = reinterpret_cast<const sgx_sealed_data_t *>(sealed_data);
    uint32_t payload_size = sgx_get_encrypt_txt_len(sealed);
    if ((payload_size != sizeof(SealedAdminState) && payload_size != sizeof(LegacySealedAdminState))
        || sgx_get_add_mac_txt_len(sealed) != 0
        || sealed_size != sgx_calc_sealed_data_size(0, payload_size))
        return 1;
    // Do not replace active state until authentication and all validation succeed.
    uint8_t payload[sizeof(SealedAdminState)]{};
    uint32_t size = sizeof(payload), additional_size = 0;
    if (sgx_unseal_data(sealed, nullptr, &additional_size, payload, &size) != SGX_SUCCESS
        || size != payload_size)
        return 1;
    SealedAdminState state{};
    if (size == sizeof(LegacySealedAdminState))
    {
        LegacySealedAdminState legacy{};
        memcpy(&legacy, payload, sizeof(legacy));
        if (legacy.version != 1 || legacy.count > AdminPrivileges::max_admins) return 1;
        state.version = 2;
        state.privileges.count = legacy.count;
        for (unsigned i = 0; i < legacy.count; ++i)
        {
            memcpy(state.privileges.entries[i].role_name, legacy.entries[i].role_name, 256);
            state.privileges.entries[i].priv_mask = legacy.entries[i].priv_mask;
        }
    }
    else
    {
        memcpy(&state, payload, sizeof(state));
        if (state.version != 2 || state.privileges.count > AdminPrivileges::max_admins) return 1;
    }
    for (unsigned i = 0; i < state.privileges.count; ++i)
    {
        if (!AdminPrivileges::valid_role(state.privileges.entries[i].role_name)
            || state.privileges.entries[i].deletion_pending > 1
            || (state.privileges.entries[i].deletion_pending && state.privileges.entries[i].priv_mask != 0))
            return 1;
        for (unsigned j = 0; j < i; ++j)
            if (strcmp(state.privileges.entries[i].role_name, state.privileges.entries[j].role_name) == 0)
                return 1;
    }
    admin_privileges = state.privileges;
    return 0;
}

int enclave_begin_role_delete(const char *role_name)
{
    return admin_privileges.begin_delete(role_name) ? 0 : 1;
}

int enclave_finish_role_delete(const char *role_name)
{
    return admin_privileges.finish_delete(role_name) ? 0 : 1;
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
