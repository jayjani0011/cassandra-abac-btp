/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cassandra.auth;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.slf4j.LoggerFactory;

/** Direct, reproducible runner for Chapter 6, Experiment 1. */
public final class AbacExperiment1Runner
{
    private static final int[] RULE_COUNTS = { 1, 10, 50, 100, 200, 400, 600, 800, 1000 };
    private static final int[] CONDITIONS_PER_RULE = { 1, 2, 4, 8 };
    private static final int WARMUP_CALLS = 20;
    private static final int MEASURED_CALLS = 50;
    private static final String USER = "abac_exp1_user";
    private static final String RESOURCE_NAME = "data/abac_experiment_1/target";
    private static final IResource RESOURCE = DataResource.table("abac_experiment_1", "target");

    private AbacExperiment1Runner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
            throw new IllegalArgumentException("Usage: AbacExperiment1Runner <output-directory>");

        Path outputDirectory = Path.of(args[0]);
        Files.createDirectories(outputDirectory);
        Path rawOutput = outputDirectory.resolve("experiment1_raw_samples.csv");
        Path summaryOutput = outputDirectory.resolve("experiment1_summary.csv");
        Path metadataOutput = outputDirectory.resolve("experiment1_metadata.txt");

        CQLTester.setUpClass();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);

        try (BufferedWriter raw = Files.newBufferedWriter(rawOutput);
             BufferedWriter summary = Files.newBufferedWriter(summaryOutput))
        {
            raw.write("rule_count,conditions_per_rule,sample,elapsed_ns\n");
            summary.write("rule_count,conditions_per_rule,warmup_calls,measured_calls,mean_us,stddev_us,min_us,max_us\n");

            for (int conditionsPerRule : CONDITIONS_PER_RULE)
            {
                for (int ruleCount : RULE_COUNTS)
                    runPoint(ruleCount, conditionsPerRule, raw, summary);
            }
        }
        finally
        {
            CQLTester.tearDownClass();
        }

        Files.writeString(metadataOutput, metadata());
        // Cassandra's embedded test services retain non-daemon threads.  This
        // runner is a one-shot process, so exit explicitly after files close.
        System.exit(0);
    }

    private static void runPoint(int ruleCount, int conditionsPerRule, BufferedWriter raw, BufferedWriter summary) throws IOException
    {
        resetData();
        insertAttributes(conditionsPerRule);
        insertRules(ruleCount, conditionsPerRule);

        CassandraAuthorizer authorizer = new CassandraAuthorizer();
        authorizer.setup();
        AuthenticatedUser user = new AuthenticatedUser(USER);

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
            raw.write(String.format(Locale.ROOT, "%d,%d,%d,%d%n", ruleCount, conditionsPerRule, sample, elapsed));
            sum += elapsed;
            sumOfSquares += (double) elapsed * elapsed;
            min = Math.min(min, elapsed);
            max = Math.max(max, elapsed);
        }
        raw.flush();

        double mean = (double) sum / MEASURED_CALLS;
        double variance = ((double) sumOfSquares / MEASURED_CALLS) - (mean * mean);
        double stddev = Math.sqrt(Math.max(variance, 0));
        summary.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n",
                                    ruleCount, conditionsPerRule, WARMUP_CALLS, MEASURED_CALLS,
                                    mean / 1000.0, stddev / 1000.0, min / 1000.0, max / 1000.0));
        summary.flush();
    }

    private static void resetData()
    {
        QueryProcessor.executeInternal("TRUNCATE system_auth.user_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.resource_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.abac_rules");
    }

    private static void insertAttributes(int conditionsPerRule)
    {
        int userConditions = userConditionCount(conditionsPerRule);
        int resourceConditions = conditionsPerRule - userConditions;
        for (int i = 0; i < userConditions; i++)
            QueryProcessor.executeInternal(String.format("INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES ('%s', 'u%d', 'match')", USER, i));
        for (int i = 0; i < resourceConditions; i++)
            QueryProcessor.executeInternal(String.format("INSERT INTO system_auth.resource_attribute_values (resource_name, attribute_name, attribute_value) VALUES ('%s', 'r%d', 'match')", RESOURCE_NAME, i));
    }

    private static void insertRules(int ruleCount, int conditionsPerRule)
    {
        for (int i = 0; i < ruleCount; i++)
        {
            boolean matching = i == ruleCount - 1;
            QueryProcessor.executeInternal(String.format(
            "INSERT INTO system_auth.abac_rules (rule_name, permissions, user_attribute_conditions, resource_attribute_conditions, environment_attribute_conditions, effect) VALUES ('exp1_r%04d', {'SELECT'}, %s, %s, {}, 'GRANT')",
            i, userConditions(conditionsPerRule, matching, i), resourceConditions(conditionsPerRule)));
        }
    }

    private static int userConditionCount(int conditionsPerRule)
    {
        // The report's even-split instruction is impossible for one condition.
        return conditionsPerRule == 1 ? 1 : conditionsPerRule / 2;
    }

    private static String userConditions(int conditionsPerRule, boolean matching, int ruleIndex)
    {
        StringBuilder conditions = new StringBuilder("{");
        for (int i = 0; i < userConditionCount(conditionsPerRule); i++)
        {
            if (i > 0)
                conditions.append(',');
            String value = !matching && i == 0 ? "nonmatch_" + ruleIndex : "match";
            conditions.append("'u").append(i).append("':'").append(value).append("'");
        }
        return conditions.append('}').toString();
    }

    private static String resourceConditions(int conditionsPerRule)
    {
        StringBuilder conditions = new StringBuilder("{");
        for (int i = 0; i < conditionsPerRule - userConditionCount(conditionsPerRule); i++)
        {
            if (i > 0)
                conditions.append(',');
            conditions.append("'r").append(i).append("':'match'");
        }
        return conditions.append('}').toString();
    }

    private static String metadata()
    {
        return String.format(Locale.ROOT,
                             "experiment=Chapter 6 Experiment 1%n" +
                             "method=getAbacPermissions(AuthenticatedUser, IResource)%n" +
                             "warmup_calls_per_point=%d%n" +
                             "measured_calls_per_point=%d%n" +
                             "threads=1%n" +
                             "environment_conditions=none%n" +
                             "nonmatching_rules=all except last; first user condition mismatches%n" +
                             "one_condition_convention=one user condition, zero resource conditions%n" +
                             "logging=WARN (excluded from timing)%n" +
                             "java.version=%s%n" +
                             "java.vm.name=%s%n" +
                             "os.name=%s%n" +
                             "os.version=%s%n" +
                             "os.arch=%s%n" +
                             "available_processors=%d%n",
                             WARMUP_CALLS, MEASURED_CALLS,
                             System.getProperty("java.version"), System.getProperty("java.vm.name"),
                             System.getProperty("os.name"), System.getProperty("os.version"),
                             System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
    }
}
