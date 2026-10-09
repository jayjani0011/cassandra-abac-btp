package org.apache.cassandra.auth;

/** Runs the ABAC environmental-condition-count workload using the shared repeated-run protocol. */
public final class AbacEnvironmentCountExperimentRunner
{
    private AbacEnvironmentCountExperimentRunner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        AbacConditionCountExperimentRunner.run(args, "ABAC environmental-condition-count", "environment_conditions_per_rule");
        System.exit(0);
    }
}
