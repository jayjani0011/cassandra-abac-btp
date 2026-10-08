/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cassandra.auth;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.junit.Test;

import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.marshal.UTF8Type;

import static org.junit.Assert.assertEquals;

public class AbacConfigurationLoaderTest extends CQLTester
{
    private static final Path CONFIGURATION_DIRECTORY = Path.of("benchmarks/abac-experiments/config-001");

    @Test
    public void readsConfigurationFromIndividualFiles() throws Exception
    {
        AbacConfigurationLoader.ConfigurationFiles files = new AbacConfigurationLoader.ConfigurationFiles(
        CONFIGURATION_DIRECTORY.resolve("configuration.json"),
        CONFIGURATION_DIRECTORY.resolve("users.csv"),
        CONFIGURATION_DIRECTORY.resolve("resources.csv"),
        CONFIGURATION_DIRECTORY.resolve("user_attributes.csv"),
        CONFIGURATION_DIRECTORY.resolve("resource_attributes.csv"),
        CONFIGURATION_DIRECTORY.resolve("environment_attributes.csv"),
        CONFIGURATION_DIRECTORY.resolve("abac_rules.csv"),
        CONFIGURATION_DIRECTORY.resolve("rule_conditions.csv"));

        AbacConfigurationLoader.Dataset dataset = AbacConfigurationLoader.readDataset(files);

        assertEquals(10, dataset.configuration.users);
        assertEquals(100, dataset.configuration.resources);
        assertEquals(1, dataset.rules.size());
        assertEquals(files.rules, dataset.configuration.files.rules);
    }

    @Test
    public void readsRuleCountPointWithSharedBaseProfileData() throws Exception
    {
        Path baseProfile = Path.of("benchmarks/abac-experiments/config-003");
        Path ruleCountPoint = Path.of("benchmarks/abac-experiments/rule-count/config-003/rules-050");
        AbacConfigurationLoader.ConfigurationFiles files = new AbacConfigurationLoader.ConfigurationFiles(
        ruleCountPoint.resolve("configuration.json"),
        baseProfile.resolve("users.csv"),
        baseProfile.resolve("resources.csv"),
        baseProfile.resolve("user_attributes.csv"),
        baseProfile.resolve("resource_attributes.csv"),
        baseProfile.resolve("environment_attributes.csv"),
        ruleCountPoint.resolve("abac_rules.csv"),
        ruleCountPoint.resolve("rule_conditions.csv"));

        AbacConfigurationLoader.Dataset dataset = AbacConfigurationLoader.readDataset(files);

        assertEquals(50, dataset.configuration.users);
        assertEquals(500, dataset.configuration.resources);
        assertEquals(50, dataset.rules.size());
        assertEquals(350, dataset.ruleConditions.size());
    }

    @Test
    public void loadsFirstConfigurationIntoAbacTables() throws Exception
    {
        AbacConfigurationLoader.Dataset dataset = AbacConfigurationLoader.load(CONFIGURATION_DIRECTORY);

        assertEquals(dataset.userAttributes.size(), rowCount("system_auth.user_attribute_values"));
        assertEquals(dataset.resourceAttributes.size(), rowCount("system_auth.resource_attribute_values"));
        assertEquals(dataset.rules.size(), rowCount("system_auth.abac_rules"));
        assertEquals(dataset.configuration.environmentConditionsPerRule, dataset.environmentAttributes.size());

        UntypedResultSet.Row rule = QueryProcessor.executeInternal("SELECT * FROM system_auth.abac_rules WHERE rule_name = 'rule_0001'").one();
        assertEquals("GRANT", rule.getString("effect"));
        assertEquals(Collections.singleton("SELECT"), rule.getSet("permissions", UTF8Type.instance));
        assertStoredConditions(dataset.conditionsByRule.get("rule_0001").user,
                               rule.getMap("user_attribute_conditions", UTF8Type.instance, UTF8Type.instance));
        assertStoredConditions(dataset.conditionsByRule.get("rule_0001").resource,
                               rule.getMap("resource_attribute_conditions", UTF8Type.instance, UTF8Type.instance));
        assertStoredConditions(dataset.conditionsByRule.get("rule_0001").environment,
                               rule.getMap("environment_attribute_conditions", UTF8Type.instance, UTF8Type.instance));

    }

    private static long rowCount(String table)
    {
        return QueryProcessor.executeInternal("SELECT count(*) AS count FROM " + table).one().getLong("count");
    }

    private static void assertStoredConditions(Map<String, String> expected, Map<String, String> actual)
    {
        assertEquals(expected.isEmpty() ? null : expected, actual);
    }

}
