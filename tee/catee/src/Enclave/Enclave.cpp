#include "Enclave_t.h"
#include "sgx_utils.h"

// Development fixture: configuration and comparison both live in the enclave.
namespace {
constexpr int start_minute = 9 * 60;
constexpr int end_minute = 17 * 60;
}

int check_worktime(int minute, int *allowed, int *debug)
{
    if (allowed == nullptr || debug == nullptr)
        return 1;
    *allowed = 0;
    *debug = (sgx_self_report()->body.attributes.flags & SGX_FLAGS_DEBUG) != 0;
    if (minute < 0 || minute >= 24 * 60)
        return 1;

    *allowed = minute >= start_minute && minute < end_minute;
    return 0;
}
