/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cassandra.auth;

import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.CQLTester;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.LoggerFactory;

/**
 * Controlled reproduction of Chapter 6, Experiment 1 in BTP_Report_Rishabh.
 *
 * The benchmark invokes CassandraAuthorizer.getAbacPermissions directly.  Each
 * parameter trial creates one matching rule and ruleCount - 1 non-matching rules
 * in the embedded test node's system_auth keyspace.  No environment conditions
 * are created.  A single condition is assigned to the user; even condition counts
 * are split evenly between user and resource conditions.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class AbacExperiment1Bench extends CQLTester
{
    private static final String USER = "abac_exp1_user";
    private static final String RESOURCE_NAME = "data/abac_experiment_1/target";
    private static final IResource RESOURCE = DataResource.table("abac_experiment_1", "target");
    private static boolean serverInitialized;

    @Param({ "1", "10", "50", "100", "200", "400", "600", "800", "1000" })
    public int ruleCount;

    @Param({ "1", "2", "4", "8" })
    public int conditionsPerRule;

    private CassandraAuthorizer authorizer;
    private AuthenticatedUser user;

    @Setup(org.openjdk.jmh.annotations.Level.Trial)
    public void setupTrial()
    {
        initializeServer();
        ((Logger) LoggerFactory.getLogger(CassandraAuthorizer.class)).setLevel(Level.WARN);

        QueryProcessor.executeInternal("TRUNCATE system_auth.user_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.resource_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.abac_rules");

        insertAttributes();
        insertRules();

        authorizer = new CassandraAuthorizer();
        authorizer.setup();
        user = new AuthenticatedUser(USER);
    }

    private static synchronized void initializeServer()
    {
        if (!serverInitialized)
        {
            CQLTester.setUpClass();
            serverInitialized = true;
        }
    }

    @TearDown(org.openjdk.jmh.annotations.Level.Trial)
    public void tearDownTrial()
    {
        CQLTester.tearDownClass();
        serverInitialized = false;
    }

    @Benchmark
    public Object getAbacPermissions()
    {
        return authorizer.getAbacPermissions(user, RESOURCE);
    }

    private void insertAttributes()
    {
        int userConditions = userConditionCount();
        int resourceConditions = conditionsPerRule - userConditions;
        for (int i = 0; i < userConditions; i++)
            QueryProcessor.executeInternal(String.format("INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES ('%s', 'u%d', 'match')", USER, i));
        for (int i = 0; i < resourceConditions; i++)
            QueryProcessor.executeInternal(String.format("INSERT INTO system_auth.resource_attribute_values (resource_name, attribute_name, attribute_value) VALUES ('%s', 'r%d', 'match')", RESOURCE_NAME, i));
    }

    private void insertRules()
    {
        for (int i = 0; i < ruleCount; i++)
        {
            boolean matching = i == ruleCount - 1;
            QueryProcessor.executeInternal(String.format(
            "INSERT INTO system_auth.abac_rules (rule_name, permissions, user_attribute_conditions, resource_attribute_conditions, environment_attribute_conditions, effect) VALUES ('exp1_r%04d', {'SELECT'}, %s, %s, {}, 'GRANT')",
            i, userConditions(matching, i), resourceConditions()));
        }
    }

    private int userConditionCount()
    {
        // Chapter 6 specifies an even split, but includes one condition.  For
        // that underspecified case, use one user condition and no resource one.
        return conditionsPerRule == 1 ? 1 : conditionsPerRule / 2;
    }

    private String userConditions(boolean matching, int ruleIndex)
    {
        StringBuilder conditions = new StringBuilder("{");
        for (int i = 0; i < userConditionCount(); i++)
        {
            if (i > 0)
                conditions.append(',');
            String value = !matching && i == 0 ? "nonmatch_" + ruleIndex : "match";
            conditions.append("'u").append(i).append("':'").append(value).append("'");
        }
        return conditions.append('}').toString();
    }

    private String resourceConditions()
    {
        StringBuilder conditions = new StringBuilder("{");
        for (int i = 0; i < conditionsPerRule - userConditionCount(); i++)
        {
            if (i > 0)
                conditions.append(',');
            conditions.append("'r").append(i).append("':'match'");
        }
        return conditions.append('}').toString();
    }
}
