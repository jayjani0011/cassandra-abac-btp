#include "SubnetConfiguration.h"
#include <cassert>
#include <cstdio>

int main()
{
    for (unsigned prefix = 0; prefix <= 32; ++prefix)
    {
        SubnetConfiguration config;
        uint32_t mask = SubnetConfiguration::mask(prefix);
        uint32_t network = 0xabcdef12 & mask;
        assert(config.set("network", network, prefix) && config.valid());
        assert(config.matches("network", network));
        assert(config.matches("network", network | ~mask));
        if (prefix > 0) assert(!config.matches("network", network ^ 0x80000000));
        assert(!config.matches("absent", network));
    }
    SubnetConfiguration config;
    for (unsigned i = 0; i < 32; ++i)
    {
        char name[64];
        std::snprintf(name, sizeof(name), "network%u", i);
        assert(config.set(name, 0, 0));
    }
    assert(!config.set("overflow",0,0));
    assert(config.set("network0",0x7f000001,32));
    assert(config.valid());
    config.entries[0].prefix = 33;
    assert(!config.valid());
    config.entries[0].prefix = 0;
    assert(!config.valid());
    config.entries[0].network = 0;
    config.entries[1] = config.entries[0];
    assert(!config.valid());
    config.count = 33;
    assert(!config.valid());
    char unbounded[64];
    for (char &value : unbounded) value = 'x';
    assert(!SubnetConfiguration::valid_name(unbounded));
    assert(!SubnetConfiguration::valid_name(nullptr));
}
