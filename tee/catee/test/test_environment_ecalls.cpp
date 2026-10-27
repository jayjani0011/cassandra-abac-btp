#include "Enclave_u.h"
#include "sgx_urts.h"
#include <cassert>
#include <initializer_list>

// Exercise the trusted boundary directly, bypassing socket/Java validation.
int main(int argc, char **argv)
{
    assert(argc == 2);
    sgx_enclave_id_t enclave = 0;
    assert(sgx_create_enclave(argv[1], SGX_DEBUG_FLAG, nullptr, nullptr, &enclave, nullptr) == SGX_SUCCESS);
    int result = -1, allowed = -1, debug = -1;
    for (int minute : {0, 539, 540, 840, 1019, 1020, 1439})
        for (int expected : {0, 1})
        {
            assert(enclave_check_env(enclave, &result, 0, minute, 0, "", 0, expected,
                                     0, 1, 0, &allowed, &debug) == SGX_SUCCESS);
            assert(result == 0 && debug == 1);
            assert(allowed == (expected == (minute >= 540 && minute < 1020)));
        }
    assert(enclave_check_env(enclave, &result, 0, 0, 0, "", 0, 0,
                             0, 0, 0, &allowed, &debug) == SGX_SUCCESS);
    assert(result == 0 && allowed == 1);
    assert(enclave_check_env(enclave, &result, 0, 840, 0, "", 1, 1,
                             1, 1, 0, &allowed, &debug) == SGX_SUCCESS);
    assert(result == 0 && allowed == 0);
    assert(enclave_check_env(enclave, &result, 0, 840, 0, "campus", 0, 1,
                             0, 1, 1, &allowed, &debug) == SGX_SUCCESS);
    assert(result == 2 && allowed == 0);
    assert(enclave_check_env(enclave, &result, 7, 840, 0, "", 0, 1,
                             0, 1, 0, &allowed, &debug) == SGX_SUCCESS);
    assert(result == 1 && allowed == 0);
    assert(enclave_check_env(enclave, &result, 0, 1440, 0, "", 0, 1,
                             0, 1, 0, &allowed, &debug) == SGX_SUCCESS);
    assert(result == 1 && allowed == 0);
    assert(enclave_check_env(enclave, &result, 0, 840, 0, "", 0, 2,
                             0, 1, 0, &allowed, &debug) == SGX_SUCCESS);
    assert(result == 1 && allowed == 0);
    for (int mask = 0; mask < 128; ++mask)
    {
        uint8_t days[7]{};
        for (int day = 0; day < 7; ++day) days[day] = (mask >> day) & 1;
        assert(enclave_set_workday(enclave, &result, days) == SGX_SUCCESS && result == 0);
        for (int day = 0; day < 7; ++day)
            for (int expected : {0, 1})
                for (int minute : {0, 840})
                {
                    assert(enclave_check_env(enclave, &result, day, minute, 0, "", expected, 1,
                                             1, 1, 0, &allowed, &debug) == SGX_SUCCESS);
                    assert(result == 0 && allowed == ((expected == days[day]) && minute == 840));
                }
    }
    uint8_t invalid[7]{0, 0, 0, 0, 0, 0, 2};
    assert(enclave_set_workday(enclave, &result, invalid) == SGX_SUCCESS && result == 1);
    assert(enclave_check_env(enclave, &result, 6, 840, 0, "", 1, 1,
                             1, 0, 0, &allowed, &debug) == SGX_SUCCESS && result == 0 && allowed == 1);
    assert(sgx_destroy_enclave(enclave) == SGX_SUCCESS);
}
