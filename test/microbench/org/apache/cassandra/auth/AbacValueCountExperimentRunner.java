package org.apache.cassandra.auth;

/** Runs the ABAC values-per-attribute workload using the shared repeated-run protocol. */
public final class AbacValueCountExperimentRunner
{
    private AbacValueCountExperimentRunner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        AbacConditionCountExperimentRunner.run(args, "ABAC values-per-attribute", "values_per_attribute");
        System.exit(0);
    }
}
