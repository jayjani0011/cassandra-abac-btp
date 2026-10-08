package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.service.EnvironmentAttributeManager;

public final class AbacConfigurationLoader
{
    private static final List<String> REQUIRED_FILES = List.of("configuration.json",
                                                               "users.csv",
                                                               "resources.csv",
                                                               "user_attributes.csv",
                                                               "resource_attributes.csv",
                                                               "environment_attributes.csv",
                                                               "abac_rules.csv",
                                                               "rule_conditions.csv");

    private AbacConfigurationLoader()
    {
    }

    public static Configuration readConfiguration(Path configurationDirectory) throws IOException
    {
        if (!Files.isDirectory(configurationDirectory))
            throw new IllegalArgumentException("Configuration directory does not exist: " + configurationDirectory);

        for (String fileName : REQUIRED_FILES)
        {
            Path file = configurationDirectory.resolve(fileName);
            if (!Files.isRegularFile(file))
                throw new IllegalArgumentException("Missing required configuration file: " + file);
        }

        String json = Files.readString(configurationDirectory.resolve("configuration.json"));
        return new Configuration(configurationDirectory,
                                 stringField(json, "generator_version"),
                                 longField(json, "seed"),
                                 intField(json, "users"),
                                 intField(json, "resources"),
                                 intField(json, "attributes_per_entity"),
                                 intField(json, "values_per_attribute"),
                                 intField(json, "rules"),
                                 intField(json, "conditions_per_rule"),
                                 intField(json, "environment_conditions_per_rule"),
                                 stringField(json, "rule_permission"));
    }

    public static Dataset readDataset(Path configurationDirectory) throws IOException
    {
        Configuration configuration = readConfiguration(configurationDirectory);

        List<String[]> userRows = readCsv(configuration.directory.resolve("users.csv"), "user_name", 1);
        List<String[]> resourceRows = readCsv(configuration.directory.resolve("resources.csv"), "resource_name", 1);
        List<String[]> userAttributeRows = readCsv(configuration.directory.resolve("user_attributes.csv"),
                                                   "user_name,attribute_name,attribute_value", 3);
        List<String[]> resourceAttributeRows = readCsv(configuration.directory.resolve("resource_attributes.csv"),
                                                       "resource_name,attribute_name,attribute_value", 3);
        List<String[]> environmentAttributeRows = readCsv(configuration.directory.resolve("environment_attributes.csv"),
                                                          "attribute_name,attribute_value", 2);
        List<String[]> ruleRows = readCsv(configuration.directory.resolve("abac_rules.csv"),
                                          "rule_name,effect,permission", 3);
        List<String[]> conditionRows = readCsv(configuration.directory.resolve("rule_conditions.csv"),
                                               "rule_name,entity_type,attribute_name,required_value", 4);

        Dataset dataset = new Dataset(configuration,
                                      names(userRows),
                                      names(resourceRows),
                                      attributeAssignments(userAttributeRows),
                                      attributeAssignments(resourceAttributeRows),
                                      environmentAttributes(environmentAttributeRows),
                                      rules(ruleRows),
                                      ruleConditions(conditionRows),
                                      conditionsByRule(ruleRows, conditionRows, configuration.conditionsPerRule,
                                                       configuration.environmentConditionsPerRule));
        dataset.validateRowCounts();
        return dataset;
    }

    public static Dataset load(Path configurationDirectory) throws IOException
    {
        Dataset dataset = readDataset(configurationDirectory);
        load(dataset);
        return dataset;
    }

    public static void load(Dataset dataset)
    {
        registerEnvironmentAttributes(dataset);
        clearAbacData();

        for (AttributeAssignment assignment : dataset.userAttributes)
            insertAttribute("user_attribute_values", "user_name", assignment);

        for (AttributeAssignment assignment : dataset.resourceAttributes)
            insertAttribute("resource_attribute_values", "resource_name", assignment);

        for (Rule rule : dataset.rules)
        {
            RuleConditions conditions = dataset.conditionsByRule.get(rule.name);
            String query = String.format("INSERT INTO system_auth.abac_rules " +
                                         "(rule_name, permissions, user_attribute_conditions, resource_attribute_conditions, " +
                                         "environment_attribute_conditions, effect) VALUES ('%s', {'%s'}, %s, %s, %s, '%s')",
                                         escape(rule.name),
                                         escape(rule.permission),
                                         cqlMap(conditions.user),
                                         cqlMap(conditions.resource),
                                         cqlMap(conditions.environment),
                                         escape(rule.effect));
            QueryProcessor.executeInternal(query);
        }
    }

    static void registerEnvironmentAttributes(Dataset dataset)
    {
        EnvironmentAttributeManager manager = EnvironmentAttributeManager.getInstance();
        for (Map.Entry<String, String> attribute : dataset.environmentAttributes.entrySet())
            manager.registerProvider(new FixedEnvironmentAttributeProvider(attribute.getKey(), attribute.getValue()));
    }

    private static void clearAbacData()
    {
        QueryProcessor.executeInternal("TRUNCATE system_auth.user_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.resource_attribute_values");
        QueryProcessor.executeInternal("TRUNCATE system_auth.abac_rules");
    }

    private static void insertAttribute(String table, String entityColumn, AttributeAssignment assignment)
    {
        String query = String.format("INSERT INTO system_auth.%s (%s, attribute_name, attribute_value) VALUES ('%s', '%s', '%s')",
                                     table,
                                     entityColumn,
                                     escape(assignment.entityName),
                                     escape(assignment.attributeName),
                                     escape(assignment.attributeValue));
        QueryProcessor.executeInternal(query);
    }

    private static String cqlMap(Map<String, String> values)
    {
        StringBuilder result = new StringBuilder("{");
        for (Map.Entry<String, String> entry : values.entrySet())
        {
            if (result.length() > 1)
                result.append(", ");
            result.append('\'').append(escape(entry.getKey())).append("':'").append(escape(entry.getValue())).append('\'');
        }
        return result.append('}').toString();
    }

    private static String escape(String value)
    {
        return value.replace("'", "''");
    }

    private static List<String[]> readCsv(Path file, String expectedHeader, int columns) throws IOException
    {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || !lines.get(0).equals(expectedHeader))
            throw new IllegalArgumentException("Unexpected CSV header in " + file);

        List<String[]> rows = new ArrayList<>(lines.size() - 1);
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++)
        {
            String[] row = lines.get(lineNumber).split(",", -1);
            if (row.length != columns)
                throw new IllegalArgumentException("Expected " + columns + " columns in " + file + " at line " + (lineNumber + 1));
            rows.add(row);
        }
        return rows;
    }

    private static List<String> names(List<String[]> rows)
    {
        List<String> names = new ArrayList<>(rows.size());
        for (String[] row : rows)
            names.add(row[0]);
        return names;
    }

    private static List<AttributeAssignment> attributeAssignments(List<String[]> rows)
    {
        List<AttributeAssignment> assignments = new ArrayList<>(rows.size());
        for (String[] row : rows)
            assignments.add(new AttributeAssignment(row[0], row[1], row[2]));
        return assignments;
    }

    private static List<Rule> rules(List<String[]> rows)
    {
        List<Rule> rules = new ArrayList<>(rows.size());
        for (String[] row : rows)
            rules.add(new Rule(row[0], row[1], row[2]));
        return rules;
    }

    private static Map<String, String> environmentAttributes(List<String[]> rows)
    {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String[] row : rows)
        {
            if (attributes.put(row[0], row[1]) != null)
                throw new IllegalArgumentException("Duplicate environment attribute: " + row[0]);
        }
        return attributes;
    }

    private static List<RuleCondition> ruleConditions(List<String[]> rows)
    {
        List<RuleCondition> conditions = new ArrayList<>(rows.size());
        for (String[] row : rows)
            conditions.add(new RuleCondition(row[0], row[1], row[2], row[3]));
        return conditions;
    }

    private static Map<String, RuleConditions> conditionsByRule(List<String[]> ruleRows,
                                                                 List<String[]> conditionRows,
                                                                 int conditionsPerRule,
                                                                 int environmentConditionsPerRule)
    {
        Map<String, RuleConditionBuilder> builders = new LinkedHashMap<>();
        for (String[] ruleRow : ruleRows)
        {
            if (builders.put(ruleRow[0], new RuleConditionBuilder()) != null)
                throw new IllegalArgumentException("Duplicate rule name in abac_rules.csv: " + ruleRow[0]);
        }

        for (String[] conditionRow : conditionRows)
        {
            RuleConditionBuilder builder = builders.get(conditionRow[0]);
            if (builder == null)
                throw new IllegalArgumentException("Condition refers to an unknown rule: " + conditionRow[0]);

            Map<String, String> conditions = conditionsForEntityType(builder, conditionRow[1], conditionRow[0]);
            if (conditions.put(conditionRow[2], conditionRow[3]) != null)
                throw new IllegalArgumentException("Duplicate condition attribute for rule " + conditionRow[0] + ": " + conditionRow[2]);
        }

        Map<String, RuleConditions> groupedConditions = new LinkedHashMap<>();
        for (Map.Entry<String, RuleConditionBuilder> entry : builders.entrySet())
        {
            RuleConditionBuilder builder = entry.getValue();
            int userResourceConditionCount = builder.userConditions.size() + builder.resourceConditions.size();
            if (userResourceConditionCount != conditionsPerRule)
                throw new IllegalArgumentException("Expected " + conditionsPerRule + " user/resource conditions for rule " + entry.getKey() + ", found " + userResourceConditionCount);
            if (builder.environmentConditions.size() != environmentConditionsPerRule)
                throw new IllegalArgumentException("Expected " + environmentConditionsPerRule + " environment conditions for rule " + entry.getKey() + ", found " + builder.environmentConditions.size());
            groupedConditions.put(entry.getKey(), new RuleConditions(builder.userConditions, builder.resourceConditions,
                                                                      builder.environmentConditions));
        }
        return groupedConditions;
    }

    private static Map<String, String> conditionsForEntityType(RuleConditionBuilder builder, String entityType, String ruleName)
    {
        if (entityType.equals("user"))
            return builder.userConditions;
        if (entityType.equals("resource"))
            return builder.resourceConditions;
        if (entityType.equals("environment"))
            return builder.environmentConditions;
        throw new IllegalArgumentException("Unknown entity type for rule " + ruleName + ": " + entityType);
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
            throw new IllegalArgumentException("Missing numeric field in configuration.json: " + field);
        return matcher.group(1);
    }

    private static String stringField(String json, String field)
    {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json);
        if (!matcher.find())
            throw new IllegalArgumentException("Missing string field in configuration.json: " + field);
        return matcher.group(1);
    }

    public static final class Configuration
    {
        public final Path directory;
        public final String generatorVersion;
        public final long seed;
        public final int users;
        public final int resources;
        public final int attributesPerEntity;
        public final int valuesPerAttribute;
        public final int rules;
        public final int conditionsPerRule;
        public final int environmentConditionsPerRule;
        public final String rulePermission;

        private Configuration(Path directory, String generatorVersion, long seed, int users, int resources,
                              int attributesPerEntity, int valuesPerAttribute, int rules,
                              int conditionsPerRule, int environmentConditionsPerRule, String rulePermission)
        {
            this.directory = directory;
            this.generatorVersion = generatorVersion;
            this.seed = seed;
            this.users = users;
            this.resources = resources;
            this.attributesPerEntity = attributesPerEntity;
            this.valuesPerAttribute = valuesPerAttribute;
            this.rules = rules;
            this.conditionsPerRule = conditionsPerRule;
            this.environmentConditionsPerRule = environmentConditionsPerRule;
            this.rulePermission = rulePermission;
        }
    }

    public static final class Dataset
    {
        public final Configuration configuration;
        public final List<String> users;
        public final List<String> resources;
        public final List<AttributeAssignment> userAttributes;
        public final List<AttributeAssignment> resourceAttributes;
        public final Map<String, String> environmentAttributes;
        public final List<Rule> rules;
        public final List<RuleCondition> ruleConditions;
        public final Map<String, RuleConditions> conditionsByRule;

        private Dataset(Configuration configuration, List<String> users, List<String> resources,
                        List<AttributeAssignment> userAttributes, List<AttributeAssignment> resourceAttributes,
                        Map<String, String> environmentAttributes,
                        List<Rule> rules, List<RuleCondition> ruleConditions,
                        Map<String, RuleConditions> conditionsByRule)
        {
            this.configuration = configuration;
            this.users = List.copyOf(users);
            this.resources = List.copyOf(resources);
            this.userAttributes = List.copyOf(userAttributes);
            this.resourceAttributes = List.copyOf(resourceAttributes);
            this.environmentAttributes = Map.copyOf(environmentAttributes);
            this.rules = List.copyOf(rules);
            this.ruleConditions = List.copyOf(ruleConditions);
            this.conditionsByRule = Map.copyOf(conditionsByRule);
        }

        private void validateRowCounts()
        {
            requireCount("users.csv", configuration.users, users.size());
            requireCount("resources.csv", configuration.resources, resources.size());
            requireCount("user_attributes.csv", configuration.users * configuration.attributesPerEntity, userAttributes.size());
            requireCount("resource_attributes.csv", configuration.resources * configuration.attributesPerEntity, resourceAttributes.size());
            requireCount("environment_attributes.csv", configuration.environmentConditionsPerRule, environmentAttributes.size());
            requireCount("abac_rules.csv", configuration.rules, rules.size());
            requireCount("rule_conditions.csv", configuration.rules * (configuration.conditionsPerRule + configuration.environmentConditionsPerRule), ruleConditions.size());
        }

        private static void requireCount(String fileName, int expected, int actual)
        {
            if (expected != actual)
                throw new IllegalArgumentException("Expected " + expected + " rows in " + fileName + ", found " + actual);
        }
    }

    public static final class AttributeAssignment
    {
        public final String entityName;
        public final String attributeName;
        public final String attributeValue;

        private AttributeAssignment(String entityName, String attributeName, String attributeValue)
        {
            this.entityName = entityName;
            this.attributeName = attributeName;
            this.attributeValue = attributeValue;
        }
    }

    public static final class Rule
    {
        public final String name;
        public final String effect;
        public final String permission;

        private Rule(String name, String effect, String permission)
        {
            this.name = name;
            this.effect = effect;
            this.permission = permission;
        }
    }

    public static final class RuleCondition
    {
        public final String ruleName;
        public final String entityType;
        public final String attributeName;
        public final String requiredValue;

        private RuleCondition(String ruleName, String entityType, String attributeName, String requiredValue)
        {
            this.ruleName = ruleName;
            this.entityType = entityType;
            this.attributeName = attributeName;
            this.requiredValue = requiredValue;
        }
    }

    public static final class RuleConditions
    {
        public final Map<String, String> user;
        public final Map<String, String> resource;
        public final Map<String, String> environment;

        private RuleConditions(Map<String, String> user, Map<String, String> resource,
                               Map<String, String> environment)
        {
            this.user = Map.copyOf(user);
            this.resource = Map.copyOf(resource);
            this.environment = Map.copyOf(environment);
        }
    }

    private static final class RuleConditionBuilder
    {
        private final Map<String, String> userConditions = new LinkedHashMap<>();
        private final Map<String, String> resourceConditions = new LinkedHashMap<>();
        private final Map<String, String> environmentConditions = new LinkedHashMap<>();
    }
}
