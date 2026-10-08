/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cassandra.auth;

import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import org.apache.cassandra.service.EnvironmentAttributeManager;

import static org.junit.Assert.assertEquals;

public class FixedEnvironmentAttributeProviderTest
{
    @Test
    public void registersValuesFromConfiguration() throws Exception
    {
        AbacConfigurationLoader.Dataset dataset = AbacConfigurationLoader.readDataset(
        Path.of("benchmarks/abac-experiments/config-001"));
        AbacConfigurationLoader.registerEnvironmentAttributes(dataset);

        EnvironmentAttributeManager manager = EnvironmentAttributeManager.getInstance();
        for (Map.Entry<String, String> attribute : dataset.environmentAttributes.entrySet())
            assertEquals(attribute.getValue(), manager.getAttributeValue(attribute.getKey()));
    }
}
