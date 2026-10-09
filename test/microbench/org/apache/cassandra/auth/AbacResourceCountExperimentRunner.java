package org.apache.cassandra.auth;

/** Runs the ABAC resource-count workload using the shared repeated-run protocol. */
public final class AbacResourceCountExperimentRunner
{
    private AbacResourceCountExperimentRunner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        AbacConditionCountExperimentRunner.run(args, "ABAC resource-count", "resources");
        System.exit(0);
    }
}
