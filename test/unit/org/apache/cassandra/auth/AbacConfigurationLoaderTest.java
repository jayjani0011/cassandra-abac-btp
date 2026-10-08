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

        CassandraAuthorizer authorizer = new CassandraAuthorizer();
        String matchingUser = matchingUser(dataset);
        assertEquals(Collections.singleton(Permission.SELECT),
                     authorizer.getAbacPermissions(new AuthenticatedUser(matchingUser),
                                                    DataResource.table("abac_benchmark", "resource_0001")));
    }

    private static long rowCount(String table)
    {
        return QueryProcessor.executeInternal("SELECT count(*) AS count FROM " + table).one().getLong("count");
    }

    private static void assertStoredConditions(Map<String, String> expected, Map<String, String> actual)
    {
        assertEquals(expected.isEmpty() ? null : expected, actual);
    }

    private static String matchingUser(AbacConfigurationLoader.Dataset dataset)
    {
        Map<String, String> conditions = dataset.conditionsByRule.get("rule_0001").user;
        for (AbacConfigurationLoader.AttributeAssignment assignment : dataset.userAttributes)
        {
            if (conditions.size() == 1 && conditions.get(assignment.attributeName) != null &&
                conditions.get(assignment.attributeName).equals(assignment.attributeValue))
                return assignment.entityName;
        }
        throw new AssertionError("C1 should contain a user matching rule_0001");
    }
}
