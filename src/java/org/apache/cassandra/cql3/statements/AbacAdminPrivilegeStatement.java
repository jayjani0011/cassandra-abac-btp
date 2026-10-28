package org.apache.cassandra.cql3.statements;

import java.util.Set;

import org.apache.cassandra.audit.AuditLogContext;
import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.auth.CateeAdministration;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.auth.RoleResource;
import org.apache.cassandra.cql3.RoleName;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.messages.ResultMessage;

public final class AbacAdminPrivilegeStatement extends AuthenticationStatement
{
    private final RoleResource role;
    private final Set<Permission> operations;
    private final boolean grant;

    public AbacAdminPrivilegeStatement(RoleName role, Set<Permission> operations, boolean grant)
    {
        this.role = RoleResource.role(role.getName());
        this.operations = Set.copyOf(operations);
        this.grant = grant;
    }

    public void authorize(ClientState state)
    {
        CateeAdministration.ensureSuperuser(state);
    }

    public void validate(ClientState state)
    {
        CateeAdministration.ensureSuperuser(state);
    }

    public ResultMessage execute(ClientState state)
    {
        CateeAdministration.modifyPrivileges(state, role, operations, grant);
        return null;
    }

    public AuditLogContext getAuditLogContext()
    {
        return new AuditLogContext(grant ? AuditLogEntryType.GRANT : AuditLogEntryType.REVOKE);
    }
}
