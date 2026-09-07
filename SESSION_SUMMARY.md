# Cassandra ABAC BTP Session Summary

## Project

- Workspace: `/home/jay_jani_0011/cassandra-abac`
- Branch: `rishabh/abac_grammar`
- Primary report: `/home/jay_jani_0011/Desktop/btp/Sem 7/BTP_Report_Rishabh.pdf`
- Context document: `BTP_CONTEXT.md`

## Goal

Reproduce Chapter 6 performance experiments from Rishabh Sukumaran's BTP report,
"Supporting Attribute-Based Access Control in Apache Cassandra NoSQL Database."
Do not modify source or destroy existing data without first deciding on a controlled
benchmark approach. Work only in `cassandra-abac`, not `~/cassandra`.

## Environment

From `BTP_CONTEXT.md`:

- Ubuntu 20.04.6 x86_64
- Java 11.0.27
- Ant 1.10.7
- Python 3.11 environment for `cqlsh`
- Cassandra ABAC branch was built successfully with `ant jar`
- Runtime ABAC instance uses `127.0.0.1:9042` with authentication enabled
- Do not use superuser `cassandra` for authorization-behavior testing because it bypasses ABAC.

## Existing Worktree State

- Modified: `src/java/org/apache/cassandra/auth/CassandraAuthorizer.java`
  - `getAbacPermissions()` currently has package visibility with `@VisibleForTesting`.
  - At `HEAD`, this method was `private`; the visibility change is uncommitted.
- Untracked: `BTP_CONTEXT.md`, `benchmarks/`, `test/microbench/org/apache/cassandra/auth/`, `.openui/`
- Do not assume these untracked benchmark runners or results were written by Rishabh.
  They are later local reproduction artifacts and are not evidence of Rishabh's exact methodology.

## Report Chapter 6

### Experiment 1

- Rule counts: `1, 10, 50, 100, 200, 400, 600, 800, 1000`
- Conditions per rule: `1, 2, 4, 8`
- Only the final rule in each batch matches; earlier rules are non-matching.
- Conditions are intended to be split between user and resource attributes.
- Measures mean `getAbacPermissions()` time in microseconds.

### Experiment 2

- Conditions per rule: `2, 5, 10, 20, 40, 60, 80`
- Rule counts: `1, 10, 20, 50`
- Conditions in the matching rule are intended to be split between user and resource attributes.
- The report does not state how non-matching rules are generated.
- The odd value `5` means an exact even split is impossible.

### Experiment 3

- Hierarchy: `v0 -> v1 -> ... -> vD`
- Depths: `1` through `5`
- User is assigned `vD`; the rule requires `v0`.
- Measures mean `getAbacPermissions()` time.

## Code Findings

The main method is:

```java
Set<Permission> getAbacPermissions(AuthenticatedUser user, IResource resource)
```

It is at `src/java/org/apache/cassandra/auth/CassandraAuthorizer.java:113` and:

1. Reads user attributes from `system_auth.user_attribute_values`.
2. Reads resource attributes from `system_auth.resource_attribute_values`.
3. Executes `SELECT * FROM system_auth.abac_rules`.
4. Resolves environment attributes referenced by any rule.
5. Iterates every fetched rule, evaluates conditions, and applies DENY-overrides-GRANT.

Relevant implementation: `CassandraAuthorizer.java:117-208`.

The unconditional rule-table scan at line 136 and evaluation loop at line 176 explain
Experiment 1's expected O(R) trend.

### ABAC Schemas

Defined in `src/java/org/apache/cassandra/auth/AuthKeyspace.java:172-202`:

- `user_attribute_values`: primary key `(user_name, attribute_name)`
- `resource_attribute_values`: primary key `(resource_name, attribute_name)`
- `abac_rules`: one rule per row with user/resource/environment condition maps

## Critical Experiment 3 Issue

- `AttributeHierarchyManager.addEdge(parent, child)` records `child` as a descendant
  of `parent`: `AttributeHierarchyManager.java:201-211`.
- `evaluateConditions()` calls `check(attributeName, actualValue, requiredValue)` at
  `CassandraAuthorizer.java:226-230`.
- Therefore, for a hierarchy `v0 -> ... -> vD`, actual user value `vD`, and required
  rule value `v0`, the code checks whether `v0` is a descendant of `vD`. It is not.

The Chapter 6 Experiment 3 setup as written does not make the rule match under the
current implementation. It can still measure method time, but cannot validate the
report's claimed hierarchy-match path without changing the setup or implementation.
Do not silently reverse hierarchy data or values; first record this as a methodology
discrepancy and decide whether to replicate the report literally or benchmark the
intended matching behavior.

## Rishabh History And Missing Benchmark Data

Relevant commits:

- `1cbda6023b` - Access control working
- `5378e02567` - Attribute Hierarchy edge addition working
- `ce0f8efab6` - Dynamic Env Attributes working

No Rishabh-authored Chapter 6 harness, CSV, raw data, plots, or scripts exist in:

- The tracked worktree
- Reachable Git history
- Unreachable Git objects
- `/home/jay_jani_0011/Desktop/btp/Sem 7`

The report itself omits:

- Benchmark script/class and insertion CQL
- Concrete users, resources, attribute names, and values
- Warm-up, sample, and repetition counts
- Timing API and outlier policy
- Hardware, JVM, and Cassandra configuration for the reported means
- Precise construction of Experiment 2 non-matching rules
- Odd-condition split convention
- Reset and cleanup procedure between points

## Existing Untracked Local Runners

- `test/microbench/org/apache/cassandra/auth/AbacExperiment1Runner.java`
- `test/microbench/org/apache/cassandra/auth/AbacExperiment2Runner.java`
- `test/microbench/org/apache/cassandra/auth/AbacExperiment3Runner.java`

They use direct method timing via `System.nanoTime()`, 20 warmups, 50 samples, reset
ABAC tables, and create synthetic data. These are an inferred reproducibility
approach, not Rishabh's original method.

## Recommended Next Step

Design and document a controlled Experiment 1 benchmark that:

- Calls `getAbacPermissions()` directly, not end-to-end CQL latency.
- Uses the report's exact parameter grid.
- Makes every non-final rule fail its first user condition and the final rule match.
- Explicitly defines a one-condition split convention.
- Records warmups, samples, JVM/OS, logging level, raw nanosecond samples, mean,
  standard deviation, min/max, and all generated CQL/data conventions.
- Is clearly labeled as a local replication rather than Rishabh's exact harness.
