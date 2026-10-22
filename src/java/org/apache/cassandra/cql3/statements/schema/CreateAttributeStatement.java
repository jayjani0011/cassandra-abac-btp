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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.cassandra.audit.AuditLogContext;
import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.auth.CateeAdministration;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;

import org.apache.cassandra.cql3.terms.Term;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.CQL3Type;
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

public class CreateAttributeStatement extends AlterSchemaStatement
{
    private final String attributeName;
    private final CQL3Type.Raw type;
    private final List<Term.Raw> values;
    private final boolean ifNotExists;

    public CreateAttributeStatement(String attributeName, CQL3Type.Raw type, List<Term.Raw> values, boolean ifNotExists)
    {
        super("system_auth");
        this.attributeName = attributeName;
        this.type = type;
        this.values = values;
        this.ifNotExists = ifNotExists;
    }

    public void authorize(ClientState state) throws RequestValidationException
    {
        CateeAdministration.ensurePermission(state, Permission.CREATE_ATTRIBUTE, org.apache.cassandra.auth.RoleResource.root());
    }

    @Override
    public void validate(ClientState state) throws RequestValidationException
    {
    }

    @Override
    public ResultMessage execute(QueryState state, QueryOptions options, Dispatcher.RequestTime requestTime) throws RequestExecutionException, RequestValidationException
    {
        if (ifNotExists) {
            String checkQuery = String.format("SELECT attribute_name FROM system_auth.attribute_definitions WHERE attribute_name = '%s'", attributeName);
            if (!QueryProcessor.executeInternal(checkQuery).isEmpty()) {
                return null;
            }
        }

        Set<String> allowedValues = null;
        if (values != null)
        {
            allowedValues = values.stream().map(Term.Raw::getText).collect(Collectors.toSet());
        }

        String insertQuery = "INSERT INTO system_auth.attribute_definitions (attribute_name, attribute_type, allowed_values) VALUES (?, ?, ?)";
        QueryProcessor.executeInternal(insertQuery, attributeName, type.toString(), allowedValues);

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
        return new Event.SchemaChange(Event.SchemaChange.Change.UPDATED, Event.SchemaChange.Target.TABLE, keyspace(), "attribute_definitions");
    }

    public static final class Raw extends CQLStatement.Raw
    {
        private final ColumnIdentifier name;
        private final CQL3Type.Raw type;
        private final List<Term.Raw> values;
        private final boolean ifNotExists;

        public Raw(ColumnIdentifier name, CQL3Type.Raw type, List<Term.Raw> values, boolean ifNotExists)
        {
            this.name = name;
            this.type = type;
            this.values = values;
            this.ifNotExists = ifNotExists;
        }

        public CreateAttributeStatement prepare(ClientState state)
        {
            String attributeName = name.toString();
            return new CreateAttributeStatement(attributeName, type, values, ifNotExists);
        }
    }
}