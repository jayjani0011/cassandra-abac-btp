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

import java.util.Map;
import org.apache.cassandra.auth.CassandraAuthorizer;
import org.apache.cassandra.config.DatabaseDescriptor;
import java.util.Set;

import org.apache.cassandra.audit.AuditLogContext;
import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.terms.Maps;
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

public class CreateRuleStatement extends AlterSchemaStatement
{
    private final String ruleName;
    private final Set<Permission> permissions;
    private final Maps.Literal userConditions;
    private final Maps.Literal resourceConditions;
    private final Maps.Literal envConditions;
    private final String effect;
    private final boolean ifNotExists;

    public CreateRuleStatement(String ruleName,
                               Set<Permission> permissions,
                               Maps.Literal userConditions,
                               Maps.Literal resourceConditions,
                               Maps.Literal envConditions,
                               String effect,
                               boolean ifNotExists)
    {
        super("system_auth");
        this.ruleName = ruleName;
        this.permissions = permissions;
        this.userConditions = userConditions;
        this.resourceConditions = resourceConditions;
        this.envConditions = envConditions;
        this.effect = effect;
        this.ifNotExists = ifNotExists;
    }

    public void authorize(ClientState state) throws RequestValidationException
    {
        state.ensurePermission(Permission.CREATE_POLICY, org.apache.cassandra.auth.RoleResource.root());
    }

    @Override
    public void validate(ClientState state) throws RequestValidationException
    {
        if (DatabaseDescriptor.getAuthorizer() instanceof CassandraAuthorizer)
            ((CassandraAuthorizer) DatabaseDescriptor.getAuthorizer()).validateCateeRuleEnvironment(convert(envConditions));
    }

    private static String stripQuotes(String text)
    {
        if (text != null && text.length() > 1 && text.startsWith("'") && text.endsWith("'"))
            return text.substring(1, text.length() - 1);
        return text;
    }

    private static Map<String, String> convert(Maps.Literal map)
    {
        if (map == null)
            return null;
        Map<String, String> result = new java.util.HashMap<>();
        for (org.apache.cassandra.utils.Pair<Term.Raw, Term.Raw> entry : map.entries)
        {
            result.put(stripQuotes(entry.left.getText()), stripQuotes(entry.right.getText()));
        }
        return result;
    }

    @Override
    public ResultMessage execute(QueryState state, QueryOptions options, Dispatcher.RequestTime requestTime) throws RequestExecutionException, RequestValidationException
    {
        String insertQuery = "INSERT INTO system_auth.abac_rules (rule_name, permissions, user_attribute_conditions, resource_attribute_conditions, environment_attribute_conditions, effect) VALUES (?, ?, ?, ?, ?, ?)";
        if (ifNotExists) {
            insertQuery += " IF NOT EXISTS";
        }

        Map<String, String> userConds = convert(userConditions);
        Map<String, String> resourceConds = convert(resourceConditions);
        Map<String, String> envConds = convert(envConditions);
        validate(state.getClientState());

        Set<String> permissionNames = new java.util.HashSet<>();
        for (Permission p : permissions)
        {
            permissionNames.add(p.name());
        }

        QueryProcessor.executeInternal(insertQuery, ruleName, permissionNames, userConds, resourceConds, envConds, effect);

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
        return new AuditLogContext(AuditLogEntryType.CREATE_ROLE, keyspace(), ruleName);
    }

    Event.SchemaChange schemaChangeEvent(KeyspacesDiff diff)
    {
        return new Event.SchemaChange(Event.SchemaChange.Change.UPDATED, Event.SchemaChange.Target.TABLE, keyspace(), "abac_rules");
    }

    public static final class Raw extends CQLStatement.Raw
    {
        private final ColumnIdentifier ruleName;
        private final Set<Permission> permissions;
        private final Maps.Literal userConditions;
        private final Maps.Literal resourceConditions;
        private final Maps.Literal envConditions;
        private final String effect;
        private final boolean ifNotExists;

        public Raw(ColumnIdentifier ruleName,
                   Set<Permission> permissions,
                   Maps.Literal userConditions,
                   Maps.Literal resourceConditions,
                   Maps.Literal envConditions,
                   String effect,
                   boolean ifNotExists)
        {
            this.ruleName = ruleName;
            this.permissions = permissions;
            this.userConditions = userConditions;
            this.resourceConditions = resourceConditions;
            this.envConditions = envConditions;
            this.effect = effect;
            this.ifNotExists = ifNotExists;
        }

        public CreateRuleStatement prepare(ClientState state)
        {
            return new CreateRuleStatement(ruleName.toString(),
                                           permissions,
                                           userConditions,
                                           resourceConditions,
                                           envConditions,
                                           effect,
                                           ifNotExists);
        }
    }
}