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

import org.apache.cassandra.auth.AuthKeyspace;
import org.apache.cassandra.auth.AttributeHierarchyManager;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.terms.Constants;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.terms.Term;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.schema.SchemaConstants;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.commons.lang3.StringUtils;

import org.apache.cassandra.audit.AuditLogContext;
import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.auth.CateeAdministration;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.schema.Keyspaces;
import org.apache.cassandra.schema.Keyspaces.KeyspacesDiff;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.tcm.ClusterMetadata;
import org.apache.cassandra.transport.Dispatcher;
import org.apache.cassandra.transport.Event;

import static java.lang.String.format;

public class CreateHierarchyEdgeStatement extends AlterSchemaStatement
{
    private final String attributeName;
    private final String parentValue;
    private final String childValue;

    public CreateHierarchyEdgeStatement(String attributeName, String parentValue, String childValue)
    {
        super("system_auth");
        this.attributeName = attributeName;
        this.parentValue = parentValue;
        this.childValue = childValue;
    }

        public void authorize(ClientState state) throws RequestValidationException
    {
        CateeAdministration.ensurePermission(state, Permission.ALTER_ATTRIBUTE, org.apache.cassandra.auth.RoleResource.root());
    }

    @Override
    public void validate(ClientState state) throws RequestValidationException
    {

        if (attributeName == null || attributeName.toString().isEmpty())
            throw new InvalidRequestException("Attribute name cannot be empty.");
        if (parentValue == null || parentValue.isEmpty())
            throw new InvalidRequestException("Parent value cannot be empty.");
        if (childValue == null || childValue.isEmpty())
            throw new InvalidRequestException("Child value cannot be empty.");

        // Check if the new edge creates a cycle
        if (AttributeHierarchyManager.instance.check(attributeName.toString(), childValue, parentValue))
            throw new InvalidRequestException("Creates a hierarchy cycle.");
    }

    @Override
    public ResultMessage execute(QueryState state, QueryOptions options, Dispatcher.RequestTime requestTime) throws RequestExecutionException, RequestValidationException
    {

        // Check if the edge already exists to prevent duplicates. If it does, do nothing.
        // Use select command to check

        // This should ideally be done in a LOGGED BATCH with the metadata update for atomicity.
        String edgeInsert = format("INSERT INTO %s.%s (attribute_name, parent, child) VALUES ('%s', '%s', '%s')",
                                  SchemaConstants.AUTH_KEYSPACE_NAME,
                                  AuthKeyspace.ATTRIBUTE_EDGES,
                                  escape(attributeName.toString()),
                                  escape(parentValue),
                                  escape(childValue));
        QueryProcessor.executeInternal(edgeInsert);

        // 2. Update the last_modified timestamp in the metadata table.
        String metaUpdate = format("UPDATE %s.%s SET last_modified = toTimestamp(now()) WHERE key = 'singleton'",
                                  SchemaConstants.AUTH_KEYSPACE_NAME,
                                  AuthKeyspace.HIERARCHY_METADATA);
        QueryProcessor.executeInternal(metaUpdate);

        // 3. Update the in-memory cache.
        AttributeHierarchyManager.instance.addEdge(attributeName.toString(), parentValue, childValue);

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
        return new AuditLogContext(AuditLogEntryType.CREATE_ROLE, keyspace(), attributeName);
    }

    Event.SchemaChange schemaChangeEvent(KeyspacesDiff diff)
    {
        return new Event.SchemaChange(Event.SchemaChange.Change.UPDATED, Event.SchemaChange.Target.TABLE, keyspace(), "attribute_hierarchy_edges");
    }

    public static final class Raw extends CQLStatement.Raw
    {
        private final ColumnIdentifier attributeName;
        private final String parentValue;
        private final String childValue;

        public Raw(ColumnIdentifier attributeName, Term.Raw parentValue, Term.Raw childValue)
        {
            this.attributeName = attributeName;
            // Use Constants.Literal for precision, as that's what the parser returns for string literals.
            if (!(parentValue instanceof Constants.Literal) || !(childValue instanceof Constants.Literal)) {
                throw new InvalidRequestException("Hierarchy parent and child values must be string literals.");
            }
            this.parentValue = ((Constants.Literal) parentValue).getRawText();
            this.childValue = ((Constants.Literal) childValue).getRawText();
        }

        public CreateHierarchyEdgeStatement prepare(ClientState state)
        {
            String name = attributeName.toString();
            return new CreateHierarchyEdgeStatement(name, parentValue, childValue);
        }
    }

    private String escape(String name)
    {
        return StringUtils.replace(name, "'", "''");
    }
}
