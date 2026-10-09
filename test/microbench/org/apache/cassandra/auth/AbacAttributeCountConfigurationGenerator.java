package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces attribute-count variants from each saved C1-C6 profile. */
public final class AbacAttributeCountConfigurationGenerator
{
    private static final int[][] ATTRIBUTE_COUNTS =
    {
    { 1, 2, 4, 8, 12, 16 },
    { 1, 2, 4, 8, 12, 16 },
    { 2, 4, 8, 12, 16 },
    { 4, 8, 12, 16 },
    { 6, 8, 12, 16 },
    { 8, 12, 16 }
    };

    private AbacAttributeCountConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AbacAttributeCountConfigurationGenerator <source-configurations-directory> <attribute-count-output-directory>");

        Path sourceRoot = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        for (int profile = 1; profile <= 6; profile++)
        {
            String name = String.format(Locale.ROOT, "config-%03d", profile);
            generateProfile(sourceRoot.resolve(name).resolve("configuration.json"), outputRoot.resolve(name), ATTRIBUTE_COUNTS[profile - 1]);
        }
        writeManifest(outputRoot);
    }

    private static void generateProfile(Path sourceConfiguration, Path outputDirectory, int[] attributeCounts) throws IOException
    {
        AbacConfigurationGenerator.Configuration source = readConfiguration(sourceConfiguration);
        for (int attributeCount : attributeCounts)
        {
            AbacConfigurationGenerator.Configuration variant = new AbacConfigurationGenerator.Configuration(
            source.seed, source.users, source.resources, attributeCount, source.valuesPerAttribute, source.rules,
            source.conditionsPerRule, source.environmentConditionsPerRule);
            variant.validate();
            writeVariant(outputDirectory.resolve(String.format(Locale.ROOT, "attributes-%03d", attributeCount)),
                         withAnchoredFirstRule(AbacConfigurationGenerator.generate(variant)));
        }
    }

    private static AbacConfigurationGenerator.Configuration readConfiguration(Path path) throws IOException
    {
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
        Files.createDirectories(directory);
        AbacConfigurationGenerator.writeConfiguration(directory.resolve("configuration.json"), generated.configuration, generated.rules.size());
        AbacConfigurationGenerator.writeAttributes(directory.resolve("user_attributes.csv"), generated.userValues, AbacConfigurationGenerator.EntityType.USER);
        AbacConfigurationGenerator.writeAttributes(directory.resolve("resource_attributes.csv"), generated.resourceValues, AbacConfigurationGenerator.EntityType.RESOURCE);
        AbacConfigurationGenerator.writeEnvironmentAttributes(directory.resolve("environment_attributes.csv"), generated.environmentValues);
        AbacConfigurationGenerator.writeRules(directory.resolve("abac_rules.csv"), generated.rules);
        AbacConfigurationGenerator.writeRuleConditions(directory.resolve("rule_conditions.csv"), generated.rules);
    }

    private static void writeManifest(Path outputRoot) throws IOException
    {
        try (var writer = Files.newBufferedWriter(outputRoot.resolve("points.csv")))
        {
            writer.write("profile,attributes_per_entity,point_id,configuration_json,users_csv,resources_csv,user_attributes_csv,resource_attributes_csv,environment_attributes_csv,abac_rules_csv,rule_conditions_csv\n");
            for (int profile = 1; profile <= 6; profile++)
            {
                String base = String.format(Locale.ROOT, "../config-%03d", profile);
                for (int attributeCount : ATTRIBUTE_COUNTS[profile - 1])
                {
                    String variant = String.format(Locale.ROOT, "config-%03d/attributes-%03d", profile, attributeCount);
                    writer.write(String.format(Locale.ROOT,
                                               "C%d,%d,C%d-attributes-%03d,%s/configuration.json,%s/users.csv,%s/resources.csv,%s/user_attributes.csv,%s/resource_attributes.csv,%s/environment_attributes.csv,%s/abac_rules.csv,%s/rule_conditions.csv%n",
                                               profile, attributeCount, profile, attributeCount, variant, base, base,
                                               variant, variant, variant, variant, variant));
                }
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
            throw new IllegalArgumentException("Missing numeric field: " + field);
        return matcher.group(1);
    }
}
