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

/** Direct runner for Chapter 6, Experiment 3. */
public final class AbacExperiment3Runner {
    private static final String USER="abac_exp3_user", RES="data/abac_experiment_3/target";
    private static final IResource RESOURCE=DataResource.table("abac_experiment_3","target");
    private static final int WARMUP=20, SAMPLES=50;
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Usage: <output-directory>");
        Path out=Path.of(args[0]); Files.createDirectories(out); CQLTester.setUpClass();
        ((Logger)LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        ((Logger)LoggerFactory.getLogger(CassandraAuthorizer.class)).setLevel(Level.WARN);
        ((Logger)LoggerFactory.getLogger(AttributeHierarchyManager.class)).setLevel(Level.WARN);
        try(BufferedWriter raw=Files.newBufferedWriter(out.resolve("experiment3_raw_samples.csv")); BufferedWriter sum=Files.newBufferedWriter(out.resolve("experiment3_summary.csv"))) {
            raw.write("depth,sample,elapsed_ns\n"); sum.write("depth,warmup_calls,measured_calls,mean_us,stddev_us,min_us,max_us\n");
            for(int d=1;d<=5;d++) run(d,raw,sum);
        } finally { CQLTester.tearDownClass(); }
        Files.writeString(out.resolve("experiment3_metadata.txt"),String.format(Locale.ROOT,"experiment=Chapter 6 Experiment 3%nmethod=getAbacPermissions(AuthenticatedUser, IResource)%n hierarchy=attribute hierarchy_attr: v0 -> ... -> vD%nuser_assignment=vD; rule_requirement=v0%nwarmup_calls_per_point=%d%nmeasured_calls_per_point=%d%nlogging=WARN (excluded from timing)%njava.version=%s%njava.vm.name=%s%nos.name=%s%nos.arch=%s%navailable_processors=%d%n",WARMUP,SAMPLES,System.getProperty("java.version"),System.getProperty("java.vm.name"),System.getProperty("os.name"),System.getProperty("os.arch"),Runtime.getRuntime().availableProcessors()));
        System.exit(0);
    }
    private static void run(int d,BufferedWriter raw,BufferedWriter sum)throws Exception{
        reset(); QueryProcessor.executeInternal(String.format("INSERT INTO system_auth.user_attribute_values (user_name,attribute_name,attribute_value) VALUES ('%s','hierarchy_attr','v%d')",USER,d));
        for(int i=0;i<d;i++) QueryProcessor.executeInternal(String.format("INSERT INTO system_auth.attribute_hierarchy_edges (attribute_name,parent,child) VALUES ('hierarchy_attr','v%d','v%d')",i,i+1));
        QueryProcessor.executeInternal("INSERT INTO system_auth.abac_rules (rule_name,permissions,user_attribute_conditions,resource_attribute_conditions,environment_attribute_conditions,effect) VALUES ('exp3_rule',{'SELECT'},{'hierarchy_attr':'v0'},{},{},'GRANT')");
        CassandraAuthorizer a=new CassandraAuthorizer(); a.setup(); AuthenticatedUser u=new AuthenticatedUser(USER);
        for(int i=0;i<WARMUP;i++) a.getAbacPermissions(u,RESOURCE);
        long total=0,min=Long.MAX_VALUE,max=Long.MIN_VALUE; double sq=0;
        for(int i=0;i<SAMPLES;i++){long t=System.nanoTime();a.getAbacPermissions(u,RESOURCE);long e=System.nanoTime()-t;raw.write(String.format(Locale.ROOT,"%d,%d,%d%n",d,i,e));total+=e;sq+=(double)e*e;min=Math.min(min,e);max=Math.max(max,e);} double m=(double)total/SAMPLES,sd=Math.sqrt(Math.max(0,sq/SAMPLES-m*m));sum.write(String.format(Locale.ROOT,"%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n",d,WARMUP,SAMPLES,m/1000.0,sd/1000.0,min/1000.0,max/1000.0));sum.flush();raw.flush();
    }
    private static void reset(){QueryProcessor.executeInternal("TRUNCATE system_auth.user_attribute_values");QueryProcessor.executeInternal("TRUNCATE system_auth.resource_attribute_values");QueryProcessor.executeInternal("TRUNCATE system_auth.abac_rules");QueryProcessor.executeInternal("TRUNCATE system_auth.attribute_hierarchy_edges");}
}
