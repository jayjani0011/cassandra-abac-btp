package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Produces user-count variants while keeping every other saved profile input unchanged. */
public final class AbacUserCountConfigurationGenerator
{
    private static final int[][] USER_COUNTS =
    {
    { 1, 2, 5, 10 },
    { 1, 5, 10, 15, 25 },
    { 1, 10, 25, 50 },
    { 1, 25, 50, 75, 100 },
    { 1, 50, 100, 150, 200, 250 },
    { 1, 100, 200, 300, 400, 500 }
    };

    private AbacUserCountConfigurationGenerator()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AbacUserCountConfigurationGenerator <source-configurations-directory> <user-count-output-directory>");

        Path sourceRoot = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        for (int profile = 1; profile <= 6; profile++)
        {
            String name = String.format(Locale.ROOT, "config-%03d", profile);
            generateProfile(sourceRoot.resolve(name).resolve("configuration.json"), outputRoot.resolve(name), USER_COUNTS[profile - 1]);
        }
        writeManifest(outputRoot);
    }

    private static void generateProfile(Path sourceConfiguration, Path outputDirectory, int[] userCounts) throws IOException
    {
        AbacConfigurationGenerator.Configuration source = readConfiguration(sourceConfiguration);
        for (int userCount : userCounts)
        {
            AbacConfigurationGenerator.Configuration variant = new AbacConfigurationGenerator.Configuration(
            source.seed, userCount, source.resources, source.attributesPerEntity, source.valuesPerAttribute,
            source.rules, source.conditionsPerRule, source.environmentConditionsPerRule);
            variant.validate();
            AbacConfigurationGenerator.GeneratedConfiguration generated = AbacConfigurationGenerator.generate(variant);
            Path directory = outputDirectory.resolve(String.format(Locale.ROOT, "users-%03d", userCount));
            Files.createDirectories(directory);
            AbacConfigurationGenerator.writeConfiguration(directory.resolve("configuration.json"), generated.configuration, generated.rules.size());
            AbacConfigurationGenerator.writeUsers(directory.resolve("users.csv"), userCount);
            AbacConfigurationGenerator.writeAttributes(directory.resolve("user_attributes.csv"), generated.userValues,
                                                        AbacConfigurationGenerator.EntityType.USER);
        }
    }

    private static void writeManifest(Path outputRoot) throws IOException
    {
        try (var writer = Files.newBufferedWriter(outputRoot.resolve("points.csv")))
        {
            writer.write("profile,users,point_id,configuration_json,users_csv,resources_csv,user_attributes_csv,resource_attributes_csv,environment_attributes_csv,abac_rules_csv,rule_conditions_csv\n");
            for (int profile = 1; profile <= 6; profile++)
            {
                String base = String.format(Locale.ROOT, "../config-%03d", profile);
                for (int userCount : USER_COUNTS[profile - 1])
                {
                    String variant = String.format(Locale.ROOT, "config-%03d/users-%03d", profile, userCount);
                    writer.write(String.format(Locale.ROOT,
                                               "C%d,%d,C%d-users-%03d,%s/configuration.json,%s/users.csv,%s/resources.csv,%s/user_attributes.csv,%s/resource_attributes.csv,%s/environment_attributes.csv,%s/abac_rules.csv,%s/rule_conditions.csv%n",
                                               profile, userCount, profile, userCount, variant, variant, base,
                                               variant, base, base, base, base));
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
