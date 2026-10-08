package org.apache.cassandra.service;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A singleton manager for discovering and accessing {@link EnvironmentAttributeProvider}s.
 * This class loads all available providers from the classpath using the {@link ServiceLoader} mechanism.
 */
public final class EnvironmentAttributeManager
{
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentAttributeManager.class);

    private static final EnvironmentAttributeManager instance = new EnvironmentAttributeManager();

    private final Map<String, EnvironmentAttributeProvider> providers;
    private final Map<String, EnvironmentAttributeProvider> registeredProviders = new ConcurrentHashMap<>();

    private EnvironmentAttributeManager()
    {
        logger.info("Initializing EnvironmentAttributeManager...");
        this.providers = ServiceLoader.load(EnvironmentAttributeProvider.class)
                                      .stream()
                                      .map(ServiceLoader.Provider::get)
                                      .collect(Collectors.toMap(EnvironmentAttributeProvider::getAttributeName,
                                                                Function.identity(),
                                                                (p1, p2) -> {
                                                                    logger.warn("Duplicate EnvironmentAttributeProvider found for name '{}'. Using {} over {}.",
                                                                                p1.getAttributeName(), p1.getClass().getName(), p2.getClass().getName());
                                                                    return p1;
                                                                }));
        logger.info("Discovered and registered {} environment attribute provider(s).", providers.size());
    }

    /**
     * @return The singleton instance of the EnvironmentAttributeManager.
     */
    public static EnvironmentAttributeManager getInstance()
    {
        return instance;
    }

    /**
     * Retrieves the current value for a given attribute.
     * The existing authorization checker can call this method to resolve attribute values from rules.
     *
     * @param attributeName The name of the attribute to resolve (e.g., "day_of_the_week").
     * @return The current value of the attribute as a String, or null if no provider is found for the given name.
     */
    public String getAttributeValue(String attributeName)
    {
        EnvironmentAttributeProvider provider = registeredProviders.get(attributeName);
        if (provider == null)
            provider = providers.get(attributeName);
        if (provider == null)
        {
            logger.trace("No EnvironmentAttributeProvider found for attribute name '{}'", attributeName);
            return null;
        }
        return provider.getValue();
    }

    public void registerProvider(EnvironmentAttributeProvider provider)
    {
        registeredProviders.put(provider.getAttributeName(), provider);
    }
}
