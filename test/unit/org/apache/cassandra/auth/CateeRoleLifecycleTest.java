package org.apache.cassandra.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.cql3.statements.AuthenticationStatement;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.service.ClientState;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CateeRoleLifecycleTest extends CQLTester
{
    private IAuthorizer oldAuthorizer;
    private IRoleManager oldRoles;
    private INetworkAuthorizer oldNetwork;
    private ICIDRAuthorizer oldCidr;
    private IRoleManager roles;
    private ClientState state;
    private FakeAuthorizer authorizer;
    private final RoleResource alice = RoleResource.role("lifecycle_alice");
    private final AtomicBoolean exists = new AtomicBoolean(true);
    private final AtomicBoolean login = new AtomicBoolean(true);
    private final List<String> events = new ArrayList<>();
    private String failure;
    private CountDownLatch entered, resume;

    private void step(String name)
    {
        events.add(name);
        if (name.equals(failure)) throw new InvalidRequestException("Injected " + name + " failure");
    }

    private class FakeAuthorizer extends CassandraAuthorizer
    {
        boolean enabled = true, pending, granted = true;
        boolean isCateeEnabled() { return enabled; }
        void beginCateeRoleDeletion(String role) throws IOException
        {
            step("begin");
            pending = true;
            granted = false;
            if (entered != null)
            {
                entered.countDown();
                try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("Test barrier timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        }
        void finishCateeRoleDeletion(String role) { step("finish"); pending = false; }
        void modifyCateeAdminPrivilege(String role, int mask, boolean grant)
        {
            step("provision");
            if (pending) throw new InvalidRequestException("Role pending deletion");
            granted = grant;
        }
        public void revokeAllFrom(RoleResource role) { step("from"); }
        public void revokeAllOn(IResource role) { step("on"); }
        public Set<Permission> grant(AuthenticatedUser performer, Set<Permission> permissions, IResource resource, RoleResource grantee)
        {
            return permissions;
        }
    }

    @Before
    public void configureLifecycle()
    {
        oldAuthorizer = DatabaseDescriptor.getAuthorizer();
        oldRoles = DatabaseDescriptor.getRoleManager();
        oldNetwork = DatabaseDescriptor.getNetworkAuthorizer();
        oldCidr = DatabaseDescriptor.getCIDRAuthorizer();
        // Initialize user caches before replacing their configured dependencies.
        AuthenticatedUser user = mock(AuthenticatedUser.class);
        authorizer = new FakeAuthorizer();
        roles = mock(IRoleManager.class);
        INetworkAuthorizer network = mock(INetworkAuthorizer.class);
        ICIDRAuthorizer cidr = mock(ICIDRAuthorizer.class);
        DatabaseDescriptor.setAuthorizer(authorizer);
        DatabaseDescriptor.setRoleManager(roles);
        DatabaseDescriptor.setNetworkAuthorizer(network);
        DatabaseDescriptor.setCIDRAuthorizer(cidr);
        state = mock(ClientState.class);
        when(user.getName()).thenReturn("lifecycle_operator");
        when(user.isSuper()).thenReturn(true);
        when(state.getUser()).thenReturn(user);
        when(state.isSuper()).thenReturn(true);
        when(roles.isExistingRole(alice)).thenAnswer(invocation -> exists.get());
        when(roles.canLogin(alice)).thenAnswer(invocation -> login.get());
        when(roles.supportedOptions()).thenReturn(EnumSet.allOf(IRoleManager.Option.class));
        doAnswer(invocation -> { exists.set(false); step("role"); return null; }).when(roles).dropRole(org.mockito.Mockito.any(AuthenticatedUser.class), eq(alice));
        doAnswer(invocation -> { step("create"); exists.set(true); return null; }).when(roles).createRole(org.mockito.Mockito.any(AuthenticatedUser.class), eq(alice), org.mockito.Mockito.any(RoleOptions.class));
        doAnswer(invocation -> { step("alter"); login.set(false); return null; }).when(roles).alterRole(org.mockito.Mockito.any(AuthenticatedUser.class), eq(alice), org.mockito.Mockito.any(RoleOptions.class));
        doAnswer(invocation -> { step("network"); return null; }).when(network).drop(alice);
        doAnswer(invocation -> { step("cidr"); return null; }).when(cidr).dropCidrPermissionsForRole(alice);
    }

    @After
    public void restoreConfiguration()
    {
        DatabaseDescriptor.setAuthorizer(oldAuthorizer);
        DatabaseDescriptor.setRoleManager(oldRoles);
        DatabaseDescriptor.setNetworkAuthorizer(oldNetwork);
        DatabaseDescriptor.setCIDRAuthorizer(oldCidr);
    }

    private void executeRole(String query)
    {
        AuthenticationStatement statement = (AuthenticationStatement) QueryProcessor.parseStatement(query);
        // Isolate lifecycle execution from the real role-cache dependency here.
        // Hardware CQL tests exercise the actual authentication/authorization path.
        if (statement instanceof org.apache.cassandra.cql3.statements.DropRoleStatement)
        {
            statement = spy(statement);
            doNothing().when(statement).authorize(state);
        }
        statement.execute(state);
    }

    private void provision()
    {
        CateeAdministration.modifyPrivileges(state, alice, Set.of(Permission.CREATE_POLICY), true);
    }

    @Test
    public void dropAndRetryBothSyntaxesInOrder()
    {
        executeRole("DROP USER lifecycle_alice");
        assertEquals(List.of("begin", "role", "from", "on", "network", "cidr", "finish"), events);
        assertFalse(authorizer.granted);
        assertFalse(authorizer.pending);
        events.clear();
        executeRole("DROP ROLE IF EXISTS lifecycle_alice");
        assertEquals(List.of("begin", "role", "from", "on", "network", "cidr", "finish"), events);
    }

    @Test
    public void everyDeletionFailureCanBeRetriedWithoutRestoringOldGrants()
    {
        for (String stage : List.of("begin", "role", "from", "on", "network", "cidr", "finish"))
        {
            exists.set(true);
            authorizer.pending = false;
            authorizer.granted = true;
            events.clear();
            failure = stage;
            try { executeRole("DROP ROLE lifecycle_alice"); fail(stage); }
            catch (InvalidRequestException expected) { }
            assertEquals(stage, events.get(events.size() - 1));
            if (stage.equals("begin"))
            {
                assertTrue(exists.get());
                assertTrue(authorizer.granted);
            }
            else
            {
                assertTrue(authorizer.pending);
                assertFalse(authorizer.granted);
            }
            failure = null;
            executeRole("DROP ROLE IF EXISTS lifecycle_alice");
            assertFalse(exists.get());
            assertFalse(authorizer.pending);
            assertFalse(authorizer.granted);
        }
    }

    @Test
    public void recreationFinishesInterruptedCleanupAndExistingNoOpPreservesGrants()
    {
        failure = "from";
        try { executeRole("DROP ROLE lifecycle_alice"); fail(); }
        catch (InvalidRequestException expected) { }
        failure = null;
        events.clear();
        executeRole("CREATE USER lifecycle_alice");
        assertEquals(List.of("begin", "role", "from", "on", "network", "cidr", "finish", "create"), events);
        assertTrue(exists.get());
        assertFalse(authorizer.granted);
        authorizer.granted = true;
        events.clear();
        executeRole("CREATE ROLE IF NOT EXISTS lifecycle_alice");
        assertTrue(events.isEmpty());
        assertTrue(authorizer.granted);
    }

    @Test
    public void failedPreparationNeverCreatesRole()
    {
        exists.set(false);
        for (String stage : List.of("begin", "role", "from", "on", "network", "cidr", "finish"))
        {
            failure = stage;
            try { executeRole("CREATE ROLE lifecycle_alice"); fail(stage); }
            catch (InvalidRequestException expected) { }
            assertFalse(exists.get());
            verify(roles, never()).createRole(org.mockito.Mockito.any(AuthenticatedUser.class), org.mockito.Mockito.any(RoleResource.class), org.mockito.Mockito.any(RoleOptions.class));
        }
    }

    @Test
    public void grantWaitsForDropAndRechecksRoleExistence() throws Exception
    {
        entered = new CountDownLatch(1);
        resume = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> drop = workers.submit(() -> executeRole("DROP ROLE lifecycle_alice"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> grant = workers.submit(this::provision);
            try { grant.get(150, TimeUnit.MILLISECONDS); fail("Grant crossed lifecycle lock"); }
            catch (TimeoutException expected) { }
            resume.countDown();
            drop.get(3, TimeUnit.SECONDS);
            try { grant.get(3, TimeUnit.SECONDS); fail("Grant to deleted role succeeded"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof InvalidRequestException); }
            assertFalse(events.contains("provision"));
        }
        finally { resume.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    public void creationWaitsForDeletionToFinish() throws Exception
    {
        entered = new CountDownLatch(1);
        resume = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> drop = workers.submit(() -> executeRole("DROP ROLE lifecycle_alice"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> create = workers.submit(() -> executeRole("CREATE ROLE lifecycle_alice WITH LOGIN=true"));
            try { create.get(150, TimeUnit.MILLISECONDS); fail("Create crossed lifecycle lock"); }
            catch (TimeoutException expected) { }
            resume.countDown();
            drop.get(3, TimeUnit.SECONDS);
            create.get(3, TimeUnit.SECONDS);
            assertTrue(exists.get());
            assertFalse(authorizer.granted);
            assertTrue(events.lastIndexOf("finish") < events.indexOf("create"));
        }
        finally { resume.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    public void disabledLoginRejectsGrantButAllowsRevocation()
    {
        executeRole("ALTER ROLE lifecycle_alice WITH LOGIN=false");
        try { provision(); fail(); }
        catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("LOGIN")); }
        CateeAdministration.modifyPrivileges(state, alice, Set.of(Permission.CREATE_POLICY), false);
        assertFalse(authorizer.granted);
    }

    @Test
    public void grantWaitsForLoginChangeAndRechecksEligibility() throws Exception
    {
        CountDownLatch altering = new CountDownLatch(1), finishAlter = new CountDownLatch(1);
        doAnswer(invocation -> {
            altering.countDown();
            if (!finishAlter.await(5, TimeUnit.SECONDS)) throw new AssertionError("Alter barrier timed out");
            login.set(false);
            return null;
        }).when(roles).alterRole(org.mockito.Mockito.any(AuthenticatedUser.class), eq(alice), org.mockito.Mockito.any(RoleOptions.class));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<?> alter = workers.submit(() -> executeRole("ALTER ROLE lifecycle_alice WITH LOGIN=false"));
            assertTrue(altering.await(3, TimeUnit.SECONDS));
            Future<?> grant = workers.submit(this::provision);
            try { grant.get(150, TimeUnit.MILLISECONDS); fail("Grant crossed ALTER lock"); }
            catch (TimeoutException expected) { }
            finishAlter.countDown();
            alter.get(3, TimeUnit.SECONDS);
            try { grant.get(3, TimeUnit.SECONDS); fail("Grant to non-login role succeeded"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("LOGIN")); }
        }
        finally { finishAlter.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test
    public void invalidExistingCreateAndMissingDropDoNotTouchEnclave()
    {
        try { executeRole("CREATE ROLE lifecycle_alice"); fail(); }
        catch (InvalidRequestException expected) { }
        assertTrue(events.isEmpty());
        exists.set(false);
        try { executeRole("DROP ROLE lifecycle_alice"); fail(); }
        catch (InvalidRequestException expected) { }
        assertTrue(events.isEmpty());
    }

    @Test
    public void nativeModeKeepsOriginalDropAndAbsentNoOp()
    {
        authorizer.enabled = false;
        executeRole("DROP ROLE lifecycle_alice");
        assertEquals(List.of("role", "from", "on", "network", "cidr"), events);
        events.clear();
        executeRole("DROP ROLE IF EXISTS lifecycle_alice");
        assertTrue(events.isEmpty());
    }

    @Test
    public void cleanupErrorsPropagateOnlyForCateeRoleCleanup()
    {
        class BrokenAuthorizer extends CassandraAuthorizer
        {
            boolean enabled = true;
            boolean isCateeEnabled() { return enabled; }
            UntypedResultSet process(String query, ConsistencyLevel consistency)
            {
                throw new InvalidRequestException("Injected cleanup failure");
            }
        }
        BrokenAuthorizer broken = new BrokenAuthorizer();
        try { broken.revokeAllFrom(alice); fail(); } catch (InvalidRequestException expected) { }
        try { broken.revokeAllOn(alice); fail(); } catch (InvalidRequestException expected) { }
        broken.revokeAllOn(DataResource.table("ks", "table")); // Unrelated DDL behavior unchanged.
        broken.enabled = false;
        broken.revokeAllFrom(alice);
        broken.revokeAllOn(alice);
    }
}
