package org.apache.cassandra.auth;

import java.io.IOException;

import org.apache.cassandra.auth.catee.CateeAdminPermission;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.ClientState;

/** PATEE-style superuser-or-enclave check for existing ABAC administration handlers. */
public final class CateeAdministration
{
    private CateeAdministration() { }

    public static void ensurePermission(ClientState state, Permission permission, IResource resource)
    {
        IAuthorizer authorizer = DatabaseDescriptor.getAuthorizer();
        if (!(authorizer instanceof CassandraAuthorizer) || !((CassandraAuthorizer) authorizer).isCateeEnabled())
        {
            state.ensurePermission(permission, resource);
            return;
        }
        state.ensureNotAnonymous();
        if (state.isSuper())
            return;
        int mask = CateeAdminPermission.mask(permission);
        try
        {
            if (((CassandraAuthorizer) authorizer).checkCateeAdminPrivilege(state.getUser(), mask))
                return;
        }
        catch (IOException | RuntimeException | LinkageError e)
        {
            throw new UnauthorizedException("Required CATEE administration check failed", e);
        }
        throw new UnauthorizedException("User " + state.getUser().getName() + " has no CATEE " + permission + " privilege");
    }
}
