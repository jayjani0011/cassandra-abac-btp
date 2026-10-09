package org.apache.cassandra.auth;

/** Runs the ABAC user-count workload using the shared repeated-run protocol. */
public final class AbacUserCountExperimentRunner
{
    private AbacUserCountExperimentRunner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        AbacConditionCountExperimentRunner.run(args, "ABAC user-count", "users");
        System.exit(0);
    }
}
