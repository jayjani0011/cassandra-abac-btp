package org.apache.cassandra.auth;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.cassandra.auth.catee.CateeAdminPermission;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.service.ClientState;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CateeAdministrationTest extends CQLTester
{
    private IAuthorizer previous;
    private TestAuthorizer authorizer;
    private ClientState state;

    private static class TestAuthorizer extends CassandraAuthorizer
    {
        boolean enabled = true;
        Boolean allowed = false;
        int checks, observedMask;
        boolean modified, granted;
        String target;

        void modifyCateeAdminPrivilege(String role, int mask, boolean grant) throws IOException
        {
            if (allowed == null) throw new IOException("Service unavailable");
            modified = true; target = role; observedMask = mask; granted = grant;
        }
        AuthenticatedUser observedUser;

        boolean isCateeEnabled() { return enabled; }
        boolean checkCateeAdminPrivilege(AuthenticatedUser user, int mask) throws IOException
        {
            checks++;
            observedUser = user;
            observedMask = mask;
            if (allowed == null) throw new IOException("Service unavailable");
            return allowed;
        }
    }

    @Before
    public void setupAdministration()
    {
        previous = DatabaseDescriptor.getAuthorizer();
        authorizer = new TestAuthorizer();
        DatabaseDescriptor.setAuthorizer(authorizer);
        state = mock(ClientState.class);
        when(state.getUser()).thenReturn(new AuthenticatedUser("administrator"));
    }

    @After
    public void restoreAuthorizer()
    {
        DatabaseDescriptor.setAuthorizer(previous);
    }

    private void expectDenied()
    {
        try
        {
            CateeAdministration.ensurePermission(state, Permission.CREATE_ATTRIBUTE, RoleResource.root());
            fail("Expected authorization rejection");
        }
        catch (UnauthorizedException expected) { }
    }

    @Test
    public void mapsSupportedPermissionsToStableIndependentBits()
    {
        Permission[] permissions = {Permission.CREATE_ATTRIBUTE, Permission.DROP_ATTRIBUTE, Permission.ALTER_ATTRIBUTE,
            Permission.ASSIGN_USER_ATTRIBUTE, Permission.REVOKE_USER_ATTRIBUTE, Permission.ASSIGN_RESOURCE_ATTRIBUTE,
            Permission.REVOKE_RESOURCE_ATTRIBUTE, Permission.CREATE_POLICY, Permission.DROP_POLICY, Permission.SET_EA_ADMIN};
        Set<Integer> bits = new HashSet<>();
        for (int i = 0; i < permissions.length; ++i)
        {
            int mask = CateeAdminPermission.mask(permissions[i]);
            assertEquals(1 << i, mask);
            assertTrue(bits.add(mask));
            authorizer.allowed = true;
            CateeAdministration.ensurePermission(state, permissions[i], RoleResource.root());
            assertEquals(mask, authorizer.observedMask);
        }
        try { CateeAdminPermission.mask(Permission.SELECT); fail("Not an administrative operation"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void enclaveDecisionReplacesOrdinaryPermissionsAndFailureRejects()
    {
        expectDenied();
        authorizer.allowed = true;
        CateeAdministration.ensurePermission(state, Permission.CREATE_ATTRIBUTE, RoleResource.root());
        assertSame(state.getUser(), authorizer.observedUser);
        authorizer.allowed = null;
        expectDenied();
        assertEquals(3, authorizer.checks);
        verify(state, never()).ensurePermission(Permission.CREATE_ATTRIBUTE, RoleResource.root());
    }

    @Test
    public void disabledModeKeepsOriginalScopedCheck()
    {
        authorizer.enabled = false;
        IResource resource = DataResource.table("ks", "table1");
        CateeAdministration.ensurePermission(state, Permission.ASSIGN_RESOURCE_ATTRIBUTE, resource);
        verify(state).ensurePermission(Permission.ASSIGN_RESOURCE_ATTRIBUTE, resource);
        assertEquals(0, authorizer.checks);
    }

    @Test
    public void superuserDoesNotNeedRunningService()
    {
        when(state.isSuper()).thenReturn(true);
        authorizer.allowed = null;
        CateeAdministration.ensurePermission(state, Permission.CREATE_ATTRIBUTE, RoleResource.root());
        verify(state).ensureNotAnonymous();
        assertEquals(0, authorizer.checks);
    }

    @Test
    public void anonymousCallerIsRejectedBeforeEnclaveCheck()
    {
        doThrow(new UnauthorizedException("Anonymous caller")).when(state).ensureNotAnonymous();
        authorizer.allowed = true;
        expectDenied();
        assertEquals(0, authorizer.checks);
    }

    @Test
    public void otherAuthorizersKeepOriginalPermissionCheck()
    {
        DatabaseDescriptor.setAuthorizer(new AllowAllAuthorizer());
        CateeAdministration.ensurePermission(state, Permission.CREATE_ATTRIBUTE, RoleResource.root());
        verify(state).ensurePermission(Permission.CREATE_ATTRIBUTE, RoleResource.root());
        assertEquals(0, authorizer.checks);
    }
    @Test
    public void provisioningValidatesCallerTargetModeAndTransport()
    {
        IRoleManager previousRoles = DatabaseDescriptor.getRoleManager();
        IRoleManager roles = mock(IRoleManager.class);
        RoleResource target = RoleResource.role("alice");
        Set<Permission> operations = Set.of(Permission.CREATE_POLICY, Permission.DROP_POLICY);
        DatabaseDescriptor.setRoleManager(roles);
        try
        {
            try { CateeAdministration.modifyPrivileges(state, target, operations, true); fail(); }
            catch (UnauthorizedException expected) { }
            verifyNoInteractions(roles);
            when(state.isSuper()).thenReturn(true);
            authorizer.enabled = false;
            expectInvalidProvisioning(target, operations, true, "not enabled");
            authorizer.enabled = true;
            expectInvalidProvisioning(target, operations, true, "does not exist");
            when(roles.isExistingRole(target)).thenReturn(true);
            expectInvalidProvisioning(target, operations, true, "LOGIN = true");
            // Revocation remains possible for a role whose login was disabled.
            CateeAdministration.modifyPrivileges(state, target, operations, false);
            assertTrue(authorizer.modified);
            assertFalse(authorizer.granted);
            when(roles.canLogin(target)).thenReturn(true);
            CateeAdministration.modifyPrivileges(state, target, operations, true);
            assertTrue(authorizer.granted);
            assertEquals("alice", authorizer.target);
            assertEquals((1 << 7) | (1 << 8), authorizer.observedMask);
            expectInvalidProvisioning(target, Set.of(Permission.SELECT), true, "Unsupported CATEE");
            expectInvalidProvisioning(target, Set.of(), true, "At least one");
            authorizer.allowed = null;
            expectInvalidProvisioning(target, operations, true, "Required CATEE grant update failed");
            expectInvalidProvisioning(target, operations, false, "Required CATEE grant update failed");
        }
        finally { DatabaseDescriptor.setRoleManager(previousRoles); }
    }

    private void expectInvalidProvisioning(RoleResource role, Set<Permission> operations, boolean grant, String message)
    {
        try { CateeAdministration.modifyPrivileges(state, role, operations, grant); fail("Expected rejection"); }
        catch (InvalidRequestException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(message)); }
    }

    @Test
    public void parsesProtectedCommandsWithoutBreakingRoleGrants()
    {
        assertTrue(org.apache.cassandra.cql3.QueryProcessor.parseStatement(
            "GRANT ABAC_ADMIN { CREATE_POLICY, DROP_POLICY } TO alice")
            instanceof org.apache.cassandra.cql3.statements.AbacAdminPrivilegeStatement);
        assertTrue(org.apache.cassandra.cql3.QueryProcessor.parseStatement(
            "REVOKE ABAC_ADMIN { CREATE_POLICY } FROM alice")
            instanceof org.apache.cassandra.cql3.statements.AbacAdminPrivilegeStatement);
        assertTrue(org.apache.cassandra.cql3.QueryProcessor.parseStatement("GRANT abac_admin TO alice")
            instanceof org.apache.cassandra.cql3.statements.GrantRoleStatement);
        for (String query : new String[] {
            "GRANT ABAC_ADMIN {} TO alice",
            "GRANT ABAC_ADMIN { CREATE_POLICY } FROM alice",
            "REVOKE ABAC_ADMIN { CREATE_POLICY } TO alice",
            "GRANT ABAC_ADMIN { UNKNOWN_OPERATION } TO alice" })
        {
            try { org.apache.cassandra.cql3.QueryProcessor.parseStatement(query); fail(query); }
            catch (org.apache.cassandra.exceptions.SyntaxException expected) { }
        }
    }

}
