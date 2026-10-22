#ifndef CATEE_ADMIN_PRIVILEGES_H
#define CATEE_ADMIN_PRIVILEGES_H

#include <stdint.h>
#include <string.h>

struct AdminPrivilegeEntry {
    char role_name[256];
    uint32_t priv_mask;
};

// PATEE's fixed role/mask table, with Cassandra role names instead of role OIDs.
struct AdminPrivileges {
    static constexpr unsigned max_admins = 32;
    AdminPrivilegeEntry entries[max_admins]{};
    unsigned count = 0;

    static bool valid_role(const char *role)
    {
        if (role == nullptr || role[0] == '\0')
            return false;
        for (unsigned i = 1; i < sizeof(AdminPrivilegeEntry::role_name); ++i)
            if (role[i] == '\0')
                return true;
        return false;
    }

    bool grant(const char *role, uint32_t mask)
    {
        if (!valid_role(role) || mask == 0)
            return false;
        for (unsigned i = 0; i < count; ++i)
            if (strcmp(entries[i].role_name, role) == 0)
            {
                entries[i].priv_mask |= mask;
                return true;
            }
        if (count == max_admins)
            return false;
        memcpy(entries[count].role_name, role, strlen(role) + 1);
        entries[count++].priv_mask = mask;
        return true;
    }

    bool revoke(const char *role, uint32_t mask)
    {
        if (!valid_role(role) || mask == 0)
            return false;
        for (unsigned i = 0; i < count; ++i)
            if (strcmp(entries[i].role_name, role) == 0)
            {
                entries[i].priv_mask &= ~mask;
                return true;
            }
        return true;
    }

    bool check(const char *role, uint32_t mask) const
    {
        if (!valid_role(role) || mask == 0)
            return false;
        for (unsigned i = 0; i < count; ++i)
            if (strcmp(entries[i].role_name, role) == 0)
                return (entries[i].priv_mask & mask) != 0;
        return false;
    }
};

#endif
