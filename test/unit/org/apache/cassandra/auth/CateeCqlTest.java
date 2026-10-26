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
        useUser("catee_reader", "test-password");
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET environment_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of(), "catee_worktime");
        assertRowsNet(executeNet("SELECT id FROM %s"), row(1));
    }
}
