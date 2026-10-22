/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements.schema;

import org.apache.cassandra.audit.AuditLogContext;
import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.auth.IResource;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.auth.CateeAdministration;
import org.apache.cassandra.cql3.RoleName;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.terms.Term;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.schema.Keyspaces;
import org.apache.cassandra.schema.Keyspaces.KeyspacesDiff;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.tcm.ClusterMetadata;
import org.apache.cassandra.transport.Dispatcher;
import org.apache.cassandra.transport.Event;
import org.apache.cassandra.transport.messages.ResultMessage;

public class GrantAttributeStatement extends AlterSchemaStatement
{
    private final String attributeType; // "USER" or "RESOURCE"
    private final String name;
    private final IResource resource;
    private final String attributeName;
    private final Term.Raw attributeValue;

    public GrantAttributeStatement(String attributeType, String name, IResource resource, String attributeName, Term.Raw attributeValue)
    {
        super("system_auth");
        this.attributeType = attributeType;
        this.name = name;
        this.resource = resource;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
    }

    @Override
    public void authorize(ClientState state) throws RequestValidationException
    {
        if (attributeType.equalsIgnoreCase("USER"))
        {
            CateeAdministration.ensurePermission(state, Permission.ASSIGN_USER_ATTRIBUTE, org.apache.cassandra.auth.RoleResource.role(name));
        }
        else // RESOURCE
        {
            CateeAdministration.ensurePermission(state, Permission.ASSIGN_RESOURCE_ATTRIBUTE, resource);
        }
    }

    @Override
    public void validate(ClientState state) throws RequestValidationException
    {
    }

    @Override
    public ResultMessage execute(QueryState state, QueryOptions options, Dispatcher.RequestTime requestTime) throws RequestExecutionException, RequestValidationException
    {
        String tableName = attributeType.equalsIgnoreCase("USER") ? "user_attribute_values" : "resource_attribute_values";
        String columnName = attributeType.equalsIgnoreCase("USER") ? "user_name" : "resource_name";

        String insertQuery = String.format("INSERT INTO system_auth.%s (%s, attribute_name, attribute_value) VALUES (?, ?, ?)", tableName, columnName);
        String valueToStore = attributeValue.getText();
        if (valueToStore != null && valueToStore.length() > 1 && valueToStore.startsWith("'") && valueToStore.endsWith("'"))
        {
            valueToStore = valueToStore.substring(1, valueToStore.length() - 1);
        }
        QueryProcessor.executeInternal(insertQuery, name, attributeName, valueToStore);

        return new ResultMessage.SchemaChange(schemaChangeEvent(null));
    }

    @Override
    public Keyspaces apply(ClusterMetadata metadata)
    {
        return metadata.schema.getKeyspaces();
    }

    @Override
    public AuditLogContext getAuditLogContext()
    {
        return new AuditLogContext(AuditLogEntryType.GRANT, keyspace(), name);
    }

    Event.SchemaChange schemaChangeEvent(KeyspacesDiff diff)
    {
        String tableName = attributeType.equalsIgnoreCase("USER") ? "user_attribute_values" : "resource_attribute_values";
        return new Event.SchemaChange(Event.SchemaChange.Change.UPDATED, Event.SchemaChange.Target.TABLE, keyspace(), tableName);
    }

    public static final class Raw extends CQLStatement.Raw
    {
        private final String attributeType; // "USER" or "RESOURCE"
        private RoleName user;
        private IResource resource;
        private final ColumnIdentifier attributeName;
        private final Term.Raw attributeValue;

        public Raw(RoleName user, ColumnIdentifier attributeName, Term.Raw attributeValue)
        {
            this.attributeType = "USER";
            this.user = user;
            this.attributeName = attributeName;
            this.attributeValue = attributeValue;
        }

        public Raw(IResource resource, ColumnIdentifier attributeName, Term.Raw attributeValue)
        {
            this.attributeType = "RESOURCE";
            this.resource = resource;
            this.attributeName = attributeName;
            this.attributeValue = attributeValue;
        }

        public GrantAttributeStatement prepare(ClientState state)
        {
            String name = attributeType.equals("USER") ? user.getName() : resource.getName();
            IResource targetResource = attributeType.equals("USER") ? null : resource;
            return new GrantAttributeStatement(attributeType, name, targetResource, attributeName.toString(), attributeValue);
        }
    }
}
