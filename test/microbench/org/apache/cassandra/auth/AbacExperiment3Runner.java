/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cassandra.auth;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.slf4j.LoggerFactory;

/**
 * Direct, reproducible runner for Chapter 6, Experiment 3.
 *
 * <p>The report-literal configuration creates {@code v0 -> ... -> vD}, assigns
 * {@code vD} to the user, and requires {@code v0} in the rule. Under the checked-out
 * implementation, {@code check(actual, required)} therefore tests whether {@code v0}
 * is a descendant of {@code vD}; it is not. This runner deliberately preserves that
 * configuration and records it as a hierarchy miss.</p>
 */
public final class AbacExperiment3Runner
{
    private static final int WARMUP_CALLS = 20;
    private static final int MEASURED_CALLS = 50;
    private static final String USER = "abac_exp3_user";
    private static final IResource RESOURCE = DataResource.table("abac_experiment_3", "target");

    private enum Configuration
    {
        REPORT_LITERAL("report-literal", false),
        IMPLEMENTATION_MATCH("implementation-match", true);

        final String name;
        final boolean expectedRuleMatch;

        Configuration(String name, boolean expectedRuleMatch)
        {
            this.name = name;
            this.expectedRuleMatch = expectedRuleMatch;
        }

        static Configuration parse(String value)
        {
            for (Configuration configuration : values())
                if (configuration.name.equals(value))
                    return configuration;
            throw new IllegalArgumentException("Unknown configuration: " + value);
        }
    }

    private AbacExperiment3Runner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Usage: AbacExperiment3Runner <output-directory> [report-literal|implementation-match]");

        Path outputDirectory = Path.of(args[0]);
        Configuration configuration = args.length == 2 ? Configuration.parse(args[1]) : Configuration.REPORT_LITERAL;
        Files.createDirectories(outputDirectory);
        CQLTester.setUpClass();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        ((Logger) LoggerFactory.getLogger(CassandraAuthorizer.class)).setLevel(Level.WARN);
        ((Logger) LoggerFactory.getLogger(AttributeHierarchyManager.class)).setLevel(Level.WARN);

        try (BufferedWriter raw = Files.newBufferedWriter(outputDirectory.resolve("experiment3_raw_samples.csv"));
             BufferedWriter summary = Files.newBufferedWriter(outputDirectory.resolve("experiment3_summary.csv")))
        {
            raw.write("depth,sample,elapsed_ns\n");
            summary.write("depth,warmup_calls,measured_calls,mean_us,stddev_us,min_us,max_us\n");

            CassandraAuthorizer authorizer = new CassandraAuthorizer();
            // Initialize before adding edges. Otherwise initialize() could load a
            // persisted cache and discard just-added in-memory edges.
            authorizer.setup();
            AuthenticatedUser user = new AuthenticatedUser(USER);
            for (int depth = 1; depth <= 5; depth++)
                runPoint(depth, configuration, authorizer, user, raw, summary);
        }
        finally
        {
            CQLTester.tearDownClass();
        }

        Files.writeString(outputDirectory.resolve("experiment3_metadata.txt"), metadata(configuration));
        // Cassandra's embedded test services retain non-daemon threads.
        System.exit(0);
    }

    private static void runPoint(int depth,
                                 Configuration configuration,
                                 CassandraAuthorizer authorizer,
                                 AuthenticatedUser user,
                                 BufferedWriter raw,
                                 BufferedWriter summary) throws Exception
    {
        String attributeName = "hierarchy_attr_depth_" + depth;
        resetData();
        insertData(depth, attributeName, configuration);

        Set<Permission> initialPermissions = authorizer.getAbacPermissions(user, RESOURCE);
        boolean ruleMatched = initialPermissions.contains(Permission.SELECT);
        if (ruleMatched != configuration.expectedRuleMatch)
            throw new AssertionError("Unexpected hierarchy match result at depth " + depth + " for " + configuration.name);

        for (int i = 0; i < WARMUP_CALLS; i++)
            authorizer.getAbacPermissions(user, RESOURCE);

        long sum = 0;
        double sumOfSquares = 0;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int sample = 0; sample < MEASURED_CALLS; sample++)
        {
            long start = System.nanoTime();
            authorizer.getAbacPermissions(user, RESOURCE);
            long elapsed = System.nanoTime() - start;
            raw.write(String.format(Locale.ROOT, "%d,%d,%d%n", depth, sample, elapsed));
            sum += elapsed;
            sumOfSquares += (double) elapsed * elapsed;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
        }

        double mean = (double) sum / MEASURED_CALLS;
        double variance = (sumOfSquares / MEASURED_CALLS) - (mean * mean);
        double stddev = Math.sqrt(Math.max(variance, 0));
        summary.write(String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n",
                                    depth, WARMUP_CALLS, MEASURED_CALLS,
                                    mean / 1000.0, stddev / 1000.0,
                                    min / 1000.0, max / 1000.0));
        raw.flush();
        summary.flush();
    }

    private static void insertData(int depth, String attributeName, Configuration configuration)
    {
        String userValue = configuration == Configuration.REPORT_LITERAL ? "v" + depth : "v0";
        String ruleValue = configuration == Configuration.REPORT_LITERAL ? "v0" : "v" + depth;
        QueryProcessor.executeInternal(String.format(
        "INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES ('%s', '%s', '%s')",
        USER, attributeName, userValue));

        for (int i = 0; i < depth; i++)
        {
            QueryProcessor.executeInternal(String.format(
            "INSERT INTO system_auth.attribute_hierarchy_edges (attribute_name, parent, child) VALUES ('%s', 'v%d', 'v%d')",
            attributeName, i, i + 1));
            // This mirrors the production statement's local cache update.
            AttributeHierarchyManager.instance.addEdge(attributeName, "v" + i, "v" + (i + 1));
        }

        QueryProcessor.executeInternal(String.format(
        "INSERT INTO system_auth.abac_rules (rule_name, permissions, user_attribute_conditions, resource_attribute_conditions, environment_attribute_conditions, effect) VALUES ('exp3_rule', {'SELECT'}, {'%s':'%s'}, {}, {}, 'GRANT')",
        attributeName, ruleValue));
    }

    private static void resetData()
    {
        QueryProcessor.executeInternal("TRUNCATE system_auth.user_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.resource_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.abac_rules");
        QueryProcessor.executeInternal("TRUNCATE system_auth.attribute_hierarchy_edges");
    }

    private static String metadata(Configuration configuration)
    {
        return String.format(Locale.ROOT,
                             "experiment=Chapter 6 Experiment 3%n" +
                             "method=getAbacPermissions(AuthenticatedUser, IResource)%n" +
                             "configuration=%s%n" +
                             "hierarchy=v0 -> ... -> vD%n" +
                             "user_assignment=%s%n" +
                             "rule_requirement=%s%n" +
                             "expected_rule_match=%s under current check(actual, required) semantics%n" +
                             "hierarchy_cache=updated after every direct edge insert%n" +
                             "warmup_calls_per_point=%d%n" +
                             "measured_calls_per_point=%d%n" +
                             "threads=1%n" +
                             "logging=WARN (excluded from timing)%n" +
                             "java.version=%s%n" +
                             "java.vm.name=%s%n" +
                             "os.name=%s%n" +
                             "os.version=%s%n" +
                             "os.arch=%s%n" +
                             "available_processors=%d%n",
                             configuration.name,
                             configuration == Configuration.REPORT_LITERAL ? "vD" : "v0",
                             configuration == Configuration.REPORT_LITERAL ? "v0" : "vD",
                             configuration.expectedRuleMatch,
                             WARMUP_CALLS, MEASURED_CALLS,
                             System.getProperty("java.version"), System.getProperty("java.vm.name"),
                             System.getProperty("os.name"), System.getProperty("os.version"),
                             System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
    }
}
