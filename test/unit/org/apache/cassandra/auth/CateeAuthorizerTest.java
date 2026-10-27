package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import org.apache.cassandra.auth.catee.CateeClient;
import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.EnvironmentAttributeManager;
import org.apache.cassandra.service.EnvironmentAttributeProvider;
import org.apache.cassandra.utils.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CateeAuthorizerTest extends CQLTester
{
    private static final DataResource RESOURCE = DataResource.table("catee_test", "table1");
    private static final AuthenticatedUser USER = new AuthenticatedUser("catee_reader")
    {
        public boolean isSuper() { return false; }
        public Set<Role> getRoleDetails()
        {
            return Collections.singleton(new Role(getName(), false, true, Collections.emptyMap(), Collections.emptySet()));
        }
    };

    private static class TestAuthorizer extends AuthTestUtils.LocalCassandraAuthorizer
    {
        boolean enabled = true;
        Boolean worktime = true;
        int checks;

        boolean isCateeEnabled() { return enabled; }
        boolean checkCateeEnvironment(Map<String, String> conditions) throws IOException
        {
            checks++;
            if (worktime == null) throw new IOException("Test service unavailable");
            return worktime == Boolean.parseBoolean(conditions.get("worktime"));
        }
    }

    @Before
    public void clearTestTables()
    {
        for (String table : new String[]{"abac_rules", "user_attribute_values", "resource_attribute_values", "role_permissions"})
            QueryProcessor.executeInternal("TRUNCATE system_auth." + table);
    }

    private TestAuthorizer authorizer()
    {
        TestAuthorizer authorizer = new TestAuthorizer();
        authorizer.setup();
        return authorizer;
    }

    private void rule(String name, String effect, Map<String, String> environment)
    {
        QueryProcessor.executeInternal("INSERT INTO system_auth.abac_rules " +
            "(rule_name, effect, permissions, environment_attribute_conditions) VALUES (?, ?, ?, ?)",
            name, effect, Collections.singleton("SELECT"), environment);
    }

    private void grantRbac()
    {
        QueryProcessor.executeInternal("INSERT INTO system_auth.role_permissions (role, resource, permissions) VALUES (?, ?, ?)",
                                       USER.getName(), RESOURCE.getName(), Collections.singleton("SELECT"));
    }

    private static void assertUnauthorized(Runnable action)
    {
        try { action.run(); fail("Expected authorization failure"); }
        catch (UnauthorizedException expected) { }
    }

    private static PermissionsCache testCache(CassandraAuthorizer authorizer)
    {
        return new PermissionsCache(authorizer)
        {
            protected String getObjectName() { return "org.apache.cassandra.auth:type=CateeTestPermissionsCache"; }
        };
    }

    @Test
    public void matchesBothBooleanValuesAndAppliesAbacDeny()
    {
        TestAuthorizer authorizer = authorizer();
        rule("grant", "GRANT", Map.of("worktime", "true"));
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        authorizer.worktime = false;
        assertTrue(authorizer.authorize(USER, RESOURCE).isEmpty());
        rule("outside", "GRANT", Map.of("worktime", "false"));
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        rule("deny", "DENY", Map.of("worktime", "false"));
        assertTrue(authorizer.authorize(USER, RESOURCE).isEmpty());
    }

    @Test
    public void preservesRbacUnionOnHealthyFalseButBlocksRequiredServiceFailure()
    {
        TestAuthorizer authorizer = authorizer();
        grantRbac();
        rule("worktime", "DENY", Map.of("worktime", "true"));
        authorizer.worktime = false;
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        authorizer.worktime = true;
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        authorizer.worktime = null;
        assertUnauthorized(() -> authorizer.authorize(USER, RESOURCE));
    }

    @Test
    public void doesNotContactServiceWithoutAnApplicableWorktimeCondition()
    {
        TestAuthorizer authorizer = authorizer();
        authorizer.worktime = null;
        rule("plain", "GRANT", Collections.emptyMap());
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        rule("unrelated", "DENY", Map.of("worktime", "true"));
        QueryProcessor.executeInternal("UPDATE system_auth.abac_rules SET user_attribute_conditions = ? WHERE rule_name = ?",
                                       Map.of("department", "other"), "unrelated");
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        assertEquals(0, authorizer.checks);
    }

    @Test
    public void checksExpectedValueForEachRule()
    {
        TestAuthorizer authorizer = authorizer();
        rule("one", "GRANT", Map.of("worktime", "true"));
        rule("two", "GRANT", Map.of("worktime", "false"));
        authorizer.authorize(USER, RESOURCE);
        assertEquals(2, authorizer.checks);
    }

    @Test
    public void invalidConditionFailsClosed()
    {
        grantRbac();
        rule("invalid", "DENY", Map.of("worktime", "TRUE"));
        assertUnauthorized(() -> authorizer().authorize(USER, RESOURCE));
    }

    @Test
    public void disabledModeKeepsExistingProviderPath()
    {
        EnvironmentAttributeManager.getInstance().registerProvider(new EnvironmentAttributeProvider()
        {
            public String getAttributeName() { return "worktime"; }
            public String getValue() { return "true"; }
        });
        TestAuthorizer authorizer = authorizer();
        rule("grant", "GRANT", Map.of("worktime", "true"));
        authorizer.worktime = false;
        assertTrue(authorizer.authorize(USER, RESOURCE).isEmpty());
        authorizer.enabled = false;
        assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
        assertEquals(1, authorizer.checks);
    }

    @Test
    public void cachedGrantCannotSkipWorktimeOrServiceFailure()
    {
        TestAuthorizer authorizer = authorizer();
        rule("grant", "GRANT", Map.of("worktime", "true"));
        PermissionsCache cache = testCache(authorizer);
        try
        {
            cache.cache.put(Pair.create(USER, RESOURCE), Collections.singleton(Permission.SELECT));
            assertEquals(Collections.singleton(Permission.SELECT), cache.getPermissions(USER, RESOURCE));
            authorizer.worktime = false;
            assertTrue(cache.getPermissions(USER, RESOURCE).isEmpty());
            authorizer.worktime = null;
            assertUnauthorized(() -> cache.getPermissions(USER, RESOURCE));
            assertEquals(3, authorizer.checks);
        }
        finally { cache.unregisterMBean(); cache.shutdown(); }
    }

    @Test
    public void hardwareServiceDrivesPolicyAndFailureBlocksRbac() throws Exception
    {
        org.junit.Assume.assumeTrue("Enable with -Dcatee.test.hardware=true",
                                   "true".equals(System.getProperty("catee.test.hardware")));
        Path directory = Files.createTempDirectory("catee-auth-");
        Path socket = directory.resolve("service.sock");
        Path root = Path.of("tee/catee").toAbsolutePath();
        Path log = directory.resolve("service.log");
        Process service = null;
        PermissionsCache cache = null;
        String previousSocket = CassandraRelevantProperties.CATEE_SOCKET.getString();
        try
        {
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
            CassandraRelevantProperties.CATEE_SOCKET.setString(socket.toString());
            CassandraAuthorizer authorizer = new AuthTestUtils.LocalCassandraAuthorizer();
            CassandraRelevantProperties.CATEE_SOCKET.clearValue();
            authorizer.setup();
            LocalTime now = LocalTime.now();
            String actual = Boolean.toString(now.getHour() >= 9 && now.getHour() < 17);
            rule("hardware", "GRANT", Map.of("worktime", actual));
            assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
            rule("hardware", "GRANT", Map.of("worktime", Boolean.toString(!Boolean.parseBoolean(actual))));
            assertTrue(authorizer.authorize(USER, RESOURCE).isEmpty());
            // The expected day and time are compared together inside SGX.
            String workingDay = Boolean.toString(java.time.LocalDate.now().getDayOfWeek().getValue() <= 5);
            rule("hardware", "GRANT", Map.of("workday", workingDay, "worktime", actual));
            assertEquals(Collections.singleton(Permission.SELECT), authorizer.authorize(USER, RESOURCE));
            rule("hardware", "GRANT", Map.of("workday", Boolean.toString(!Boolean.parseBoolean(workingDay))));
            assertTrue(authorizer.authorize(USER, RESOURCE).isEmpty());
            // Unsupported subnet checks must not fall back to ordinary providers.
            rule("hardware", "GRANT", Map.of("subnet", "campus"));
            assertUnauthorized(() -> authorizer.authorize(USER, RESOURCE));
            rule("hardware", "GRANT", Map.of("worktime", actual));
            grantRbac();
            cache = testCache(authorizer);
            assertEquals(Collections.singleton(Permission.SELECT), cache.getPermissions(USER, RESOURCE));
            service.destroy();
            assertTrue(service.waitFor(3, TimeUnit.SECONDS));
            final PermissionsCache activeCache = cache;
            assertUnauthorized(() -> activeCache.getPermissions(USER, RESOURCE));
            // Policies without worktime do not require a running SGX service.
            rule("hardware", "GRANT", Collections.emptyMap());
            assertEquals(Collections.singleton(Permission.SELECT), cache.getPermissions(USER, RESOURCE));
        }
        finally
        {
            if (service != null && service.isAlive()) { service.destroyForcibly(); service.waitFor(); }
            if (cache != null) { cache.unregisterMBean(); cache.shutdown(); }
            if (previousSocket == null) CassandraRelevantProperties.CATEE_SOCKET.clearValue();
            else CassandraRelevantProperties.CATEE_SOCKET.setString(previousSocket);
            Files.deleteIfExists(socket);
            Files.deleteIfExists(log);
            Files.delete(directory);
        }
    }
}
