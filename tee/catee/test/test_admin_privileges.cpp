#include "AdminPrivileges.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <map>
#include <random>
#include <string>

int main()
{
    AdminPrivileges privileges;
    assert(!privileges.check("alice", 1));
    assert(privileges.grant("alice", 1));
    assert(privileges.grant("alice", 2));
    assert(privileges.count == 1);
    assert(privileges.check("alice", 1));
    assert(privileges.check("alice", 2));
    assert(!privileges.check("alice", 4));
    // Like PATEE, check tests whether any requested bit is present.
    assert(privileges.check("alice", 6));
    assert(!privileges.check("Alice", 1));
    assert(!privileges.check("bob", 1));
    assert(privileges.revoke("alice", 1));
    assert(!privileges.check("alice", 1));
    assert(privileges.check("alice", 2));
    assert(privileges.revoke("missing", 1));
    assert(privileges.revoke("alice", 2));
    assert(!privileges.check("alice", 3));
    assert(privileges.grant("alice", 4));
    assert(privileges.count == 1);

    char longest[256];
    memset(longest, 'a', sizeof(longest) - 1);
    longest[255] = '\0';
    assert(privileges.grant(longest, 1));
    assert(privileges.check(longest, 1));
    char oversized[257];
    memset(oversized, 'a', sizeof(oversized) - 1);
    oversized[256] = '\0';
    const char *invalid_roles[] = {nullptr, "", oversized};
    for (const char *invalid : invalid_roles)
    {
        assert(!privileges.grant(invalid, 1));
        assert(!privileges.revoke(invalid, 1));
        assert(!privileges.check(invalid, 1));
    }
    assert(!privileges.grant("alice", 0));
    assert(!privileges.revoke("alice", 0));
    assert(!privileges.check("alice", 0));

    AdminPrivileges full;
    char role[32];
    for (unsigned i = 0; i < AdminPrivileges::max_admins; ++i)
    {
        snprintf(role, sizeof(role), "role%u", i);
        assert(full.grant(role, 1));
    }
    assert(!full.grant("overflow", 1));
    assert(!full.check("overflow", 1));
    assert(full.grant("role0", 2));
    assert(full.check("role0", 2));
    assert(full.revoke("role0", 3));
    assert(!full.check("role0", 3));
    // PATEE retains entries after revocation; regrant uses the existing slot.
    assert(!full.grant("overflow", 1));
    assert(full.grant("role0", 4));
    assert(!full.finish_delete("role0")); // Cannot bypass BEGIN on an active entry.
    assert(!full.begin_delete("overflow")); // A marker needs a slot; fail safely.
    assert(full.begin_delete("role0"));
    assert(full.begin_delete("role0"));
    assert(!full.check("role0", UINT32_MAX));
    assert(!full.grant("role0", 1));
    assert(full.revoke("role0", UINT32_MAX));
    assert(!full.grant("role0", 1)); // REVOKE must not clear the marker.
    assert(full.finish_delete("role0"));
    assert(full.finish_delete("role0"));
    assert(full.count == AdminPrivileges::max_admins - 1);
    for (unsigned i = 1; i < AdminPrivileges::max_admins; ++i)
    {
        snprintf(role, sizeof(role), "role%u", i);
        assert(full.check(role, 1)); // Compaction must preserve all other entries.
    }
    assert(full.grant("overflow", 1));
    assert(!full.begin_delete("absent"));
    assert(full.begin_delete("overflow"));
    assert(full.finish_delete("overflow"));
    assert(full.begin_delete("absent"));
    assert(!full.grant("absent", 1));
    assert(full.finish_delete("absent"));
    for (const char *invalid : invalid_roles)
    {
        assert(!full.begin_delete(invalid));
        assert(!full.finish_delete(invalid));
    }
    // Repeated churn must not leak slots or old grants.
    for (unsigned i = 0; i < 1000; ++i)
    {
        assert(full.grant("churn", 7));
        assert(full.begin_delete("churn"));
        assert(full.finish_delete("churn"));
        assert(!full.check("churn", 7));
    }
    // Deterministic mixed-operation stress against an independent reference model.
    AdminPrivileges mixed;
    std::map<std::string, std::pair<uint32_t, bool>> reference;
    std::mt19937 random(20261025);
    for (unsigned step = 0; step < 10000; ++step)
    {
        std::string name = "mixed" + std::to_string(random() % 48);
        uint32_t mask = 1u << (random() % 32);
        auto found = reference.find(name);
        bool present = found != reference.end();
        bool room = reference.size() < AdminPrivileges::max_admins;
        switch (random() % 4)
        {
            case 0:
            {
                bool expected = present ? !found->second.second : room;
                assert(mixed.grant(name.c_str(), mask) == expected);
                if (expected) reference[name].first |= mask;
                break;
            }
            case 1:
                assert(mixed.revoke(name.c_str(), mask));
                if (present) found->second.first &= ~mask;
                break;
            case 2:
                assert(mixed.begin_delete(name.c_str()) == (present || room));
                if (present || room) reference[name] = {0, true};
                break;
            case 3:
            {
                bool expected = !present || found->second.second;
                assert(mixed.finish_delete(name.c_str()) == expected);
                if (expected) reference.erase(name);
                break;
            }
        }
        assert(mixed.count == reference.size());
        for (unsigned i = 0; i < 48; ++i)
        {
            std::string candidate = "mixed" + std::to_string(i);
            auto entry = reference.find(candidate);
            bool expected = entry != reference.end() && !entry->second.second && entry->second.first != 0;
            assert(mixed.check(candidate.c_str(), UINT32_MAX) == expected);
        }
    }
    puts("Administrative privilege store tests passed");
}
