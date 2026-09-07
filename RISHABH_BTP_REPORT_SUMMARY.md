# Summary: Supporting Attribute-Based Access Control in Apache Cassandra NoSQL Database

## Source and scope

- **Report:** *Supporting Attribute-Based Access Control in Apache Cassandra NoSQL Database*
- **Author:** Rishabh Sukumaran (22CS10058), IIT Kharagpur
- **Supervisor:** Professor Shamik Sural
- **Submission date stated in report:** May 3, 2026
- **Local source PDF:** `/home/jay_jani_0011/Desktop/btp/Sem 7/BTP_Report_Rishabh.pdf`

This document summarizes what Rishabh's report states. The final section records
replication caveats discovered from inspecting the current source tree; those are
not claims made by the report.

## Executive summary

The project extends Apache Cassandra with a native Attribute-Based Access Control
(ABAC) mechanism. Cassandra's normal authorization model is Role-Based Access
Control (RBAC), where users receive permissions through roles. The report argues
that RBAC is hard to administer when access depends on combinations of user,
resource, and runtime properties.

Instead of placing a policy-enforcement proxy in front of Cassandra, the project
places ABAC evaluation inside Cassandra's own `IAuthorizer` implementation. ABAC
administration is exposed through new CQL statements, and its metadata is stored
in Cassandra's `system_auth` keyspace. The implementation supports attributes of
users, resources, and the environment; grant and deny rules; attribute-value
hierarchies; and pluggable dynamic environment attributes.

The report concludes that the design is practical. Its performance experiments
identify scanning the entire rule table as the main cost. They report that adding
conditions to a rule and increasing hierarchy depth have comparatively little
effect on authorization time.

## Motivation

Cassandra's standard RBAC model is suitable for fixed job-function permissions,
but the report identifies three limitations:

1. **Role explosion.** Policies involving combinations of user properties and
   data properties can require a separate role for every combination.
2. **Static, identity-only policies.** RBAC cannot naturally express conditions
   based on resource classification or other properties of the object being
   accessed.
3. **No native context awareness.** Standard Cassandra RBAC has no mechanism to
   make a decision based on current context, such as day of week or system state.

ABAC expresses a policy as conditions on a subject (user), object (resource), and
environment. A native implementation avoids the additional network hop,
operational complexity, and potential single point of failure introduced by an
external proxy.

## Contributions claimed in the report

The report presents the following main contributions:

1. **Native CQL administration.** New CQL DDL constructs allow administrators to
   manage attributes, assignments, policies, and hierarchies inside Cassandra.
2. **ABAC-aware authorizer.** `CassandraAuthorizer` evaluates ABAC rules in
   addition to standard RBAC permissions.
3. **DAG-based attribute hierarchy.** Attribute values can form directed acyclic
   graphs, with a precomputed transitive closure intended to give constant-time
   authorization-time hierarchy lookup.
4. **Hierarchy-cache persistence and synchronization.** The hierarchy closure is
   serialized with Kryo, and nodes poll a timestamp every 30 seconds to detect
   remote hierarchy changes.
5. **Pluggable environment attributes.** Java `ServiceLoader` discovers provider
   implementations for dynamic, runtime-computed attributes.

## Background concepts

### Cassandra authorization

Cassandra calls an `IAuthorizer` on the coordinator node before executing a
protected operation. The primary authorization interface is conceptually:

```java
Set<Permission> authorize(AuthenticatedUser user, IResource resource)
```

The standard `CassandraAuthorizer` resolves RBAC permissions through roles and
role inheritance. The ABAC implementation extends this same authorization path.

### ABAC entities and rules

The report uses three kinds of attributes:

- **Subject/user attributes:** for example `department = engineering` or
  `clearance = secret`.
- **Resource attributes:** for example `classification = internal` or
  `owner = alice`.
- **Environment attributes:** request-time values such as day of week, client
  location, or system state.

An ABAC rule has the conceptual form:

```text
IF user conditions AND resource conditions AND environment conditions
THEN GRANT or DENY a set of permissions
```

Every condition provided by a rule must be satisfied for the rule to match.

## Architecture

The report describes three layers:

1. **CQL interface layer.** Administrators issue ABAC-management statements
   through CQL and `cqlsh`.
2. **Authorization layer.** `CassandraAuthorizer` evaluates standard RBAC and
   ABAC permissions.
3. **Persistence layer.** ABAC metadata is stored in `system_auth`; the hierarchy
   closure is additionally cached in memory at each node.

RBAC and ABAC are evaluated independently. The report states the final permission
set as:

```text
effective permissions = RBAC permissions union ABAC permissions
```

Within the ABAC contribution, deny-overrides-grant is applied. A matching `DENY`
removes the corresponding permission from the ABAC grant set. A permission granted
by RBAC is not removed by this ABAC-only conflict resolution.

## ABAC data model

The implementation adds the following `system_auth` tables:

| Table | Purpose |
|---|---|
| `attribute_definitions` | Attribute name, declared type, and optional allowed values. |
| `user_attribute_values` | Attribute-value assignments for users. |
| `resource_attribute_values` | Attribute-value assignments for resources. |
| `abac_rules` | Rule name, permissions, three condition maps, and effect. |
| `attribute_hierarchy_edges` | Persisted parent-to-child hierarchy edges. |
| `hierarchy_metadata` | A modification timestamp used for cache-staleness detection. |

The user and resource assignment tables use the user or resource name as their
partition key, enabling a single-partition read of all attributes for the target of
an authorization check.

## CQL administration interface

The report documents ABAC management statements including:

- `CREATE ATTRIBUTE`, `ALTER ATTRIBUTE`, and `DROP ATTRIBUTE`
- `GRANT USER ATTRIBUTE` and `REVOKE USER ATTRIBUTE`
- `GRANT RESOURCE ATTRIBUTE` and `REVOKE RESOURCE ATTRIBUTE`
- `CREATE RULE` and `DROP RULE`
- `CREATE HIERARCHY_EDGE` and `DROP HIERARCHY_EDGE`

`CREATE RULE` accepts user conditions and optional resource and environment maps.
For example, a rule can grant `SELECT` only to a user in a particular department,
against a resource with a particular classification, on a permitted weekday.

New permissions protect ABAC administration, including creating, altering, and
dropping attributes; assigning/revoking user and resource attributes; and creating
or dropping policies. Hierarchy edits are protected using `ALTER_ATTRIBUTE`.

## Authorization algorithm

The report describes the following flow for each authorization request:

1. A superuser bypasses the normal RBAC and ABAC evaluation.
2. Standard RBAC permissions are resolved from role permissions.
3. All user attributes are loaded from `user_attribute_values`.
4. All resource attributes are loaded from `resource_attribute_values`.
5. Environment attribute names referenced by rules are identified and their values
   obtained through registered providers.
6. All rows of `abac_rules` are read.
7. Every rule is evaluated against user, resource, and environment conditions.
8. Matching `GRANT` rules contribute to a grant set; matching `DENY` rules
   contribute to a denial set.
9. Denied permissions are removed from the ABAC grant set.
10. The resulting ABAC permissions are unioned with the RBAC permissions.

The condition evaluator first checks direct string equality. If equality fails, it
uses the hierarchy manager to test the appropriate attribute-value relationship.

## Attribute hierarchy design

Each named attribute has an independent directed acyclic graph of values. The
report uses a parent-to-child edge to express an authority/specificity relationship,
such as:

```text
engineering -> backend
```

`AttributeHierarchyManager` maintains two concurrent maps:

- `descendants[(attribute, value)]`: values reachable below a value;
- `ancestors[(attribute, value)]`: values that can reach a value.

On an edge addition, the implementation propagates the newly connected
ancestor-descendant pairs. The stated write cost is proportional to the number of
ancestors of the parent times the number of descendants of the child. Removing an
edge triggers a full rebuild for the affected attribute because alternate paths must
be considered.

The hierarchy closure is persisted in a Kryo binary cache file. At startup, a node
uses the cache if it is at least as recent as the database metadata timestamp;
otherwise it rebuilds from persisted edges. A task saves dirty cache data every five
minutes. Another task polls hierarchy metadata every 30 seconds and rebuilds when
another node has changed the hierarchy.

## Dynamic environment attributes

The environment extension point is an `EnvironmentAttributeProvider` interface.
Each provider supplies an attribute name and a current value. Providers are
discovered by Java `ServiceLoader` from the Cassandra classpath.

Only environment attributes referenced by existing rules are resolved at
authorization time. The included example is a day-of-week provider, which can be
used to restrict access based on the current day. The design is intended to permit
new providers, such as geolocation or system-load providers, through a JAR and a
service-registration file rather than a Cassandra source change.

## Implementation outline

The implementation described by the report changes four main areas:

1. **ANTLR CQL grammar.** New lexer tokens and parser rules recognize ABAC
   statements and prepare executable statement classes.
2. **Statement execution.** Statement classes authorize, validate, and execute
   management operations. Attribute changes enforce allowed-value constraints;
   renaming migrates references across multiple tables; dropping an attribute
   cascades to related assignments and hierarchy data.
3. **Hierarchy manager.** `AttributeHierarchyManager` owns in-memory closure
   state, persistence, initialization, incremental edge addition, and rebuilds.
4. **Authorizer integration.** `CassandraAuthorizer` combines RBAC resolution
   with `getAbacPermissions()` and initializes the hierarchy manager on startup.

The report notes that attribute rename is a multi-step, non-atomic process because
Cassandra does not provide the required multi-partition transaction semantics.

## Performance evaluation reported in Chapter 6

The report measures `getAbacPermissions()`, rather than end-to-end CQL client
latency. It presents three experiments.

### Experiment 1: rule count versus authorization time

Rule counts are `1, 10, 50, 100, 200, 400, 600, 800, 1000`. Conditions per rule
are `1, 2, 4, 8`, intended to be split between user and resource attributes. Only
the final rule matches, forcing the implementation to evaluate all earlier rules.

Reported mean times in microseconds:

| Rules | 1 condition | 2 conditions | 4 conditions | 8 conditions |
|---:|---:|---:|---:|---:|
| 1 | 6,806 | 8,712 | 9,424 | 12,141 |
| 10 | 6,079 | 8,716 | 9,862 | 12,357 |
| 50 | 5,879 | 10,065 | 10,729 | 14,255 |
| 100 | 6,336 | 11,887 | 11,261 | 15,173 |
| 200 | 8,085 | 14,753 | 14,631 | 16,224 |
| 400 | 12,046 | 16,605 | 19,386 | 20,449 |
| 600 | 16,802 | 20,087 | 22,316 | 25,124 |
| 800 | 22,215 | 23,904 | 25,401 | 33,889 |
| 1000 | 28,029 | 30,311 | 30,575 | 36,867 |

The report concludes that authorization grows approximately linearly with rule
count. It attributes the trend to reading every `abac_rules` row and evaluating
every rule sequentially.

### Experiment 2: conditions per rule versus authorization time

The matching rule has `2, 5, 10, 20, 40, 60, 80` conditions, intended to be split
between user and resource conditions. Total rule counts are `1, 10, 20, 50`.

Reported mean times in microseconds:

| Conditions | 1 rule | 10 rules | 20 rules | 50 rules |
|---:|---:|---:|---:|---:|
| 2 | 5,080 | 5,906 | 5,938 | 9,884 |
| 5 | 5,654 | 6,259 | 6,189 | 10,000 |
| 10 | 6,273 | 6,099 | 6,655 | 10,058 |
| 20 | 5,314 | 7,020 | 8,656 | 10,213 |
| 40 | 4,847 | 7,555 | 9,254 | 10,665 |
| 60 | 4,941 | 5,914 | 9,909 | 11,981 |
| 80 | 5,175 | 7,293 | 10,455 | 14,026 |

The report concludes that condition evaluation is small compared with scanning
the full rule set. Even at 80 conditions, a direct map lookup plus an optional
constant-time hierarchy check is reported as inexpensive.

### Experiment 3: hierarchy depth versus authorization time

The report constructs a linear hierarchy `v0 -> v1 -> ... -> vD` for depths 1 to
5. It assigns the test user `vD` and creates a rule requiring `v0`.

| Hierarchy depth | Mean time (microseconds) |
|---:|---:|
| 1 | 7,781 |
| 2 | 6,725 |
| 3 | 8,046 |
| 4 | 6,738 |
| 5 | 6,061 |

The report concludes that hierarchy depth adds no meaningful read-time cost
because the transitive closure reduces the decision to hash-map/set membership.

## Overall conclusion and future directions

The report concludes that native ABAC provides fine-grained, context-aware access
control in Cassandra without an external proxy or client changes. It considers the
reported authorization overhead acceptable for typical rule counts, while naming
the all-rules scan as the principal scalability limitation.

Suggested future work includes rule caching, gossip-based cache invalidation,
richer condition operators, and post-authorization obligations.

## Local replication caveats

The following observations come from our repository and report inspection:

- No Rishabh-authored benchmark harness, generated CQL, raw samples, CSV data,
  or graph-generation source was found in the report directory, the tracked
  worktree, reachable Git history, or unreachable Git objects.
- The report does not document its warm-up count, sample count, timing API,
  outlier policy, hardware, JVM settings, Cassandra settings, cleanup process,
  or exact construction of Experiment 2's non-matching rules.
- The report says Chapter 6 has three experiments, although an earlier chapter
  says five experiments.
- Experiment 1 specifies an even user/resource split but includes one condition,
  for which an even split is impossible. Experiment 2 likewise includes five
  conditions.
- The Experiment 3 setup described in the report conflicts with the hierarchy
  direction currently implemented in the checked-out code: assigning `vD` and
  requiring `v0` does not exercise the intended match under the current
  `check(actual, required)` semantics.

Consequently, the local runners in `test/microbench/org/apache/cassandra/auth/`
are a documented, controlled reproduction harness created for this BTP. Their
results must be described as **local replication measurements**, not as an exact
reproduction of Rishabh's original measurements.
