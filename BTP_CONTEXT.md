# BTP Project Context — ABAC Cassandra

## 1. Project Goal

I am working on a BTP project involving an Attribute-Based Access Control (ABAC)
implementation for Apache Cassandra.

The implementation is based on this GitHub repository:

https://github.com/Rishabh8124/cassandra

The ABAC implementation is on the branch:

rishabh/abac_grammar

I want to:
1. Understand the implementation.
2. Run and verify it locally.
3. Replicate the performance experiments reported in the BTP report.
4. Collect my own measurements/logs.
5. Compare my results with the reported results.
6. Eventually use the results for my BTP report/presentation.

---

## 2. Important Reference Report

There is a report:

"BTP_Report_Rishabh.pdf"

Title:
"Supporting Attribute-Based Access Control in Apache Cassandra NoSQL Database"

Author:
Rishabh Sukumaran
Roll No. 22CS10058
IIT Kharagpur
Spring Semester 2025-26

The report is the primary reference for understanding the intended implementation
and the performance experiments.

### Important report sections

Chapter 4:
System Design

Chapter 5:
Implementation

Chapter 6:
Performance Evaluation

Chapter 6 is especially important because I want to reproduce its experiments.

---

## 3. Current Machine / Environment

OS:

Ubuntu 20.04.6 LTS (focal)

Architecture:

x86_64

Java:

openjdk version "11.0.27"

javac:

11.0.27

Ant:

Apache Ant 1.10.7

Git:

2.25.1

Python:

3.11.15

Python environment:

cassandra-py311

This environment is used for cqlsh.

---

## 4. Cassandra Checkouts

There are two Cassandra source trees.

### Normal Cassandra

~/cassandra

This is the normal/trunk checkout.

It was previously built and successfully run.

### ABAC Cassandra

~/cassandra-abac

This is the ABAC checkout:

branch:

rishabh/abac_grammar

The ABAC branch has been successfully built using:

ant jar

Build result:

BUILD SUCCESSFUL

Jar:

build/apache-cassandra-5.1-SNAPSHOT.jar

Runtime dependency:

lib/jamm-0.4.0.jar

---

## 5. Running the ABAC Instance

The ABAC Cassandra instance runs from:

~/cassandra-abac

Start:

cd ~/cassandra-abac
bin/cassandra -f

cqlsh:

conda activate cassandra-py311
cd ~/cassandra-abac
./bin/cqlsh -u cassandra -p cassandra

The instance runs on:

127.0.0.1:9042

Authentication is enabled.

Default superuser:

username: cassandra
password: cassandra

IMPORTANT:
Do not use the superuser account to test ABAC authorization behavior because
the implementation has a superuser bypass.

---

## 6. ABAC Implementation Has Been Confirmed

The branch contains significant ABAC modifications.

Important modified/added files include:

src/java/org/apache/cassandra/auth/AttributeHierarchyManager.java
src/java/org/apache/cassandra/auth/CassandraAuthorizer.java
src/java/org/apache/cassandra/auth/Permission.java
src/java/org/apache/cassandra/auth/AuthKeyspace.java

src/java/org/apache/cassandra/cql3/statements/schema/CreateAttributeStatement.java
src/java/org/apache/cassandra/cql3/statements/schema/AlterAttributeStatement.java
src/java/org/apache/cassandra/cql3/statements/schema/DropAttributeStatement.java
src/java/org/apache/cassandra/cql3/statements/schema/GrantAttributeStatement.java
src/java/org/apache/cassandra/cql3/statements/schema/RevokeAttributeStatement.java

src/java/org/apache/cassandra/cql3/statements/schema/CreateRuleStatement.java
src/java/org/apache/cassandra/cql3/statements/schema/DropRuleStatement.java
src/java/org/apache/cassandra/cql3/statements/schema/CreateHierarchyEdgeStatement.java

src/java/org/apache/cassandra/service/EnvironmentAttributeManager.java
src/java/org/apache/cassandra/service/EnvironmentAttributeProvider.java
src/java/org/apache/cassandra/service/provider/DayOfWeekProvider.java

ANTLR grammar files were also modified:

src/antlr/Lexer.g
src/antlr/Parser.g

---

## 7. ABAC Tables Confirmed on the Running Instance

Inside system_auth, the following ABAC-related tables exist:

abac_rules
attribute_definitions
attribute_hierarchy_edges
env_attribute_configs
hierarchy_metadata
resource_attribute_values
user_attribute_values

The running ABAC instance successfully showed these tables.

---

## 8. ABAC CQL Syntax

The branch adds CQL commands for ABAC administration.

### Attribute definition

CREATE ATTRIBUTE <name>
WITH TYPE <type>
AND VALUES IN (...);

Example:

CREATE ATTRIBUTE department
WITH TYPE text
AND VALUES IN ('CS', 'EE');

### User attribute

GRANT USER ATTRIBUTE <attribute> = <value> TO <user>;

Example:

GRANT USER ATTRIBUTE department = 'CS' TO alice;

### Resource attribute

GRANT RESOURCE ATTRIBUTE <attribute> = <value>
TO <resource>;

### Rule

CREATE RULE <name>
FOR <permissions>
OF USER ATTRIBUTE { ... }
AND RESOURCE ATTRIBUTE { ... }
AND ENVIRONMENT ATTRIBUTE { ... }
WITH EFFECT (GRANT | DENY);

Resource and environment conditions are optional.

---

## 9. ABAC Test Already Successfully Performed

A test keyspace was created:

abac_test

Table:

abac_test.data

Schema:

CREATE TABLE data (
    id int PRIMARY KEY,
    value text
);

A row was inserted.

Two users were created:

alice / alice
bob / bob

An attribute was created:

department

Allowed values:

CS
EE

Alice was assigned:

department = CS

Bob was not assigned this attribute.

A rule was created that grants SELECT based on the user attribute.

Alice successfully accessed the protected resource while Bob was denied.

The user's attribute was then changed from CS to EE and the authorization behavior changed accordingly.

This verified that the running ABAC implementation actually affects authorization decisions.

IMPORTANT:
Alice and Bob were not given ordinary RBAC SELECT permissions in this test.
This was intentional so that ABAC was responsible for the observed access behavior.

---

## 10. Important ABAC Semantics From the Report

The report says CassandraAuthorizer does both RBAC and ABAC evaluation.

Overall flow:

1. RBAC permissions are resolved.
2. ABAC permissions are computed.
3. User attributes are fetched.
4. Resource attributes are fetched.
5. Required environment attributes are resolved.
6. All ABAC rules are fetched.
7. Every rule is evaluated.
8. Matching GRANT rules add permissions.
9. Matching DENY rules add denied permissions.
10. Denied permissions are removed from granted permissions.
11. Final permission set is the union of RBAC and ABAC permissions.

Therefore:

- RBAC and ABAC are independent.
- Their permissions are unioned.
- Within ABAC, DENY overrides GRANT.

Rule conditions are conjunctions:
all specified user/resource/environment conditions must match.

Attribute evaluation supports:
- direct equality
- hierarchy-aware matching

---

# 11. Chapter 6 — Performance Experiments To Replicate

This is the current main task.

IMPORTANT:
Do not assume that the numerical results below should be reproduced exactly.
The goal is first to reproduce the experiment design and obtain measurements on
my machine.

The report's Chapter 6 contains THREE actual experiments.

---

# Experiment 1
## Rule Count vs Authorization Time

### Purpose

Measure how the time taken by:

getAbacPermissions()

changes as the total number of ABAC rules increases.

The implementation scans all rows in system_auth.abac_rules for each authorization
call, so the expected behavior is O(R), where R is the number of rules.

### Rule counts

1
10
50
100
200
400
600
800
1000

### Attributes per rule

1
2
4
8

The report says the attributes are split between user and resource attributes.

Only the final rule should match the test user.
The other rules should be non-matching.

The purpose is to force a full rule scan.

### Reported results

Mean getAbacPermissions() time in microseconds:

| Rules | 1 attr/rule | 2 attr/rule | 4 attr/rule | 8 attr/rule |
|------:|------------:|------------:|------------:|------------:|
| 1     | 6806  | 8712  | 9424  | 12141 |
| 10    | 6079  | 8716  | 9862  | 12357 |
| 50    | 5879  | 10065 | 10729 | 14255 |
| 100   | 6336  | 11887 | 11261 | 15173 |
| 200   | 8085  | 14753 | 14631 | 16224 |
| 400   | 12046 | 16605 | 19386 | 20449 |
| 600   | 16802 | 20087 | 22316 | 25124 |
| 800   | 22215 | 23904 | 25401 | 33889 |
| 1000  | 28029 | 30311 | 30575 | 36867 |

Reported conclusion:

Authorization time grows approximately linearly with rule count.

The dominant cost is the sequential scan of abac_rules.

---

# Experiment 2
## Attribute Conditions per Rule vs Authorization Time

### Purpose

Isolate the cost of evaluating conditions inside a rule.

### Number of attribute conditions

2
5
10
20
40
60
80

The conditions are split evenly between user and resource attributes.

### Total rule counts

1
10
20
50

### Reported results

Mean getAbacPermissions() time in microseconds:

| Conditions | 1 rule | 10 rules | 20 rules | 50 rules |
|-----------:|-------:|---------:|---------:|---------:|
| 2  | 5080 | 5906 | 5938 | 9884 |
| 5  | 5654 | 6259 | 6189 | 10000 |
| 10 | 6273 | 6099 | 6655 | 10058 |
| 20 | 5314 | 7020 | 8656 | 10213 |
| 40 | 4847 | 7555 | 9254 | 10665 |
| 60 | 4941 | 5914 | 9909 | 11981 |
| 80 | 5175 | 7293 | 10455 | 14026 |

Reported conclusion:

Condition evaluation cost is comparatively small.

The main cost remains the O(R) rule scan.

---

# Experiment 3
## Hierarchy Depth vs Authorization Time

### Purpose

Test whether authorization time increases with attribute hierarchy depth.

### Hierarchy

A linear hierarchy is created:

v0 -> v1 -> ... -> vD

The test user gets:

vD

The rule requires:

v0

Hierarchy depths:

1
2
3
4
5

The implementation maintains a precomputed transitive closure, so authorization
should be a constant-time lookup instead of walking the hierarchy.

### Reported results

| Hierarchy depth | Mean time (µs) |
|----------------:|---------------:|
| 1 | 7781 |
| 2 | 6725 |
| 3 | 8046 |
| 4 | 6738 |
| 5 | 6061 |

Reported conclusion:

Authorization time remains roughly constant across hierarchy depth.

---

# 12. Critical Measurement Detail

The report measures:

getAbacPermissions()

not simply end-to-end cqlsh query latency.

The report describes getAbacPermissions() as:

- reading user attributes
- reading resource attributes
- discovering environment attributes
- reading all ABAC rules
- evaluating each rule
- applying GRANT/DENY conflict resolution

Therefore, do NOT simply time:

SELECT * FROM abac_test.data;

and call that an equivalent replication.

We need to reproduce the actual method-level benchmark as closely as possible.

---

# 13. Current Task

The current task is:

1. Inspect the existing CassandraAuthorizer implementation in
   ~/cassandra-abac.
2. Determine the exact signature/visibility of getAbacPermissions().
3. Determine the cleanest way to benchmark that method directly.
4. Build a controlled benchmark harness.
5. Reproduce Experiment 1 first.
6. Collect repeated measurements and calculate mean times.
7. Save the results to a log/CSV.
8. Compare my measurements against the report.
9. Then reproduce Experiments 2 and 3.

Do NOT immediately modify Cassandra source code.
First inspect the repository and determine the safest benchmark approach.

---

# 14. Important Constraints

- Do not touch ~/cassandra (the normal/trunk checkout).
- Work only in ~/cassandra-abac unless explicitly asked otherwise.
- Do not destroy the current ABAC installation/data unnecessarily.
- Avoid guessing about the implementation when the repository/report can answer it.
- Prefer the report as the source for intended experiment design.
- Distinguish clearly between:
  a) what the report explicitly states,
  b) what is observed on my machine,
  c) assumptions/inferences needed to reproduce an underspecified benchmark.
- Do not claim that my numbers reproduce Rishabh's numbers exactly unless they actually do.
- Keep benchmark methodology reproducible and log all important conditions.

---

# 15. Current Immediate Next Step

Inspect:

src/java/org/apache/cassandra/auth/CassandraAuthorizer.java

Specifically find:

getAbacPermissions()

Determine:
- signature
- visibility
- inputs
- return value
- dependencies
- whether it can be called directly from a benchmark
- whether it has side effects

Also inspect whether there is already benchmark/test infrastructure in the repository
that could be reused.

Do not change files yet.

After inspection, explain the recommended benchmark approach before implementing it.