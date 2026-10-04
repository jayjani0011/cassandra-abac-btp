/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.cassandra.auth;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Generates one reproducible ABAC benchmark configuration and writes the complete policy input to disk.
 *
 * <p>The generated policy contains exactly one rule which grants SELECT to the generated probe request.
 * Every other rule is random, but is deliberately made not to match that probe request. This gives a known
 * allowed request for a timing runner without relying on a separate policy evaluator.</p>
 *
 * <p>Usage:
 * <pre>
 * AbacConfigurationGenerator &lt;output-directory&gt; &lt;seed&gt; &lt;users&gt; &lt;resources&gt;
 *                            &lt;attributes-per-entity&gt; &lt;values-per-attribute&gt;
 *                            &lt;rules&gt; &lt;conditions-per-rule&gt;
 * </pre>
 */
public final class AbacConfigurationGenerator
{
    private static final String GENERATOR_VERSION = "1";
    private static final String PERMISSION = "SELECT";
    private static final String EFFECT = "GRANT";
    private static final String KEYSPACE = "abac_benchmark";

    private AbacConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 8)
            throw new IllegalArgumentException("Usage: AbacConfigurationGenerator <output-directory> <seed> <users> <resources> <attributes-per-entity> <values-per-attribute> <rules> <conditions-per-rule>");

        Path outputDirectory = Path.of(args[0]);
        Configuration configuration = new Configuration(Long.parseLong(args[1]),
                                                        positive(args[2], "users"),
                                                        positive(args[3], "resources"),
                                                        positive(args[4], "attributes-per-entity"),
                                                        positive(args[5], "values-per-attribute"),
                                                        positive(args[6], "rules"),
                                                        positive(args[7], "conditions-per-rule"));
        configuration.validate();

        requireEmptyDirectory(outputDirectory);
        GeneratedConfiguration generated = generate(configuration);
        write(outputDirectory, generated);
    }

    private static int positive(String value, String name)
    {
        int parsed = Integer.parseInt(value);
        if (parsed < 1)
            throw new IllegalArgumentException(name + " must be at least 1");
        return parsed;
    }

    private static void requireEmptyDirectory(Path directory) throws IOException
    {
        if (Files.exists(directory))
        {
            if (!Files.isDirectory(directory))
                throw new IllegalArgumentException("Output path is not a directory: " + directory);

            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
            {
                if (entries.iterator().hasNext())
                    throw new IllegalArgumentException("Output directory must be empty: " + directory);
            }
        }
        else
        {
            Files.createDirectories(directory);
        }
    }

    private static GeneratedConfiguration generate(Configuration configuration)
    {
        Random random = new Random(configuration.seed);
        int[][] userValues = randomValues(configuration.users, configuration.attributesPerEntity,
                                          configuration.valuesPerAttribute, random);
        int[][] resourceValues = randomValues(configuration.resources, configuration.attributesPerEntity,
                                              configuration.valuesPerAttribute, random);

        int probeUser = random.nextInt(configuration.users);
        int probeResource = random.nextInt(configuration.resources);
        int userConditionCount = (configuration.conditionsPerRule + 1) / 2;
        int resourceConditionCount = configuration.conditionsPerRule - userConditionCount;

        List<Rule> rules = new ArrayList<>(configuration.rules);
        rules.add(matchingRule("rule_0001", probeUser, probeResource, userValues, resourceValues,
                               userConditionCount, resourceConditionCount, random));
        for (int ruleIndex = 1; ruleIndex < configuration.rules; ruleIndex++)
        {
            rules.add(nonMatchingRule(String.format(Locale.ROOT, "rule_%04d", ruleIndex + 1), probeUser,
                                      probeResource, configuration, userValues, resourceValues,
                                      userConditionCount, resourceConditionCount, random));
        }

        return new GeneratedConfiguration(configuration, userValues, resourceValues, rules, probeUser, probeResource);
    }

    private static int[][] randomValues(int entityCount, int attributeCount, int valueCount, Random random)
    {
        int[][] values = new int[entityCount][attributeCount];
        for (int entity = 0; entity < entityCount; entity++)
            for (int attribute = 0; attribute < attributeCount; attribute++)
                values[entity][attribute] = random.nextInt(valueCount);
        return values;
    }

    private static Rule matchingRule(String name, int probeUser, int probeResource, int[][] userValues,
                                     int[][] resourceValues, int userConditionCount, int resourceConditionCount,
                                     Random random)
    {
        List<Condition> conditions = new ArrayList<>();
        for (int attribute : selectedAttributes(userValues[probeUser].length, userConditionCount, random))
            conditions.add(new Condition(EntityType.USER, attribute, userValues[probeUser][attribute]));
        for (int attribute : selectedAttributes(resourceValues[probeResource].length, resourceConditionCount, random))
            conditions.add(new Condition(EntityType.RESOURCE, attribute, resourceValues[probeResource][attribute]));
        return new Rule(name, conditions, true);
    }

    private static Rule nonMatchingRule(String name, int probeUser, int probeResource, Configuration configuration,
                                        int[][] userValues, int[][] resourceValues, int userConditionCount,
                                        int resourceConditionCount, Random random)
    {
        List<Condition> conditions = new ArrayList<>();
        for (int attribute : selectedAttributes(configuration.attributesPerEntity, userConditionCount, random))
            conditions.add(new Condition(EntityType.USER, attribute, random.nextInt(configuration.valuesPerAttribute)));
        for (int attribute : selectedAttributes(configuration.attributesPerEntity, resourceConditionCount, random))
            conditions.add(new Condition(EntityType.RESOURCE, attribute, random.nextInt(configuration.valuesPerAttribute)));

        // Ensure this rule cannot match the probe. We use another valid value from the same domain,
        // rather than a synthetic value that falls outside the configured value cardinality.
        Condition condition = conditions.get(random.nextInt(conditions.size()));
        int probeValue = condition.entityType == EntityType.USER
                         ? userValues[probeUser][condition.attributeIndex]
                         : resourceValues[probeResource][condition.attributeIndex];
        condition.requiredValue = differentValue(probeValue, configuration.valuesPerAttribute, random);
        return new Rule(name, conditions, false);
    }

    private static int differentValue(int value, int valueCount, Random random)
    {
        int offset = 1 + random.nextInt(valueCount - 1);
        return (value + offset) % valueCount;
    }

    private static List<Integer> selectedAttributes(int attributeCount, int needed, Random random)
    {
        List<Integer> attributes = new ArrayList<>(attributeCount);
        for (int attribute = 0; attribute < attributeCount; attribute++)
            attributes.add(attribute);
        Collections.shuffle(attributes, random);
        return attributes.subList(0, needed);
    }

    private static void write(Path outputDirectory, GeneratedConfiguration generated) throws IOException
    {
        writeConfiguration(outputDirectory.resolve("configuration.json"), generated);
        writeUsers(outputDirectory.resolve("users.csv"), generated.configuration.users);
        writeResources(outputDirectory.resolve("resources.csv"), generated.configuration.resources);
        writeAttributes(outputDirectory.resolve("user_attributes.csv"), generated.userValues, EntityType.USER);
        writeAttributes(outputDirectory.resolve("resource_attributes.csv"), generated.resourceValues, EntityType.RESOURCE);
        writeRules(outputDirectory.resolve("abac_rules.csv"), generated.rules);
        writeRuleConditions(outputDirectory.resolve("rule_conditions.csv"), generated.rules);
        writeProbeRequest(outputDirectory.resolve("request_workload.csv"), generated);
    }

    private static void writeConfiguration(Path path, GeneratedConfiguration generated) throws IOException
    {
        Configuration c = generated.configuration;
        String json = String.format(Locale.ROOT,
                                    "{\n" +
                                    "  \"generator_version\": \"%s\",\n" +
                                    "  \"seed\": %d,\n" +
                                    "  \"users\": %d,\n" +
                                    "  \"resources\": %d,\n" +
                                    "  \"attributes_per_entity\": %d,\n" +
                                    "  \"values_per_attribute\": %d,\n" +
                                    "  \"rules\": %d,\n" +
                                    "  \"conditions_per_rule\": %d,\n" +
                                    "  \"permission\": \"%s\",\n" +
                                    "  \"probe_user\": \"%s\",\n" +
                                    "  \"probe_resource\": \"%s\"\n" +
                                    "}\n",
                                    GENERATOR_VERSION, c.seed, c.users, c.resources, c.attributesPerEntity,
                                    c.valuesPerAttribute, c.rules, c.conditionsPerRule, PERMISSION,
                                    userName(generated.probeUser), resourceName(generated.probeResource));
        Files.writeString(path, json);
    }

    private static void writeUsers(Path path, int userCount) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("user_name\n");
            for (int user = 0; user < userCount; user++)
                writer.write(userName(user) + "\n");
        }
    }

    private static void writeResources(Path path, int resourceCount) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("resource_name\n");
            for (int resource = 0; resource < resourceCount; resource++)
                writer.write(resourceName(resource) + "\n");
        }
    }

    private static void writeAttributes(Path path, int[][] values, EntityType entityType) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write(entityType == EntityType.USER
                         ? "user_name,attribute_name,attribute_value\n"
                         : "resource_name,attribute_name,attribute_value\n");
            for (int entity = 0; entity < values.length; entity++)
            {
                for (int attribute = 0; attribute < values[entity].length; attribute++)
                {
                    String name = entityType == EntityType.USER ? userName(entity) : resourceName(entity);
                    writer.write(name + "," + attributeName(entityType, attribute) + "," + valueName(values[entity][attribute]) + "\n");
                }
            }
        }
    }

    private static void writeRules(Path path, List<Rule> rules) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("rule_name,effect,permission,expected_probe_match\n");
            for (Rule rule : rules)
                writer.write(rule.name + "," + EFFECT + "," + PERMISSION + "," + rule.matchesProbe + "\n");
        }
    }

    private static void writeRuleConditions(Path path, List<Rule> rules) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("rule_name,entity_type,attribute_name,required_value\n");
            for (Rule rule : rules)
            {
                for (Condition condition : rule.conditions)
                {
                    writer.write(rule.name + "," + condition.entityType.name().toLowerCase(Locale.ROOT) + "," +
                                 attributeName(condition.entityType, condition.attributeIndex) + "," +
                                 valueName(condition.requiredValue) + "\n");
                }
            }
        }
    }

    private static void writeProbeRequest(Path path, GeneratedConfiguration generated) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("request_id,user_name,resource_name,permission,expected_allowed\n");
            writer.write("probe_allow," + userName(generated.probeUser) + "," + resourceName(generated.probeResource) + "," + PERMISSION + ",true\n");
        }
    }

    private static String userName(int index)
    {
        return String.format(Locale.ROOT, "benchmark_user_%04d", index + 1);
    }

    private static String resourceName(int index)
    {
        return String.format(Locale.ROOT, "data/%s/resource_%04d", KEYSPACE, index + 1);
    }

    private static String attributeName(EntityType entityType, int index)
    {
        return String.format(Locale.ROOT, entityType == EntityType.USER ? "u_attr_%02d" : "r_attr_%02d", index + 1);
    }

    private static String valueName(int index)
    {
        return String.format(Locale.ROOT, "value_%02d", index);
    }

    private enum EntityType
    {
        USER,
        RESOURCE
    }

    private static final class Configuration
    {
        final long seed;
        final int users;
        final int resources;
        final int attributesPerEntity;
        final int valuesPerAttribute;
        final int rules;
        final int conditionsPerRule;

        private Configuration(long seed, int users, int resources, int attributesPerEntity,
                              int valuesPerAttribute, int rules, int conditionsPerRule)
        {
            this.seed = seed;
            this.users = users;
            this.resources = resources;
            this.attributesPerEntity = attributesPerEntity;
            this.valuesPerAttribute = valuesPerAttribute;
            this.rules = rules;
            this.conditionsPerRule = conditionsPerRule;
        }

        private void validate()
        {
            if (valuesPerAttribute < 2)
                throw new IllegalArgumentException("values-per-attribute must be at least 2 so non-matching rules can use another domain value");

            int userConditions = (conditionsPerRule + 1) / 2;
            int resourceConditions = conditionsPerRule - userConditions;
            if (userConditions > attributesPerEntity || resourceConditions > attributesPerEntity)
                throw new IllegalArgumentException("conditions-per-rule requires " + userConditions +
                                                   " user attributes and " + resourceConditions +
                                                   " resource attributes, but attributes-per-entity is " + attributesPerEntity);
        }
    }

    private static final class GeneratedConfiguration
    {
        final Configuration configuration;
        final int[][] userValues;
        final int[][] resourceValues;
        final List<Rule> rules;
        final int probeUser;
        final int probeResource;

        private GeneratedConfiguration(Configuration configuration, int[][] userValues, int[][] resourceValues,
                                       List<Rule> rules, int probeUser, int probeResource)
        {
            this.configuration = configuration;
            this.userValues = userValues;
            this.resourceValues = resourceValues;
            this.rules = rules;
            this.probeUser = probeUser;
            this.probeResource = probeResource;
        }
    }

    private static final class Rule
    {
        final String name;
        final List<Condition> conditions;
        final boolean matchesProbe;

        private Rule(String name, List<Condition> conditions, boolean matchesProbe)
        {
            this.name = name;
            this.conditions = conditions;
            this.matchesProbe = matchesProbe;
        }
    }

    private static final class Condition
    {
        final EntityType entityType;
        final int attributeIndex;
        int requiredValue;

        private Condition(EntityType entityType, int attributeIndex, int requiredValue)
        {
            this.entityType = entityType;
            this.attributeIndex = attributeIndex;
            this.requiredValue = requiredValue;
        }
    }
}
