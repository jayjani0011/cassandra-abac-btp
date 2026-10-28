#include "Enclave_t.h"
#include "sgx_utils.h"
#include <string.h>
#include "SubnetConfiguration.h"

// Configuration and comparison both live in the enclave.
struct WorktimeWindow {
    int start_minute;
    int end_minute;
};

static WorktimeWindow worktime_window{0, 24 * 60};

// PATEE indexes Sunday=0 and initializes Monday through Friday as working days.
static uint8_t workdays[7]{0, 1, 1, 1, 1, 1, 0};

static SubnetConfiguration subnets;

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
