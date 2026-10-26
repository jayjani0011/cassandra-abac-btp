package org.apache.cassandra.auth;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.UnauthorizedException;
import org.junit.Before;
import org.junit.Test;

import org.apache.cassandra.auth.catee.CateeAdminPermission;
import org.apache.cassandra.auth.catee.CateeClient;
import org.apache.cassandra.cql3.QueryProcessor;

import static org.junit.Assert.*;

/** Bounded correctness stress with independent authenticated CQL sessions, not performance data. */
public class CateeConcurrentCqlTest extends CateeCqlTestSupport
{
    private String prefix;
    private int windowStart;

    @Before
    public void chooseStableWindow() throws Throwable
    {
        prefix = "cc" + Long.toUnsignedString(System.nanoTime());
        useSuperUser();
        windowStart = LocalTime.now().getHour() < 12 ? 18 : 1;
        executeNet("SET ENV_ATTRIBUTE worktime = " + windowStart + ",0," + (windowStart + 1) + ",0");
    }

    private void createLogin(String name) throws Throwable
    {
        useSuperUser();
        executeNet("CREATE ROLE " + name + " WITH LOGIN=true AND PASSWORD='test-password'");
    }

    private void subject(String user, String value)
    {
        QueryProcessor.executeInternal("INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES (?, ?, ?)",
                                       user, prefix, value);
    }

    private String allowRule(String name, String subject, boolean worktime)
    {
        return "CREATE RULE " + name + " FOR SELECT OF USER ATTRIBUTE {'" + prefix + "':'" + subject + "'} " +
               "AND RESOURCE ATTRIBUTE {'" + prefix + "':'target'} AND ENVIRONMENT ATTRIBUTE {'worktime':'" + worktime + "'} WITH EFFECT (GRANT)";
    }

    private String prepareReader() throws Throwable
    {
        String reader = prefix + "reader";
        createLogin(reader);
        createTable("CREATE TABLE %s (id int PRIMARY KEY)");
        execute("INSERT INTO %s (id) VALUES (1)");
        subject(reader, "allowed");
        QueryProcessor.executeInternal("INSERT INTO system_auth.resource_attribute_values (resource_name, attribute_name, attribute_value) VALUES (?, ?, ?)",
                                       DataResource.table(KEYSPACE, currentTable()).getName(), prefix, "target");
        executeNet(allowRule(prefix + "allow", "allowed", false));
        return reader;
    }

    @FunctionalInterface
    private interface ClientAction { void run(Session session, int worker); }

    private void wave(List<Session> sessions, ClientAction action) throws Exception
    {
        ExecutorService workers = Executors.newFixedThreadPool(sessions.size());
        CountDownLatch ready = new CountDownLatch(sessions.size()), start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try
        {
            for (int i = 0; i < sessions.size(); ++i)
            {
                final int index = i;
                results.add(workers.submit(() -> {
                    ready.countDown();
                    try { if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Client barrier timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    action.run(sessions.get(index), index);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) result.get(60, TimeUnit.SECONDS);
        }
        finally
        {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void assertQuery(Session session, String query, boolean allowed)
    {
        try
        {
            List<com.datastax.driver.core.Row> rows = session.execute(query).all();
            assertTrue("Unexpected permission grant", allowed);
            assertEquals(1, rows.size());
            assertEquals(1, rows.get(0).getInt("id"));
        }
        catch (UnauthorizedException e) { assertFalse("Unexpected permission rejection", allowed); }
    }

    @Test
    public void concurrentQueriesObservePolicyChanges() throws Throwable
    {
        String reader = prepareReader(), denied = prefix + "denied";
        createLogin(denied);
        subject(denied, "denied");
        executeNet(allowRule(prefix + "deny", "denied", true));
        List<Session> clients = new ArrayList<>();
        try
        {
            for (int i = 0; i < 8; ++i)
            {
                useUser(i % 2 == 0 ? reader : denied, "test-password");
                clients.add(sessionNet().getCluster().connect());
            }
            String query = "SELECT id FROM " + KEYSPACE + "." + currentTable();
            for (int phase = 0; phase < 3; ++phase)
            {
                useSuperUser();
                if (phase == 1) executeNet("DROP RULE " + prefix + "allow");
                if (phase == 2) executeNet(allowRule(prefix + "allow", "allowed", false));
                final boolean allowReaders = phase != 1;
                wave(clients, (session, worker) -> {
                    for (int call = 0; call < 100; ++call)
                        assertQuery(session, query, allowReaders && worker % 2 == 0);
                });
            }
        }
        finally { for (Session client : clients) client.close(); }
    }

    @Test
    public void concurrentAdministrativeGrantsAndRevocationsPreserveBits() throws Throwable
    {
        String administrator = prefix + "admin";
        createLogin(administrator);
        List<Session> clients = new ArrayList<>();
        CateeClient enclave = new CateeClient(socket, 2000);
        try
        {
            useSuperUser();
            for (int i = 0; i < 8; ++i) clients.add(sessionNet().getCluster().connect());
            for (boolean grant : new boolean[] {true, false})
            {
                wave(clients, (session, worker) -> {
                    String operation = worker % 2 == 0 ? "CREATE_ATTRIBUTE" : "DROP_ATTRIBUTE";
                    String query = (grant ? "GRANT" : "REVOKE") + " ABAC_ADMIN { " + operation + " } " +
                                   (grant ? "TO " : "FROM ") + administrator;
                    for (int call = 0; call < 20; ++call) session.execute(query);
                });
                assertEquals(grant, enclave.checkAdminPrivilege(administrator, CateeAdminPermission.mask(Permission.CREATE_ATTRIBUTE)));
                assertEquals(grant, enclave.checkAdminPrivilege(administrator, CateeAdminPermission.mask(Permission.DROP_ATTRIBUTE)));
                useUser(administrator, "test-password");
                if (grant)
                {
                    executeNet("CREATE ATTRIBUTE " + prefix + "attribute WITH TYPE text");
                    executeNet("DROP ATTRIBUTE " + prefix + "attribute");
                }
                else
                    assertInvalidMessageNet("has no CATEE", "CREATE ATTRIBUTE " + prefix + "rejected WITH TYPE text");
            }
        }
        finally { for (Session client : clients) client.close(); }
    }

    @Test
    public void abruptServiceCrashFailsClosedAndRestoresCqlState() throws Throwable
    {
        String reader = prepareReader();
        executeNet("GRANT ABAC_ADMIN { CREATE_ATTRIBUTE } TO " + reader);
        executeNet("GRANT SELECT ON TABLE " + KEYSPACE + "." + currentTable() + " TO " + reader);
        useUser(reader, "test-password");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
        terminateService(true);
        assertInvalidMessageNet("Unable to complete CATEE authorization", "SELECT id FROM %s");
        assertInvalidMessageNet("Required CATEE administration check failed", "CREATE ATTRIBUTE " + prefix + "unavailable WITH TYPE text");
        useSuperUser();
        assertInvalidMessageNet("Required CATEE window update failed", "SET ENV_ATTRIBUTE worktime = 10,0,11,0");
        restartService();
        CateeClient enclave = new CateeClient(socket, 2000);
        assertTrue(enclave.checkWorktime(windowStart * 60));
        assertFalse(enclave.checkWorktime(840));
        useUser(reader, "test-password");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
        executeNet("CREATE ATTRIBUTE " + prefix + "restored WITH TYPE text");
        useSuperUser();
        executeNet("DROP ATTRIBUTE " + prefix + "restored");
    }
}
