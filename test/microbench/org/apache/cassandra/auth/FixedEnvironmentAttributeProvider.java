package org.apache.cassandra.auth;

import org.apache.cassandra.service.EnvironmentAttributeProvider;

final class FixedEnvironmentAttributeProvider implements EnvironmentAttributeProvider
{
    private final String attributeName;
    private final String value;

    FixedEnvironmentAttributeProvider(String attributeName, String value)
    {
        this.attributeName = attributeName;
        this.value = value;
    }

    @Override
    public String getAttributeName()
    {
        return attributeName;
    }

    @Override
    public String getValue()
    {
        return value;
    }
}
