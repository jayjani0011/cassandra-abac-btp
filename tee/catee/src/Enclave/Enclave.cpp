#include "Enclave_t.h"
#include "sgx_utils.h"
#include "AdminPrivileges.h"

// Administrative state starts with no grants in each enclave instance.
static AdminPrivileges admin_privileges;

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
