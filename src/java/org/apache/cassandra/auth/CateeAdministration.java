package org.apache.cassandra.auth;

import java.io.IOException;
import java.util.Set;

import org.apache.cassandra.auth.catee.CateeAdminPermission;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.service.ClientState;

/** PATEE-style superuser-or-enclave check for existing ABAC administration handlers. */
public final class CateeAdministration
{
    private CateeAdministration() { }

    public static void ensureSuperuser(ClientState state)
    {
        state.ensureNotAnonymous();
        if (!state.isSuper())
            throw new UnauthorizedException("Only superusers may manage CATEE administrative grants");
    }

    public static void modifyPrivileges(ClientState state, RoleResource role, Set<Permission> operations, boolean grant)
    {
        ensureSuperuser(state);
        IAuthorizer configured = DatabaseDescriptor.getAuthorizer();
        if (!(configured instanceof CassandraAuthorizer) || !((CassandraAuthorizer) configured).isCateeEnabled())
            throw new InvalidRequestException("CATEE is not enabled");
        IRoleManager roles = DatabaseDescriptor.getRoleManager();
        if (!roles.isExistingRole(role))
            throw new InvalidRequestException("Role " + role.getRoleName() + " does not exist");
        if (grant && !roles.canLogin(role))
            throw new InvalidRequestException("CATEE grants require a role with LOGIN = true");
        int mask = 0;
        if (operations.isEmpty())
            throw new InvalidRequestException("At least one CATEE administrative permission is required");
        for (Permission operation : operations)
        {
            try { mask |= CateeAdminPermission.mask(operation); }
            catch (IllegalArgumentException e) { throw new InvalidRequestException(e.getMessage()); }
        }
        try
        {
            ((CassandraAuthorizer) configured).modifyCateeAdminPrivilege(role.getRoleName(), mask, grant);
        }
        catch (IOException | RuntimeException | LinkageError e)
        {
            throw new InvalidRequestException("Required CATEE grant update failed: " + e.getMessage());
        }
    }

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
