package org.apache.cassandra.auth;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.slf4j.LoggerFactory;

/** Direct runner for Chapter 6, Experiment 2. */
public final class AbacExperiment2Runner {
    private static final int[] CONDITIONS = {2, 5, 10, 20, 40, 60, 80};
    private static final int[] RULES = {1, 10, 20, 50};
    private static final int WARMUP = 20, SAMPLES = 50;
    private static final String USER = "abac_exp2_user";
    private static final String RESOURCE_NAME = "data/abac_experiment_2/target";
    private static final IResource RESOURCE = DataResource.table("abac_experiment_2", "target");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path out = Path.of(args[0]); Files.createDirectories(out);
        CQLTester.setUpClass();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        ((Logger) LoggerFactory.getLogger(CassandraAuthorizer.class)).setLevel(Level.WARN);
        try (BufferedWriter raw = Files.newBufferedWriter(out.resolve("experiment2_raw_samples.csv"));
             BufferedWriter summary = Files.newBufferedWriter(out.resolve("experiment2_summary.csv"))) {
            raw.write("conditions_per_rule,rule_count,sample,elapsed_ns\n");
            summary.write("conditions_per_rule,rule_count,warmup_calls,measured_calls,mean_us,stddev_us,min_us,max_us\n");
            for (int c : CONDITIONS) for (int r : RULES) run(c, r, raw, summary);
        } finally { CQLTester.tearDownClass(); }
        Files.writeString(out.resolve("experiment2_metadata.txt"), String.format(Locale.ROOT,
            "experiment=Chapter 6 Experiment 2%nmethod=getAbacPermissions(AuthenticatedUser, IResource)%n"+
            "warmup_calls_per_point=%d%nmeasured_calls_per_point=%d%nthreads=1%nenvironment_conditions=none%n"+
            "nonmatching_rules=all except last; first user condition mismatches%n"+
            "odd_condition_split=ceil(conditions/2) user, remainder resource%nlogging=WARN (excluded from timing)%n"+
            "java.version=%s%njava.vm.name=%s%nos.name=%s%nos.arch=%s%navailable_processors=%d%n",
            WARMUP, SAMPLES, System.getProperty("java.version"), System.getProperty("java.vm.name"),
            System.getProperty("os.name"), System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors()));
        System.exit(0);
    }

    private static void run(int c, int r, BufferedWriter raw, BufferedWriter summary) throws Exception {
        reset(); int u = (c + 1) / 2;
        for (int i = 0; i < u; i++) ins(String.format("INSERT INTO system_auth.user_attribute_values (user_name, attribute_name, attribute_value) VALUES ('%s','u%d','match')", USER, i));
        for (int i = 0; i < c - u; i++) ins(String.format("INSERT INTO system_auth.resource_attribute_values (resource_name, attribute_name, attribute_value) VALUES ('%s','r%d','match')", RESOURCE_NAME, i));
        for (int i = 0; i < r; i++) {
            boolean match = i == r - 1; StringBuilder um = new StringBuilder("{");
            for (int j = 0; j < u; j++) { if (j > 0) um.append(','); um.append("'u").append(j).append("':'").append(!match && j == 0 ? "wrong" + i : "match").append("'"); } um.append('}');
            StringBuilder rm = new StringBuilder("{");
            for (int j = 0; j < c-u; j++) { if (j > 0) rm.append(','); rm.append("'r").append(j).append("':'match'"); } rm.append('}');
            ins(String.format("INSERT INTO system_auth.abac_rules (rule_name,permissions,user_attribute_conditions,resource_attribute_conditions,environment_attribute_conditions,effect) VALUES ('exp2_%d_%d_%d',{'SELECT'},%s,%s,{},'GRANT')", c, r, i, um, rm));
        }
        CassandraAuthorizer a = new CassandraAuthorizer(); a.setup(); AuthenticatedUser user = new AuthenticatedUser(USER);
        for (int i=0;i<WARMUP;i++) a.getAbacPermissions(user, RESOURCE);
        long sum=0,min=Long.MAX_VALUE,max=Long.MIN_VALUE; double sq=0;
        for (int i=0;i<SAMPLES;i++) { long t=System.nanoTime(); a.getAbacPermissions(user, RESOURCE); long e=System.nanoTime()-t; raw.write(String.format(Locale.ROOT,"%d,%d,%d,%d%n",c,r,i,e)); sum+=e; sq+=(double)e*e; min=Math.min(min,e); max=Math.max(max,e); }
        double mean=(double)sum/SAMPLES, sd=Math.sqrt(Math.max(0,sq/SAMPLES-mean*mean));
        summary.write(String.format(Locale.ROOT,"%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n",c,r,WARMUP,SAMPLES,mean/1000.0,sd/1000.0,min/1000.0,max/1000.0)); summary.flush(); raw.flush();
    }
    private static void ins(String q) { QueryProcessor.executeInternal(q); }
    private static void reset() { ins("TRUNCATE system_auth.user_attribute_values"); ins("TRUNCATE system_auth.resource_attribute_values"); ins("TRUNCATE system_auth.abac_rules"); }
}
