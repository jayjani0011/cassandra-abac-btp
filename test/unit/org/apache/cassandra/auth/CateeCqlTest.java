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
import org.apache.cassandra.config.DatabaseDescriptor;
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
                                     root.resolve("build/enclave.signed.so").toString(), socket.toString() + ".sealed", "--init")
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
        if (socket != null) Files.deleteIfExists(Path.of(socket.toString() + ".sealed"));
        if (socket != null) Files.deleteIfExists(Path.of(socket.toString() + ".sealed.lock"));
        if (log != null) Files.deleteIfExists(log);
    }

    private void checkRoleLifecycle() throws Throwable
    {
        CateeClient administration = new CateeClient(socket, 2000);
        int privilege = CateeAdminPermission.mask(Permission.CREATE_ATTRIBUTE);
        executeNet("CREATE USER lifecycle_user WITH PASSWORD 'test-password'");
        executeNet("GRANT ABAC_ADMIN { CREATE_ATTRIBUTE } TO lifecycle_user");
        useUser("lifecycle_user", "test-password");
        com.datastax.driver.core.Session existingSession = sessionNet();
        existingSession.execute("CREATE ATTRIBUTE lifecycle_before WITH TYPE text");
        useSuperUser();
        executeNet("DROP USER lifecycle_user");
        org.junit.Assert.assertFalse(administration.checkAdminPrivilege("lifecycle_user", privilege));
        try { existingSession.execute("CREATE ATTRIBUTE lifecycle_dropped WITH TYPE text"); fail("Dropped role retained privilege"); }
        catch (com.datastax.driver.core.exceptions.UnauthorizedException expected) { }
        executeNet("CREATE ROLE lifecycle_user WITH LOGIN=true AND PASSWORD='test-password'");
        org.junit.Assert.assertFalse(administration.checkAdminPrivilege("lifecycle_user", privilege));
        try { existingSession.execute("CREATE ATTRIBUTE lifecycle_recreated WITH TYPE text"); fail("Recreated role inherited old privilege"); }
        catch (com.datastax.driver.core.exceptions.UnauthorizedException expected) { }
        executeNet("GRANT ABAC_ADMIN { CREATE_ATTRIBUTE } TO lifecycle_user");
        executeNet("CREATE ROLE IF NOT EXISTS lifecycle_user");
        assertTrue(administration.checkAdminPrivilege("lifecycle_user", privilege));
        existingSession.execute("CREATE ATTRIBUTE lifecycle_new_grant WITH TYPE text");
        executeNet("DROP ROLE lifecycle_user");
        executeNet("DROP USER IF EXISTS lifecycle_user");

        // Emulate restart after BEGIN: the marker must survive service restoration.
        executeNet("CREATE ROLE lifecycle_interrupted WITH LOGIN=true AND PASSWORD='test-password'");
        executeNet("GRANT ABAC_ADMIN { CREATE_ATTRIBUTE } TO lifecycle_interrupted");
        administration.beginRoleDeletion("lifecycle_interrupted");
        restartService();
        assertInvalidMessageNet("Required CATEE grant update failed",
                                "GRANT ABAC_ADMIN { CREATE_ATTRIBUTE } TO lifecycle_interrupted");
        executeNet("DROP ROLE IF EXISTS lifecycle_interrupted");
        executeNet("CREATE USER lifecycle_interrupted WITH PASSWORD 'test-password'");
        org.junit.Assert.assertFalse(administration.checkAdminPrivilege("lifecycle_interrupted", privilege));
        executeNet("DROP ROLE lifecycle_interrupted");

        // Recover after the Cassandra role row disappeared but FINISH was not done.
        administration.beginRoleDeletion("lifecycle_absent");
        restartService();
        executeNet("DROP ROLE IF EXISTS lifecycle_absent");
        administration.grantAdminPrivilege("lifecycle_absent", privilege);
        assertTrue(administration.checkAdminPrivilege("lifecycle_absent", privilege));
        // Creating an absent name also removes stale state, without restoring grants.
        executeNet("CREATE USER lifecycle_absent WITH PASSWORD 'test-password'");
        org.junit.Assert.assertFalse(administration.checkAdminPrivilege("lifecycle_absent", privilege));
        executeNet("DROP USER lifecycle_absent");
        executeNet("DROP ATTRIBUTE lifecycle_before");
        executeNet("DROP ATTRIBUTE lifecycle_new_grant");
    }

    private static void restartService() throws Exception
    {
        service.destroy();
        assertTrue(service.waitFor(3, TimeUnit.SECONDS));
        Path root = Path.of("tee/catee").toAbsolutePath();
        service = new ProcessBuilder(root.resolve("build/catee-service").toString(), socket.toString(),
                                     root.resolve("build/enclave.signed.so").toString(), socket.toString() + ".sealed")
                  .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        CateeClient client = new CateeClient(socket, 2000);
        for (int i = 0; i < 100; ++i)
        {
            if (!service.isAlive()) fail("Hardware service failed to restart");
            try { client.checkWorktime(840); return; }
            catch (IOException e) { Thread.sleep(50); }
        }
        fail("Hardware service did not restart");
    }

    private void checkWindowConfigurationThroughCql(boolean originalExpected) throws Throwable
    {
        useSuperUser();
        CateeClient client = new CateeClient(socket, 2000);
        executeNet("SET ENV_ATTRIBUTE worktime = 10, 0, 11, 0");
        assertTrue(client.checkWorktime(600));
        assertTrue(client.checkWorktime(659));
        org.junit.Assert.assertFalse(client.checkWorktime(599));
        org.junit.Assert.assertFalse(client.checkWorktime(660));
        for (String values : new String[] {"-1,0,17,0", "24,0,17,0", "9,60,17,0", "9,0,17,60",
                                           "9,0,9,0", "17,0,9,0", "2147483648,0,17,0"})
        {
            assertInvalidMessageNet(values.contains("2147483648") ? "valid integers" :
                                    values.equals("9,0,9,0") || values.equals("17,0,9,0") ? "earlier than end" : "hour/minute",
                                    "SET ENV_ATTRIBUTE worktime = " + values);
            assertTrue(client.checkWorktime(630));
            org.junit.Assert.assertFalse(client.checkWorktime(840));
        }
        assertInvalidMessageNet("Unsupported CATEE", "SET ENV_ATTRIBUTE workday = 9,0,17,0");
        useUser("catee_reader", "test-password");
        assertInvalidMessageNet("Only superusers", "SET ENV_ATTRIBUTE worktime = 9,0,17,0");
        assertTrue(client.checkWorktime(630));
        org.junit.Assert.assertFalse(client.checkWorktime(840));

        useSuperUser();
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of("worktime", "false"), "catee_worktime");
        // Choose a window far from the current clock so this rule grants access.
        executeNet(LocalTime.now().getHour() < 12 ? "SET ENV_ATTRIBUTE worktime = 18,0,19,0"
                                                : "SET ENV_ATTRIBUTE worktime = 1,0,2,0");
        useUser("catee_reader", "test-password");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
        useSuperUser();
        executeNet("SET ENV_ATTRIBUTE worktime = 0,0,23,59");
        useUser("catee_reader", "test-password");
        // The existing exclusive-end convention excludes the final minute of the day.
        LocalTime now = LocalTime.now();
        if (now.getHour() * 60 + now.getMinute() < 1439)
            assertInvalidMessageNet("has no SELECT permission", "SELECT id FROM %s");
        else
            assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
        useSuperUser();
        executeNet("SET ENV_ATTRIBUTE worktime = 9,0,17,0");
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of("worktime", Boolean.toString(originalExpected)), "catee_worktime");
        useUser("catee_reader", "test-password");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
    }

    @Test
    public void cqlUsesEnclaveAndDoesNotReuseStalePermissions() throws Throwable
    {
        useSuperUser();
        checkRoleLifecycle();
        executeNet("CREATE ROLE catee_admin WITH login=true AND password='test-password'");
        executeNet("CREATE ROLE catee_target WITH login=true AND password='test-password'");
        executeNet("CREATE ROLE catee_parent WITH login=false");
        executeNet("GRANT catee_parent TO catee_admin");
        executeNet("GRANT ABAC_ADMIN { CREATE_POLICY, DROP_POLICY } TO catee_admin");
        CateeClient provisioned = new CateeClient(socket, 2000);
        assertTrue(provisioned.checkAdminPrivilege("catee_admin", CateeAdminPermission.mask(Permission.CREATE_POLICY)));
        assertTrue(provisioned.checkAdminPrivilege("catee_admin", CateeAdminPermission.mask(Permission.DROP_POLICY)));
        executeNet("REVOKE ABAC_ADMIN { CREATE_POLICY, DROP_POLICY } FROM catee_admin");
        org.junit.Assert.assertFalse(provisioned.checkAdminPrivilege("catee_admin", CateeAdminPermission.mask(Permission.CREATE_POLICY)));
        assertInvalidMessageNet("LOGIN = true", "GRANT ABAC_ADMIN { CREATE_POLICY } TO catee_parent");
        assertInvalidMessageNet("does not exist", "GRANT ABAC_ADMIN { CREATE_POLICY } TO catee_missing");
        assertInvalidMessageNet("Unsupported CATEE", "GRANT ABAC_ADMIN { SELECT } TO catee_admin");
        assertInvalidMessageNet("Unsupported CATEE", "GRANT ABAC_ADMIN { ALTER_POLICY } TO catee_admin");
        // The new token must remain usable as an ordinary unquoted role name.
        executeNet("CREATE ROLE abac_admin WITH login=false");
        executeNet("GRANT abac_admin TO catee_admin");
        executeNet("REVOKE abac_admin FROM catee_admin");
        executeNet("DROP ROLE abac_admin");
        executeNet("CREATE ROLE \"Catee Quoted\" WITH login=true AND password='test-password'");
        executeNet("GRANT ABAC_ADMIN { CREATE_POLICY } TO \"Catee Quoted\"");
        executeNet("ALTER ROLE \"Catee Quoted\" WITH login=false");
        executeNet("REVOKE ABAC_ADMIN { CREATE_POLICY } FROM \"Catee Quoted\"");
        org.junit.Assert.assertFalse(provisioned.checkAdminPrivilege("Catee Quoted", CateeAdminPermission.mask(Permission.CREATE_POLICY)));
        useUser("catee_admin", "test-password");
        assertInvalidMessageNet("Only superusers", "GRANT ABAC_ADMIN { CREATE_POLICY } TO catee_admin");
        assertInvalidMessageNet("Only superusers", "REVOKE ABAC_ADMIN { CREATE_POLICY } FROM catee_admin");
        useSuperUser();
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
            useSuperUser();
            executeNet("GRANT ABAC_ADMIN { " + permissions[i] + " } TO catee_admin");
            useUser("catee_admin", "test-password");
            executeNet(commands[i]);
            useSuperUser();
            executeNet("REVOKE ABAC_ADMIN { " + permissions[i] + " } FROM catee_admin");
            useUser("catee_admin", "test-password");
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
        checkWindowConfigurationThroughCql(within);
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
        useSuperUser();
        assertInvalidMessageNet("Required CATEE window update failed", "SET ENV_ATTRIBUTE worktime = 10,0,11,0");
        useUser("catee_admin", "test-password");
        assertInvalidMessageNet("Required CATEE administration check failed", "CREATE ATTRIBUTE catee_unavailable WITH TYPE text");
        useSuperUser();
        assertInvalidMessageNet("Required CATEE grant update failed", "GRANT ABAC_ADMIN { CREATE_POLICY } TO catee_admin");
        assertInvalidMessageNet("Cannot begin CATEE role deletion", "DROP ROLE catee_target");
        assertTrue(DatabaseDescriptor.getRoleManager().isExistingRole(RoleResource.role("catee_target")));
        assertInvalidMessageNet("Cannot begin CATEE role deletion", "CREATE ROLE catee_service_down WITH LOGIN=true");
        org.junit.Assert.assertFalse(DatabaseDescriptor.getRoleManager().isExistingRole(RoleResource.role("catee_service_down")));
        executeNet("CREATE ATTRIBUTE catee_superuser WITH TYPE text");
        executeNet("DROP ATTRIBUTE catee_superuser");
        useUser("catee_reader", "test-password");
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of(), "catee_worktime");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
    }
}
