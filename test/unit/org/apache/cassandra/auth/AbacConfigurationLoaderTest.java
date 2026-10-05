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
    public void loadsFirstConfigurationIntoAbacTables() throws Exception
    {
        AbacConfigurationLoader.Dataset dataset = AbacConfigurationLoader.load(CONFIGURATION_DIRECTORY);

        assertEquals(dataset.userAttributes.size(), rowCount("system_auth.user_attribute_values"));
        assertEquals(dataset.resourceAttributes.size(), rowCount("system_auth.resource_attribute_values"));
        assertEquals(dataset.rules.size(), rowCount("system_auth.abac_rules"));

        UntypedResultSet.Row rule = QueryProcessor.executeInternal("SELECT * FROM system_auth.abac_rules WHERE rule_name = 'rule_0001'").one();
        assertEquals("GRANT", rule.getString("effect"));
        assertEquals(Collections.singleton("SELECT"), rule.getSet("permissions", UTF8Type.instance));
        assertStoredConditions(dataset.conditionsByRule.get("rule_0001").user,
                               rule.getMap("user_attribute_conditions", UTF8Type.instance, UTF8Type.instance));
        assertStoredConditions(dataset.conditionsByRule.get("rule_0001").resource,
                               rule.getMap("resource_attribute_conditions", UTF8Type.instance, UTF8Type.instance));
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
