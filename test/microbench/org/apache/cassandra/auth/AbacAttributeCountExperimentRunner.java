package org.apache.cassandra.auth;

/** Runs the ABAC attribute-count workload using the shared repeated-run protocol. */
public final class AbacAttributeCountExperimentRunner
{
    private AbacAttributeCountExperimentRunner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        AbacConditionCountExperimentRunner.run(args, "ABAC attribute-count", "attributes_per_entity");
        System.exit(0);
    }
}
