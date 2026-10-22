package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.auth.catee.CateeClient;
import org.apache.cassandra.auth.catee.CateeAdminPermission;
import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Opt-in network CQL check against the real hardware service. */
public class CateeCqlTest extends CQLTester
{
    private static Process service;
    private static Path socket;
    private static Path log;

    @BeforeClass
    public static void setupHardwareAndAuthentication() throws Exception
    {
        Assume.assumeTrue("true".equals(System.getProperty("catee.test.hardware")));
        String configuredSocket = CassandraRelevantProperties.CATEE_SOCKET.getString();
        if (configuredSocket == null)
            throw new IllegalArgumentException("Pass cassandra.catee.socket in test.jvm.args before JVM startup");
        socket = Path.of(configuredSocket);
        Path root = Path.of("tee/catee").toAbsolutePath();
        log = socket.resolveSibling("service.log");
        service = new ProcessBuilder(root.resolve("build/catee-service").toString(), socket.toString(),
                                     root.resolve("build/enclave.signed.so").toString())
                  .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        CateeClient client = new CateeClient(socket, 2000);
        boolean ready = false;
        for (int i = 0; i < 100; ++i)
        {
            if (!service.isAlive()) fail("Hardware service failed to start");
            try { client.checkWorktime(840); ready = true; break; }
            catch (IOException e) { Thread.sleep(50); }
        }
        assertTrue("Hardware service did not become ready", ready);
        CassandraRelevantProperties.ORG_APACHE_CASSANDRA_DISABLE_MBEAN_REGISTRATION.setBoolean(true);
        requireAuthentication();
        requireNetwork();
    }

    @AfterClass
    public static void stopHardware() throws Exception
    {
        if (service != null && service.isAlive())
        {
            service.destroy();
            if (!service.waitFor(3, TimeUnit.SECONDS)) { service.destroyForcibly(); service.waitFor(); }
        }
        if (socket != null) Files.deleteIfExists(socket);
        if (log != null) Files.deleteIfExists(log);
    }

    @Test
    public void cqlUsesEnclaveAndDoesNotReuseStalePermissions() throws Throwable
    {
        useSuperUser();
        executeNet("CREATE ROLE catee_admin WITH login=true AND password='test-password'");
        executeNet("CREATE ROLE catee_target WITH login=true AND password='test-password'");
        executeNet("CREATE ROLE catee_parent WITH login=false");
        executeNet("GRANT catee_parent TO catee_admin");
        // An ordinary RBAC administrative grant must not bypass the enclave map.
        QueryProcessor.executeInternal("INSERT INTO system_auth.role_permissions (role, resource, permissions) VALUES (?, ?, ?)",
                                       "catee_admin", RoleResource.root().getName(), java.util.Set.of("CREATE_ATTRIBUTE"));
        createTable("CREATE TABLE %s (id int PRIMARY KEY)");
        CateeClient administration = new CateeClient(socket, 2000);
        administration.grantAdminPrivilege("catee_parent", CateeAdminPermission.mask(Permission.CREATE_ATTRIBUTE));
        useUser("catee_admin", "test-password");
        String[] commands = {
            "CREATE ATTRIBUTE catee_department WITH TYPE text",
            "ALTER ATTRIBUTE catee_department ADD VALUES = ('research')",
            "CREATE HIERARCHY_EDGE catee_department FROM 'all' TO 'research'",
            "GRANT USER ATTRIBUTE catee_department = 'research' TO catee_target",
            "REVOKE USER ATTRIBUTE catee_department FROM catee_target",
            "GRANT RESOURCE ATTRIBUTE catee_department = 'research' TO TABLE " + KEYSPACE + "." + currentTable(),
            "REVOKE RESOURCE ATTRIBUTE catee_department FROM TABLE " + KEYSPACE + "." + currentTable(),
            "CREATE RULE catee_admin_rule FOR SELECT OF USER ATTRIBUTE {'catee_department': 'research'} WITH EFFECT (GRANT)",
            "DROP RULE catee_admin_rule",
            "DROP ATTRIBUTE catee_department"
        };
        Permission[] permissions = {Permission.CREATE_ATTRIBUTE, Permission.ALTER_ATTRIBUTE, Permission.ALTER_ATTRIBUTE,
            Permission.ASSIGN_USER_ATTRIBUTE, Permission.REVOKE_USER_ATTRIBUTE, Permission.ASSIGN_RESOURCE_ATTRIBUTE,
            Permission.REVOKE_RESOURCE_ATTRIBUTE, Permission.CREATE_POLICY, Permission.DROP_POLICY, Permission.DROP_ATTRIBUTE};
        for (int i = 0; i < commands.length; ++i)
        {
            assertInvalidMessageNet("has no CATEE", commands[i]);
            int mask = CateeAdminPermission.mask(permissions[i]);
            administration.grantAdminPrivilege("catee_admin", mask);
            executeNet(commands[i]);
            administration.revokeAdminPrivilege("catee_admin", mask);
        }
        assertInvalidMessageNet("has no CATEE", "CREATE ATTRIBUTE catee_revoked WITH TYPE text");
        administration.grantAdminPrivilege("catee_admin", CateeAdminPermission.mask(Permission.CREATE_ATTRIBUTE));
        useSuperUser();
        executeNet("CREATE ROLE catee_reader WITH login=true AND password='test-password'");
        createTable("CREATE TABLE %s (id int PRIMARY KEY)");
        execute("INSERT INTO %s (id) VALUES (1)");
        QueryProcessor.executeInternal("INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES (?, ?, ?)",
                                       "catee_reader", "catee_subject", "reader");
        QueryProcessor.executeInternal("INSERT INTO system_auth.resource_attribute_values (resource_name, attribute_name, attribute_value) VALUES (?, ?, ?)",
                                       DataResource.table(KEYSPACE, currentTable()).getName(), "catee_table", "target");
        LocalTime now = LocalTime.now();
        boolean within = now.getHour() >= 9 && now.getHour() < 17;
        executeNet("CREATE RULE catee_worktime FOR SELECT OF USER ATTRIBUTE {'catee_subject': 'reader'} " +
                   "AND RESOURCE ATTRIBUTE {'catee_table': 'target'} AND ENVIRONMENT ATTRIBUTE {'worktime': '" + within + "'} WITH EFFECT (GRANT)");
        useUser("catee_reader", "test-password");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of("worktime", Boolean.toString(!within)), "catee_worktime");
        assertInvalidMessageNet("has no SELECT permission", "SELECT id FROM %s");
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of("worktime", Boolean.toString(within)), "catee_worktime");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
        useSuperUser();
        executeNet("GRANT SELECT ON TABLE " + KEYSPACE + "." + currentTable() + " TO catee_reader");
        useUser("catee_reader", "test-password");
        service.destroy();
        assertTrue(service.waitFor(3, TimeUnit.SECONDS));
        assertInvalidMessageNet("Unable to complete CATEE authorization", "SELECT id FROM %s");
        useUser("catee_admin", "test-password");
        assertInvalidMessageNet("Required CATEE administration check failed", "CREATE ATTRIBUTE catee_unavailable WITH TYPE text");
        useSuperUser();
        executeNet("CREATE ATTRIBUTE catee_superuser WITH TYPE text");
        executeNet("DROP ATTRIBUTE catee_superuser");
        useUser("catee_reader", "test-password");
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of(), "catee_worktime");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
    }
}
