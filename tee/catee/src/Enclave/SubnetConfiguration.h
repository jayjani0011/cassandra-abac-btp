#ifndef CATEE_SUBNET_CONFIGURATION_H
#define CATEE_SUBNET_CONFIGURATION_H
#include <stdint.h>
#include <string.h>

// PATEE's fixed named IPv4 table and prefix-mask comparison (patee/tee/Enclave).
struct SubnetConfiguration {
    static constexpr uint32_t capacity = 32;
    struct Entry { char name[64]; uint32_t network; uint32_t prefix; } entries[capacity]{};
    uint32_t count = 0;

    static bool valid_name(const char *name)
    {
        if (name == nullptr || name[0] == '\0') return false;
        for (unsigned i = 1; i < 64; ++i) if (name[i] == '\0') return true;
        return false;
    }
    static uint32_t mask(uint32_t prefix) { return prefix == 0 ? 0 : UINT32_MAX << (32 - prefix); }
    bool valid() const
    {
        if (count > capacity) return false;
        for (uint32_t i = 0; i < count; ++i)
        {
            const Entry &entry = entries[i];
            if (!valid_name(entry.name) || entry.prefix > 32
                || (entry.network & mask(entry.prefix)) != entry.network) return false;
            for (uint32_t j = 0; j < i; ++j)
                if (strcmp(entry.name, entries[j].name) == 0) return false;
        }
        return true;
    }
    bool contains(const char *name) const
    {
        for (uint32_t i = 0; i < count; ++i)
            if (strcmp(entries[i].name, name) == 0) return true;
        return false;
    }
    bool set(const char *name, uint32_t network, uint32_t prefix)
    {
        if (!valid_name(name) || prefix > 32 || (network & mask(prefix)) != network) return false;
        uint32_t i = 0;
        for (; i < count; ++i) if (strcmp(entries[i].name, name) == 0) break;
        if (i == count && count == capacity) return false;
        Entry replacement{};
        memcpy(replacement.name, name, strlen(name) + 1);
        replacement.network = network;
        replacement.prefix = prefix;
        entries[i] = replacement;
        if (i == count) ++count;
        return true;
    }
    bool matches(const char *name, uint32_t ip) const
    {
        for (uint32_t i = 0; i < count; ++i)
            if (strcmp(entries[i].name, name) == 0)
                return (ip & mask(entries[i].prefix)) == entries[i].network;
        return false;
    }
};
#endif
