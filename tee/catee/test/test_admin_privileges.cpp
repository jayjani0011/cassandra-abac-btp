#include "AdminPrivileges.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

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
    puts("Administrative privilege store tests passed");
}
