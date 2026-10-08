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

/** Generates reproducible ABAC policy data from a seed and configuration parameters. */
public final class AbacConfigurationGenerator
{
    private static final String GENERATOR_VERSION = "3";
    private static final String PERMISSION = "SELECT";
    private static final String EFFECT = "GRANT";
    private static final String KEYSPACE = "abac_benchmark";

    private AbacConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 9)
            throw new IllegalArgumentException("Usage: AbacConfigurationGenerator <output-directory> <seed> <users> <resources> <attributes-per-entity> <values-per-attribute> <rules> <conditions-per-rule> <environment-conditions-per-rule>");

        Path outputDirectory = Path.of(args[0]);
        Configuration configuration = new Configuration(Long.parseLong(args[1]),
                                                        positive(args[2], "users"),
                                                        positive(args[3], "resources"),
                                                        positive(args[4], "attributes-per-entity"),
                                                        positive(args[5], "values-per-attribute"),
                                                        positive(args[6], "rules"),
                                                        positive(args[7], "conditions-per-rule"),
                                                        positive(args[8], "environment-conditions-per-rule"));
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

    static GeneratedConfiguration generate(Configuration configuration)
    {
        Random random = new Random(configuration.seed);
        int[][] userValues = randomValues(configuration.users, configuration.attributesPerEntity,
                                          configuration.valuesPerAttribute, random);
        int[][] resourceValues = randomValues(configuration.resources, configuration.attributesPerEntity,
                                              configuration.valuesPerAttribute, random);
        int[] environmentValues = randomValues(configuration.environmentConditionsPerRule,
                                                configuration.valuesPerAttribute, random);

        int userConditionCount = (configuration.conditionsPerRule + 1) / 2;
        int resourceConditionCount = configuration.conditionsPerRule - userConditionCount;

        List<Rule> rules = new ArrayList<>(configuration.rules);
        for (int ruleIndex = 0; ruleIndex < configuration.rules; ruleIndex++)
        {
            rules.add(randomRule(String.format(Locale.ROOT, "rule_%04d", ruleIndex + 1), configuration,
                                 userConditionCount, resourceConditionCount,
                                 environmentValues, random));
        }

        return new GeneratedConfiguration(configuration, userValues, resourceValues, environmentValues, rules);
    }

    private static int[][] randomValues(int entityCount, int attributeCount, int valueCount, Random random)
    {
        int[][] values = new int[entityCount][attributeCount];
        for (int entity = 0; entity < entityCount; entity++)
            for (int attribute = 0; attribute < attributeCount; attribute++)
                values[entity][attribute] = random.nextInt(valueCount);
        return values;
    }

    private static int[] randomValues(int attributeCount, int valueCount, Random random)
    {
        int[] values = new int[attributeCount];
        for (int attribute = 0; attribute < attributeCount; attribute++)
            values[attribute] = random.nextInt(valueCount);
        return values;
    }

    private static Rule randomRule(String name, Configuration configuration, int userConditionCount,
                                   int resourceConditionCount, int[] environmentValues, Random random)
    {
        List<Condition> conditions = new ArrayList<>();
        for (int attribute : selectedAttributes(configuration.attributesPerEntity, userConditionCount, random))
            conditions.add(new Condition(EntityType.USER, attribute, random.nextInt(configuration.valuesPerAttribute)));
        for (int attribute : selectedAttributes(configuration.attributesPerEntity, resourceConditionCount, random))
            conditions.add(new Condition(EntityType.RESOURCE, attribute, random.nextInt(configuration.valuesPerAttribute)));
        for (int attribute : selectedAttributes(environmentValues.length, environmentValues.length, random))
            conditions.add(new Condition(EntityType.ENVIRONMENT, attribute, environmentValues[attribute]));
        return new Rule(name, conditions);
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
        writeEnvironmentAttributes(outputDirectory.resolve("environment_attributes.csv"), generated.environmentValues);
        writeRules(outputDirectory.resolve("abac_rules.csv"), generated.rules);
        writeRuleConditions(outputDirectory.resolve("rule_conditions.csv"), generated.rules);
    }

    private static void writeConfiguration(Path path, GeneratedConfiguration generated) throws IOException
    {
        writeConfiguration(path, generated.configuration, generated.configuration.rules);
    }

    static void writeConfiguration(Path path, Configuration c, int rules) throws IOException
    {
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
                                    "  \"environment_conditions_per_rule\": %d,\n" +
                                    "  \"rule_permission\": \"%s\"\n" +
                                    "}\n",
                                    GENERATOR_VERSION, c.seed, c.users, c.resources, c.attributesPerEntity,
                                    c.valuesPerAttribute, rules, c.conditionsPerRule,
                                    c.environmentConditionsPerRule, PERMISSION);
        Files.writeString(path, json);
    }

    static void writeUsers(Path path, int userCount) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("user_name\n");
            for (int user = 0; user < userCount; user++)
                writer.write(userName(user) + "\n");
        }
    }

    static void writeResources(Path path, int resourceCount) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("resource_name\n");
            for (int resource = 0; resource < resourceCount; resource++)
                writer.write(resourceName(resource) + "\n");
        }
    }

    static void writeAttributes(Path path, int[][] values, EntityType entityType) throws IOException
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

    static void writeEnvironmentAttributes(Path path, int[] values) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("attribute_name,attribute_value\n");
            for (int attribute = 0; attribute < values.length; attribute++)
                writer.write(attributeName(EntityType.ENVIRONMENT, attribute) + "," + valueName(values[attribute]) + "\n");
        }
    }

    static void writeRules(Path path, List<Rule> rules) throws IOException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(path))
        {
            writer.write("rule_name,effect,permission\n");
            for (Rule rule : rules)
                writer.write(rule.name + "," + EFFECT + "," + PERMISSION + "\n");
        }
    }

    static void writeRuleConditions(Path path, List<Rule> rules) throws IOException
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
        if (entityType == EntityType.USER)
            return String.format(Locale.ROOT, "u_attr_%02d", index + 1);
        if (entityType == EntityType.RESOURCE)
            return String.format(Locale.ROOT, "r_attr_%02d", index + 1);
        return String.format(Locale.ROOT, "e_attr_%02d", index + 1);
    }

    private static String valueName(int index)
    {
        return String.format(Locale.ROOT, "value_%02d", index);
    }

    enum EntityType
    {
        USER,
        RESOURCE,
        ENVIRONMENT
    }

    static final class Configuration
    {
        final long seed;
        final int users;
        final int resources;
        final int attributesPerEntity;
        final int valuesPerAttribute;
        final int rules;
        final int conditionsPerRule;
        final int environmentConditionsPerRule;

        Configuration(long seed, int users, int resources, int attributesPerEntity,
                      int valuesPerAttribute, int rules, int conditionsPerRule,
                      int environmentConditionsPerRule)
        {
            this.seed = seed;
            this.users = users;
            this.resources = resources;
            this.attributesPerEntity = attributesPerEntity;
            this.valuesPerAttribute = valuesPerAttribute;
            this.rules = rules;
            this.conditionsPerRule = conditionsPerRule;
            this.environmentConditionsPerRule = environmentConditionsPerRule;
        }

        void validate()
        {
            int userConditions = (conditionsPerRule + 1) / 2;
            int resourceConditions = conditionsPerRule - userConditions;
            if (userConditions > attributesPerEntity || resourceConditions > attributesPerEntity)
                throw new IllegalArgumentException("conditions-per-rule requires " + userConditions +
                                                   " user attributes and " + resourceConditions +
                                                   " resource attributes, but attributes-per-entity is " + attributesPerEntity);
        }
    }

    static final class GeneratedConfiguration
    {
        final Configuration configuration;
        final int[][] userValues;
        final int[][] resourceValues;
        final int[] environmentValues;
        final List<Rule> rules;

        GeneratedConfiguration(Configuration configuration, int[][] userValues, int[][] resourceValues,
                               int[] environmentValues, List<Rule> rules)
        {
            this.configuration = configuration;
            this.userValues = userValues;
            this.resourceValues = resourceValues;
            this.environmentValues = environmentValues;
            this.rules = rules;
        }
    }

    static final class Rule
    {
        final String name;
        final List<Condition> conditions;

        Rule(String name, List<Condition> conditions)
        {
            this.name = name;
            this.conditions = conditions;
        }
    }

    static final class Condition
    {
        final EntityType entityType;
        final int attributeIndex;
        final int requiredValue;

        Condition(EntityType entityType, int attributeIndex, int requiredValue)
        {
            this.entityType = entityType;
            this.attributeIndex = attributeIndex;
            this.requiredValue = requiredValue;
        }
    }
}
