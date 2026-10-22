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
            Permission.REVOKE_RESOURCE_ATTRIBUTE, Permission.CREATE_POLICY, Permission.DROP_POLICY};
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
}
