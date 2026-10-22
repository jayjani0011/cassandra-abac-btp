package org.apache.cassandra.auth.catee;

import org.apache.cassandra.auth.Permission;

/** Stable privilege bits, independent of Cassandra Permission enum ordinals. */
public final class CateeAdminPermission
{
    private CateeAdminPermission() { }

    public static int mask(Permission permission)
    {
        switch (permission)
        {
            case CREATE_ATTRIBUTE: return 1 << 0;
            case DROP_ATTRIBUTE: return 1 << 1;
            case ALTER_ATTRIBUTE: return 1 << 2;
            case ASSIGN_USER_ATTRIBUTE: return 1 << 3;
            case REVOKE_USER_ATTRIBUTE: return 1 << 4;
            case ASSIGN_RESOURCE_ATTRIBUTE: return 1 << 5;
            case REVOKE_RESOURCE_ATTRIBUTE: return 1 << 6;
            case CREATE_POLICY: return 1 << 7;
            case DROP_POLICY: return 1 << 8;
            default: throw new IllegalArgumentException("Unsupported CATEE administrative permission: " + permission);
        }
    }
}
