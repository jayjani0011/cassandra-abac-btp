package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates nested rule-count inputs from the saved C1-C6 configuration metadata. */
public final class AbacRuleCountConfigurationGenerator
{
    private static final int MASTER_RULE_COUNT = 500;
    private static final int[] RULE_COUNTS = { 1, 10, 50, 100, 250, 500 };

    private AbacRuleCountConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AbacRuleCountConfigurationGenerator <source-configurations-directory> <rule-count-output-directory>");

        Path sourceRoot = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        for (int index = 1; index <= 6; index++)
        {
            String name = String.format(Locale.ROOT, "config-%03d", index);
            generateProfile(sourceRoot.resolve(name), outputRoot.resolve(name));
        }
    }

    private static void generateProfile(Path sourceDirectory, Path outputDirectory) throws IOException
    {
        AbacConfigurationGenerator.Configuration configuration = readConfiguration(sourceDirectory.resolve("configuration.json"));
        AbacConfigurationGenerator.GeneratedConfiguration generated = AbacConfigurationGenerator.generate(configuration);
        generated = withAnchoredFirstRule(generated);

        writeCommon(outputDirectory.resolve("common"), generated);
        for (int ruleCount : RULE_COUNTS)
            writeRulePrefix(outputDirectory.resolve(String.format(Locale.ROOT, "rules-%03d", ruleCount)), generated, ruleCount);
    }

    private static AbacConfigurationGenerator.Configuration readConfiguration(Path path) throws IOException
    {
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Missing source configuration: " + path);

        String json = Files.readString(path);
        AbacConfigurationGenerator.Configuration configuration = new AbacConfigurationGenerator.Configuration(
        longField(json, "seed"),
        intField(json, "users"),
        intField(json, "resources"),
        intField(json, "attributes_per_entity"),
        intField(json, "values_per_attribute"),
        MASTER_RULE_COUNT,
        intField(json, "conditions_per_rule"),
        intField(json, "environment_conditions_per_rule"));
        configuration.validate();
        return configuration;
    }

    private static AbacConfigurationGenerator.GeneratedConfiguration withAnchoredFirstRule(AbacConfigurationGenerator.GeneratedConfiguration generated)
    {
        List<AbacConfigurationGenerator.Rule> rules = new ArrayList<>(generated.rules);
        AbacConfigurationGenerator.Rule firstRule = rules.get(0);
        List<AbacConfigurationGenerator.Condition> conditions = new ArrayList<>(firstRule.conditions.size());

        for (AbacConfigurationGenerator.Condition condition : firstRule.conditions)
        {
            int value = condition.requiredValue;
            if (condition.entityType == AbacConfigurationGenerator.EntityType.USER)
                value = generated.userValues[0][condition.attributeIndex];
            else if (condition.entityType == AbacConfigurationGenerator.EntityType.RESOURCE)
                value = generated.resourceValues[0][condition.attributeIndex];
            else if (condition.entityType == AbacConfigurationGenerator.EntityType.ENVIRONMENT)
                value = generated.environmentValues[condition.attributeIndex];

            conditions.add(new AbacConfigurationGenerator.Condition(condition.entityType, condition.attributeIndex, value));
        }

        rules.set(0, new AbacConfigurationGenerator.Rule(firstRule.name, conditions));
        return new AbacConfigurationGenerator.GeneratedConfiguration(generated.configuration, generated.userValues,
                                                                      generated.resourceValues, generated.environmentValues,
                                                                      rules);
    }

    private static void writeCommon(Path directory, AbacConfigurationGenerator.GeneratedConfiguration generated) throws IOException
    {
        requirePlaceholderDirectory(directory);
        AbacConfigurationGenerator.writeUsers(directory.resolve("users.csv"), generated.configuration.users);
        AbacConfigurationGenerator.writeResources(directory.resolve("resources.csv"), generated.configuration.resources);
        AbacConfigurationGenerator.writeAttributes(directory.resolve("user_attributes.csv"), generated.userValues,
                                                   AbacConfigurationGenerator.EntityType.USER);
        AbacConfigurationGenerator.writeAttributes(directory.resolve("resource_attributes.csv"), generated.resourceValues,
                                                   AbacConfigurationGenerator.EntityType.RESOURCE);
        AbacConfigurationGenerator.writeEnvironmentAttributes(directory.resolve("environment_attributes.csv"), generated.environmentValues);
    }

    private static void writeRulePrefix(Path directory, AbacConfigurationGenerator.GeneratedConfiguration generated,
                                        int ruleCount) throws IOException
    {
        requirePlaceholderDirectory(directory);
        List<AbacConfigurationGenerator.Rule> rules = generated.rules.subList(0, ruleCount);
        AbacConfigurationGenerator.writeConfiguration(directory.resolve("configuration.json"), generated.configuration, ruleCount);
        AbacConfigurationGenerator.writeRules(directory.resolve("abac_rules.csv"), rules);
        AbacConfigurationGenerator.writeRuleConditions(directory.resolve("rule_conditions.csv"), rules);
    }

    private static void requirePlaceholderDirectory(Path directory) throws IOException
    {
        if (!Files.isDirectory(directory))
            throw new IllegalArgumentException("Expected output directory: " + directory);

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
        {
            for (Path entry : entries)
            {
                if (!entry.getFileName().toString().equals(".gitkeep"))
                    throw new IllegalArgumentException("Output directory already contains generated data: " + directory);
            }
        }
    }

    private static int intField(String json, String field)
    {
        return Integer.parseInt(numberField(json, field));
    }

    private static long longField(String json, String field)
    {
        return Long.parseLong(numberField(json, field));
    }

    private static String numberField(String json, String field)
    {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find())
            throw new IllegalArgumentException("Missing numeric field in " + field);
        return matcher.group(1);
    }
}
