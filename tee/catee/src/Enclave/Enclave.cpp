#include "Enclave_t.h"
#include "sgx_utils.h"
#include "sgx_tseal.h"
#include "AdminPrivileges.h"
#include <string.h>
#include "SubnetConfiguration.h"

// Administrative state starts with no grants in each enclave instance.
static AdminPrivileges admin_privileges;

// Configuration and comparison both live in the enclave.
struct WorktimeWindow {
    int start_minute;
    int end_minute;
};

static WorktimeWindow worktime_window{0, 24 * 60};

// PATEE indexes Sunday=0 and initializes Monday through Friday as working days.
static uint8_t workdays[7]{0, 1, 1, 1, 1, 1, 0};

struct LegacySealedAdminStateV3 {
    uint32_t version;
    AdminPrivileges privileges;
    WorktimeWindow window;
};

static SubnetConfiguration subnets;

struct LegacySealedAdminStateV4 {
    uint32_t version;
    AdminPrivileges privileges;
    WorktimeWindow window;
    uint8_t days[7];
};

struct SealedAdminState {
    uint32_t version;
    AdminPrivileges privileges;
    WorktimeWindow window;
    uint8_t days[7];
    SubnetConfiguration subnets;
};

struct LegacySealedAdminStateV2 {
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
static_assert(sizeof(LegacySealedAdminStateV2) == 8456, "Unexpected version-2 layout");
static_assert(sizeof(LegacySealedAdminStateV3) == 8464, "Unexpected version-3 layout");
static_assert(sizeof(LegacySealedAdminStateV4) == 8472, "Unexpected version-4 layout");
static_assert(sizeof(SealedAdminState) == 10780, "Unexpected version-5/6/7 layout");

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
    SealedAdminState state{7, admin_privileges, worktime_window, {}, subnets};
    memcpy(state.days, workdays, sizeof(workdays));
    return sgx_seal_data(0, nullptr, sizeof(state), reinterpret_cast<const uint8_t *>(&state),
                         sealed_size, reinterpret_cast<sgx_sealed_data_t *>(sealed_data)) == SGX_SUCCESS ? 0 : 1;
}

int enclave_unseal_admin(const uint8_t *sealed_data, uint32_t sealed_size)
{
    if (sealed_data == nullptr || sealed_size < sizeof(sgx_sealed_data_t)) return 1;
    const auto *sealed = reinterpret_cast<const sgx_sealed_data_t *>(sealed_data);
    uint32_t payload_size = sgx_get_encrypt_txt_len(sealed);
    if ((payload_size != sizeof(SealedAdminState) && payload_size != sizeof(LegacySealedAdminState)
         && payload_size != sizeof(LegacySealedAdminStateV2) && payload_size != sizeof(LegacySealedAdminStateV3) && payload_size != sizeof(LegacySealedAdminStateV4))
        || sgx_get_add_mac_txt_len(sealed) != 0
        || sealed_size != sgx_calc_sealed_data_size(0, payload_size))
        return 1;
    // Do not replace active state until authentication and all validation succeed.
    uint8_t payload[sizeof(SealedAdminState)]{};
    uint32_t size = sizeof(payload), additional_size = 0;
    if (sgx_unseal_data(sealed, nullptr, &additional_size, payload, &size) != SGX_SUCCESS
        || size != payload_size)
        return 1;
    SealedAdminState state{7, {}, {0, 24 * 60}, {0, 1, 1, 1, 1, 1, 0}, {}};
    if (size == sizeof(LegacySealedAdminState))
    {
        LegacySealedAdminState legacy{};
        memcpy(&legacy, payload, sizeof(legacy));
        if (legacy.version != 1 || legacy.count > AdminPrivileges::max_admins) return 1;
        state.privileges.count = legacy.count;
        for (unsigned i = 0; i < legacy.count; ++i)
        {
            memcpy(state.privileges.entries[i].role_name, legacy.entries[i].role_name, 256);
            state.privileges.entries[i].priv_mask = legacy.entries[i].priv_mask;
        }
    }
    else if (size == sizeof(LegacySealedAdminStateV2))
    {
        LegacySealedAdminStateV2 legacy{};
        memcpy(&legacy, payload, sizeof(legacy));
        if (legacy.version != 2) return 1;
        state.privileges = legacy.privileges;
    }
    else if (size == sizeof(LegacySealedAdminStateV3))
    {
        LegacySealedAdminStateV3 legacy{};
        memcpy(&legacy, payload, sizeof(legacy));
        if (legacy.version != 3) return 1;
        state.privileges = legacy.privileges;
        state.window = legacy.window;
    }
    else if (size == sizeof(LegacySealedAdminStateV4))
    {
        LegacySealedAdminStateV4 legacy{};
        memcpy(&legacy, payload, sizeof(legacy));
        if (legacy.version != 4) return 1;
        state.privileges = legacy.privileges;
        state.window = legacy.window;
        memcpy(state.days, legacy.days, sizeof(state.days));
    }
    else
    {
        memcpy(&state, payload, sizeof(state));
        if (state.version != 5 && state.version != 6 && state.version != 7) return 1;
    }
    if (!state.subnets.valid()) return 1;
    for (uint8_t day : state.days)
        if (day > 1) return 1;
    if (state.privileges.count > AdminPrivileges::max_admins
        || state.window.start_minute < 0 || state.window.start_minute >= state.window.end_minute
        || state.window.end_minute > 1440)
        return 1;
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
    // Pre-v6 masks could contain raw bit 9, which had no SET_EA_ADMIN meaning.
    // Validate the original snapshot first, then remove only that newly assigned bit.
    uint32_t source_version = 0;
    memcpy(&source_version, payload, sizeof(source_version));
    if (source_version <= 5)
        for (unsigned i = 0; i < state.privileges.count; ++i)
            state.privileges.entries[i].priv_mask &= ~AdminPrivileges::set_ea_admin;
    admin_privileges = state.privileges;
    worktime_window = state.window;
    memcpy(workdays, state.days, sizeof(workdays));
    subnets = state.subnets;
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

int enclave_set_subnet(const char *name, uint32_t network, uint32_t prefix)
{
    return subnets.set(name, network, prefix) ? 0 : 1;
}

int enclave_subnet_exists(const char *name, int *exists)
{
    if (!SubnetConfiguration::valid_name(name) || exists == nullptr) return 1;
    *exists = subnets.contains(name);
    return 0;
}

int enclave_set_workday(const uint8_t *days)
{
    if (days == nullptr) return 1;
    for (int day = 0; day < 7; ++day)
        if (days[day] > 1) return 1;
    memcpy(workdays, days, sizeof(workdays));
    return 0;
}

int enclave_set_timewindow(int start_minute, int end_minute)
{
    if (start_minute < 0 || start_minute >= end_minute || end_minute > 1440)
        return 1;
    worktime_window = {start_minute, end_minute};
    return 0;
}

// Interface and combined-condition pattern adapted from PATEE's enclave_check_env.
// Reference: harshit-jain52/postgres, patee, 79d3f29576419d457c516bcda5d757d99f104b14.
int enclave_check_env(int day_of_week, int current_minute, uint32_t client_ip,
                      const char *subnet_name, int workday_value, int worktime_value,
                      int check_workday, int check_worktime, int check_subnet,
                      int *allowed, int *debug)
{
    if (allowed == nullptr || debug == nullptr)
        return 1;
    *allowed = 0;
    *debug = (sgx_self_report()->body.attributes.flags & SGX_FLAGS_DEBUG) != 0;
    if (day_of_week < 0 || day_of_week > 6 || current_minute < 0 || current_minute >= 1440
        || subnet_name == nullptr || strlen(subnet_name) > 63
        || (workday_value != 0 && workday_value != 1)
        || (worktime_value != 0 && worktime_value != 1)
        || (check_workday != 0 && check_workday != 1)
        || (check_worktime != 0 && check_worktime != 1)
        || (check_subnet != 0 && check_subnet != 1))
        return 1;
    *allowed = 1;
    if (check_workday && workday_value != workdays[day_of_week])
    {
        *allowed = 0;
        return 0;
    }
    if (check_worktime)
        *allowed = worktime_value == (current_minute >= worktime_window.start_minute
                                     && current_minute <= worktime_window.end_minute);
    if (*allowed && check_subnet) *allowed = subnets.matches(subnet_name, client_ip);
    return 0;
}
