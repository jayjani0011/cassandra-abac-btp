package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces resource-count variants while keeping every other saved profile input unchanged. */
public final class AbacResourceCountConfigurationGenerator
{
    private static final int[][] RESOURCE_COUNTS =
    {
    { 10, 20, 50, 100 },
    { 25, 50, 100, 150, 250 },
    { 50, 100, 250, 500 },
    { 100, 250, 500, 750, 1000 },
    { 250, 500, 1000, 1500, 2000, 2500 },
    { 500, 1000, 2000, 3000, 4000, 5000 }
    };

    private AbacResourceCountConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AbacResourceCountConfigurationGenerator <source-configurations-directory> <resource-count-output-directory>");

        Path sourceRoot = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        for (int profile = 1; profile <= 6; profile++)
        {
            String name = String.format(Locale.ROOT, "config-%03d", profile);
            generateProfile(sourceRoot.resolve(name).resolve("configuration.json"), outputRoot.resolve(name), RESOURCE_COUNTS[profile - 1]);
        }
        writeManifest(outputRoot);
    }

    private static void generateProfile(Path sourceConfiguration, Path outputDirectory, int[] resourceCounts) throws IOException
    {
        AbacConfigurationGenerator.Configuration source = readConfiguration(sourceConfiguration);
        AbacConfigurationGenerator.GeneratedConfiguration anchoredSource = withAnchoredFirstRule(AbacConfigurationGenerator.generate(source));
        for (int resourceCount : resourceCounts)
        {
            AbacConfigurationGenerator.Configuration variant = new AbacConfigurationGenerator.Configuration(
            source.seed, source.users, resourceCount, source.attributesPerEntity, source.valuesPerAttribute,
            source.rules, source.conditionsPerRule, source.environmentConditionsPerRule);
            variant.validate();
            AbacConfigurationGenerator.GeneratedConfiguration generated = AbacConfigurationGenerator.generate(variant);
            Path directory = outputDirectory.resolve(String.format(Locale.ROOT, "resources-%04d", resourceCount));
            Files.createDirectories(directory);
            AbacConfigurationGenerator.writeConfiguration(directory.resolve("configuration.json"), generated.configuration, generated.rules.size());
            AbacConfigurationGenerator.writeResources(directory.resolve("resources.csv"), resourceCount);
            AbacConfigurationGenerator.writeAttributes(directory.resolve("resource_attributes.csv"), generated.resourceValues,
                                                        AbacConfigurationGenerator.EntityType.RESOURCE);
            AbacConfigurationGenerator.writeRules(directory.resolve("abac_rules.csv"), anchoredSource.rules);
            AbacConfigurationGenerator.writeRuleConditions(directory.resolve("rule_conditions.csv"), anchoredSource.rules);
        }
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

    private static void writeManifest(Path outputRoot) throws IOException
    {
        try (var writer = Files.newBufferedWriter(outputRoot.resolve("points.csv")))
        {
            writer.write("profile,resources,point_id,configuration_json,users_csv,resources_csv,user_attributes_csv,resource_attributes_csv,environment_attributes_csv,abac_rules_csv,rule_conditions_csv\n");
            for (int profile = 1; profile <= 6; profile++)
            {
                String base = String.format(Locale.ROOT, "../config-%03d", profile);
                for (int resourceCount : RESOURCE_COUNTS[profile - 1])
                {
                    String variant = String.format(Locale.ROOT, "config-%03d/resources-%04d", profile, resourceCount);
                    writer.write(String.format(Locale.ROOT,
                                               "C%d,%d,C%d-resources-%04d,%s/configuration.json,%s/users.csv,%s/resources.csv,%s/user_attributes.csv,%s/resource_attributes.csv,%s/environment_attributes.csv,%s/abac_rules.csv,%s/rule_conditions.csv%n",
                                               profile, resourceCount, profile, resourceCount, variant, base, variant,
                                               base, variant, base, variant, variant));
                }
            }
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
