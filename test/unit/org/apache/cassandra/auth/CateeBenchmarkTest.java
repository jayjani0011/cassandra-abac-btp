package org.apache.cassandra.auth;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.Assume;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.service.EnvironmentAttributeManager;
import org.apache.cassandra.service.EnvironmentAttributeProvider;

import static org.junit.Assert.*;

/** Opt-in experiment harness using Cassandra's isolated test database, not a live node. */
public class CateeBenchmarkTest extends CQLTester
{
    private static final AuthenticatedUser USER = new AuthenticatedUser("catee_benchmark_user");
    private static final IResource RESOURCE = DataResource.table("catee_benchmark", "table1");

    private static boolean withinWorktime()
    {
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        return minute >= 540 && minute <= 1020;
    }

    private static void loadPoint(String scenario)
    {
        for (String table : new String[]{"abac_rules", "user_attribute_values", "resource_attribute_values"})
            QueryProcessor.executeInternal("TRUNCATE system_auth." + table);
        QueryProcessor.executeInternal("INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES (?, ?, ?)",
                                       USER.getName(), "department", "research");
        QueryProcessor.executeInternal("INSERT INTO system_auth.resource_attribute_values (resource_name, attribute_name, attribute_value) VALUES (?, ?, ?)",
                                       RESOURCE.getName(), "classification", "public");
        Map<String, String> environment = scenario.equals("control") ? Collections.emptyMap()
                                                                  : Map.of("worktime", scenario.equals("worktime_true") ? "true" : "false");
        QueryProcessor.executeInternal("INSERT INTO system_auth.abac_rules (rule_name, effect, permissions, user_attribute_conditions, " +
                                       "resource_attribute_conditions, environment_attribute_conditions) VALUES (?, ?, ?, ?, ?, ?)",
                                       "catee_benchmark_rule", "GRANT", Set.of("SELECT"), Map.of("department", "research"),
                                       Map.of("classification", "public"), environment);
    }

    @Test
    public void measureEquivalentAbacAndCateePolicies() throws Exception
    {
        Assume.assumeTrue("Run through tee/catee/test/benchmark_abac.py", Boolean.getBoolean("catee.benchmark"));
        boolean smoke = Boolean.getBoolean("catee.benchmark.smoke");
        int warmups = smoke ? 1 : 10, batches = smoke ? 2 : 100, calls = smoke ? 3 : 100;
        String mode = System.getProperty("catee.benchmark.mode");
        assertTrue("Expected native or sgx mode", "native".equals(mode) || "sgx".equals(mode));
        assertEquals("Mode must agree with the startup socket property", "sgx".equals(mode),
                     CassandraRelevantProperties.CATEE_SOCKET.getString() != null);
        int repeat = Integer.parseInt(System.getProperty("catee.benchmark.repeat"));
        long seed = Long.parseLong(System.getProperty("catee.benchmark.seed"));
        Path output = Path.of(System.getProperty("catee.benchmark.output"));
        EnvironmentAttributeManager.getInstance().registerProvider(new EnvironmentAttributeProvider()
        {
            public String getAttributeName() { return "worktime"; }
            public String getValue() { return Boolean.toString(withinWorktime()); }
        });
        CassandraAuthorizer authorizer = new CassandraAuthorizer();
        authorizer.setup();
        List<String> scenarios = new ArrayList<>(Arrays.asList("control", "worktime_true", "worktime_false"));
        Collections.shuffle(scenarios, new Random(seed + repeat));
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger auth = (Logger) LoggerFactory.getLogger(CassandraAuthorizer.class);
        Level rootLevel = root.getLevel(), authLevel = auth.getLevel();
        root.setLevel(Level.WARN);
        auth.setLevel(Level.WARN);
        boolean initialWorktime = withinWorktime();
        try (BufferedWriter raw = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW))
        {
            raw.write("repeat,mode,scenario,batch,sample,elapsed_ns,select_granted\n");
            for (String scenario : scenarios)
            {
                loadPoint(scenario); // Exactly once per point, before all warmup and retained calls.
                for (int batch = -warmups; batch < batches; ++batch)
                {
                    long[] elapsed = new long[calls];
                    boolean[] granted = new boolean[calls];
                    for (int sample = 0; sample < calls; ++sample)
                    {
                        assertEquals("Working-hours boundary crossed; discard this run", initialWorktime, withinWorktime());
                        long start = System.nanoTime();
                        Set<Permission> permissions = authorizer.getAbacPermissions(USER, RESOURCE);
                        elapsed[sample] = System.nanoTime() - start;
                        assertEquals("Working-hours boundary crossed; discard this run", initialWorktime, withinWorktime());
                        boolean expected = scenario.equals("control") || scenario.equals("worktime_true") == initialWorktime;
                        assertEquals("Unexpected authorization result", expected ? Set.of(Permission.SELECT) : Set.of(), permissions);
                        granted[sample] = permissions.contains(Permission.SELECT);
                    }
                    if (batch >= 0)
                        for (int sample = 0; sample < calls; ++sample)
                            raw.write(repeat + "," + mode + "," + scenario + "," + (batch + 1) + "," + (sample + 1)
                                      + "," + elapsed[sample] + "," + granted[sample] + "\n");
                }
            }
        }
        finally
        {
            root.setLevel(rootLevel);
            auth.setLevel(authLevel);
        }
    }
}
