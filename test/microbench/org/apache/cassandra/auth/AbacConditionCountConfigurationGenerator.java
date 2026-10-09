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

/** Produces condition-count variants while preserving each profile's saved entities and attributes. */
public final class AbacConditionCountConfigurationGenerator
{
    private static final int[][] CONDITION_COUNTS =
    {
    { 1, 2 },
    { 1, 2, 3, 4 },
    { 1, 2, 4, 6, 8 },
    { 1, 2, 4, 8, 12, 16 },
    { 1, 2, 4, 8, 12, 16, 24 },
    { 1, 2, 4, 8, 12, 16, 24, 32 }
    };

    private AbacConditionCountConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AbacConditionCountConfigurationGenerator <source-configurations-directory> <condition-count-output-directory>");

        Path sourceRoot = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        for (int profile = 1; profile <= 6; profile++)
        {
            String name = String.format(Locale.ROOT, "config-%03d", profile);
            generateProfile(sourceRoot.resolve(name), outputRoot.resolve(name), CONDITION_COUNTS[profile - 1]);
        }
        writeManifest(outputRoot);
    }

    private static void generateProfile(Path sourceDirectory, Path outputDirectory, int[] conditionCounts) throws IOException
    {
        AbacConfigurationGenerator.Configuration source = readConfiguration(sourceDirectory.resolve("configuration.json"));
        for (int conditionCount : conditionCounts)
        {
            AbacConfigurationGenerator.Configuration variant = new AbacConfigurationGenerator.Configuration(
            source.seed, source.users, source.resources, source.attributesPerEntity, source.valuesPerAttribute,
            source.rules, conditionCount, source.environmentConditionsPerRule);
            variant.validate();

            AbacConfigurationGenerator.GeneratedConfiguration generated = withAnchoredFirstRule(AbacConfigurationGenerator.generate(variant));
            writeVariant(outputDirectory.resolve(String.format(Locale.ROOT, "conditions-%03d", conditionCount)), generated);
        }
    }

    private static AbacConfigurationGenerator.Configuration readConfiguration(Path path) throws IOException
    {
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Missing source configuration: " + path);

        String json = Files.readString(path);
        AbacConfigurationGenerator.Configuration configuration = new AbacConfigurationGenerator.Configuration(
        longField(json, "seed"), intField(json, "users"), intField(json, "resources"),
        intField(json, "attributes_per_entity"), intField(json, "values_per_attribute"), intField(json, "rules"),
        intField(json, "conditions_per_rule"), intField(json, "environment_conditions_per_rule"));
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

    private static void writeVariant(Path directory, AbacConfigurationGenerator.GeneratedConfiguration generated) throws IOException
    {
        requirePlaceholderDirectory(directory);
        AbacConfigurationGenerator.writeConfiguration(directory.resolve("configuration.json"), generated.configuration, generated.rules.size());
        AbacConfigurationGenerator.writeRules(directory.resolve("abac_rules.csv"), generated.rules);
        AbacConfigurationGenerator.writeRuleConditions(directory.resolve("rule_conditions.csv"), generated.rules);
    }

    private static void requirePlaceholderDirectory(Path directory) throws IOException
    {
        if (!Files.exists(directory))
        {
            Files.createDirectories(directory);
            return;
        }
        if (!Files.isDirectory(directory))
            throw new IllegalArgumentException("Expected output directory: " + directory);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
        {
            for (Path entry : entries)
                if (!entry.getFileName().toString().equals(".gitkeep"))
                    throw new IllegalArgumentException("Output directory already contains generated data: " + directory);
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
            throw new IllegalArgumentException("Missing numeric field: " + field);
        return matcher.group(1);
    }

    private static void writeManifest(Path outputRoot) throws IOException
    {
        try (var writer = Files.newBufferedWriter(outputRoot.resolve("points.csv")))
        {
            writer.write("profile,conditions_per_rule,point_id,configuration_json,users_csv,resources_csv,user_attributes_csv,resource_attributes_csv,environment_attributes_csv,abac_rules_csv,rule_conditions_csv\n");
            for (int profile = 1; profile <= 6; profile++)
            {
                String base = String.format(Locale.ROOT, "../config-%03d", profile);
                for (int conditionCount : CONDITION_COUNTS[profile - 1])
                {
                    String variant = String.format(Locale.ROOT, "config-%03d/conditions-%03d", profile, conditionCount);
                    writer.write(String.format(Locale.ROOT,
                                               "C%d,%d,C%d-conditions-%03d,%s/configuration.json,%s/users.csv,%s/resources.csv,%s/user_attributes.csv,%s/resource_attributes.csv,%s/environment_attributes.csv,%s/abac_rules.csv,%s/rule_conditions.csv%n",
                                               profile, conditionCount, profile, conditionCount, variant,
                                               base, base, base, base, base, variant, variant));
                }
            }
        }
    }
}
