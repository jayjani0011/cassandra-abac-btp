package org.apache.cassandra.auth;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.apache.cassandra.cql3.CQLTester;
import org.slf4j.LoggerFactory;

/** Runs the ABAC conditions-per-rule workload described by a manifest of input CSV paths. */
public final class AbacConditionCountExperimentRunner
{
    private static final int DISCARDED_RUNS = 2;
    private static final int MEASURED_RUNS = 8;
    private static final int AUTHORIZATIONS_PER_RUN = 100;
    private static final long ORDER_SEED = 20261010L;
    private static final String USER_NAME = "benchmark_user_0001";
    private static final IResource RESOURCE = DataResource.table("abac_benchmark", "resource_0001");
    private static AuthenticatedUser user;

    private AbacConditionCountExperimentRunner()
    {
    }

    public static void main(String[] args) throws Exception
    {
        run(args, "ABAC conditions-per-rule", "conditions_per_rule");
        System.exit(0);
    }

    static void run(String[] args, String experimentName, String factorColumn) throws Exception
    {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AbacConditionCountExperimentRunner <manifest.csv> <output-directory>");

        Path manifest = Path.of(args[0]);
        Path outputDirectory = Path.of(args[1]);
        Files.createDirectories(outputDirectory);
        List<Point> points = readManifest(manifest, factorColumn);
        List<Point> executionOrder = new ArrayList<>(points);
        Collections.shuffle(executionOrder, new Random(ORDER_SEED));

        CQLTester.setUpClass();
        user = new AuthenticatedUser(USER_NAME);
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
        ((Logger) LoggerFactory.getLogger(CassandraAuthorizer.class)).setLevel(Level.WARN);
        try (BufferedWriter raw = Files.newBufferedWriter(outputDirectory.resolve("raw_authorizations.csv"));
             BufferedWriter runs = Files.newBufferedWriter(outputDirectory.resolve("run_summary.csv"));
             BufferedWriter summary = Files.newBufferedWriter(outputDirectory.resolve("point_summary.csv")))
        {
            raw.write("profile," + factorColumn + ",point_id,run,sample,elapsed_ns\n");
            runs.write("profile," + factorColumn + ",point_id,run,mean_ns,median_ns,p95_ns,min_ns,max_ns\n");
            summary.write("profile," + factorColumn + ",point_id,measured_runs,mean_ns,median_ns,stddev_ns,min_ns,max_ns\n");

            CassandraAuthorizer authorizer = new CassandraAuthorizer();
            authorizer.setup();
            for (Point point : executionOrder)
            {
                List<Long> runMeans = runPoint(point, authorizer, raw, runs);
                Statistics statistics = Statistics.of(runMeans);
                summary.write(String.format(Locale.ROOT, "%s,%d,%s,%d,%.3f,%.3f,%.3f,%d,%d%n",
                                            point.profile, point.conditionCount, point.id, MEASURED_RUNS,
                                            statistics.mean, statistics.median, statistics.stddev,
                                            statistics.min, statistics.max));
                raw.flush();
                runs.flush();
                summary.flush();
            }
        }
        finally
        {
            CQLTester.tearDownClass();
        }

        Files.writeString(outputDirectory.resolve("metadata.txt"), metadata(manifest, points, experimentName));
    }

    private static List<Long> runPoint(Point point, CassandraAuthorizer authorizer, BufferedWriter raw,
                                       BufferedWriter runs) throws Exception
    {
        AbacConfigurationLoader.load(point.files);
        Set<Permission> permissions = authorizer.getAbacPermissions(user, RESOURCE);
        if (!permissions.contains(Permission.SELECT))
            throw new IllegalStateException("Anchor request was not granted for " + point.id);

        for (int run = 1; run <= DISCARDED_RUNS; run++)
            authorize(authorizer, AUTHORIZATIONS_PER_RUN, false, point, run, raw);

        List<Long> runMeans = new ArrayList<>(MEASURED_RUNS);
        for (int run = 1; run <= MEASURED_RUNS; run++)
        {
            List<Long> samples = authorize(authorizer, AUTHORIZATIONS_PER_RUN, true, point, run, raw);
            Statistics statistics = Statistics.of(samples);
            runMeans.add(Math.round(statistics.mean));
            runs.write(String.format(Locale.ROOT, "%s,%d,%s,%d,%.3f,%.3f,%d,%d,%d%n",
                                     point.profile, point.conditionCount, point.id, run,
                                     statistics.mean, statistics.median, statistics.p95,
                                     statistics.min, statistics.max));
        }
        return runMeans;
    }

    private static List<Long> authorize(CassandraAuthorizer authorizer, int calls, boolean record, Point point,
                                        int run, BufferedWriter raw) throws IOException
    {
        List<Long> samples = record ? new ArrayList<>(calls) : null;
        for (int sample = 1; sample <= calls; sample++)
        {
            long start = System.nanoTime();
            authorizer.getAbacPermissions(user, RESOURCE);
            long elapsed = System.nanoTime() - start;
            if (record)
            {
                samples.add(elapsed);
                raw.write(String.format(Locale.ROOT, "%s,%d,%s,%d,%d,%d%n",
                                        point.profile, point.conditionCount, point.id, run, sample, elapsed));
            }
        }
        return samples;
    }

    private static List<Point> readManifest(Path manifest, String factorColumn) throws IOException
    {
        String header = "profile," + factorColumn + ",point_id,configuration_json,users_csv,resources_csv,user_attributes_csv,resource_attributes_csv,environment_attributes_csv,abac_rules_csv,rule_conditions_csv";
        List<String> lines = Files.readAllLines(manifest);
        if (lines.isEmpty() || !lines.get(0).equals(header))
            throw new IllegalArgumentException("Unexpected manifest header: " + manifest);

        Path parent = manifest.toAbsolutePath().getParent();
        List<Point> points = new ArrayList<>(lines.size() - 1);
        for (int line = 1; line < lines.size(); line++)
        {
            String[] row = lines.get(line).split(",", -1);
            if (row.length != 11)
                throw new IllegalArgumentException("Expected 11 columns in " + manifest + " at line " + (line + 1));
            points.add(new Point(row[0], Integer.parseInt(row[1]), row[2], new AbacConfigurationLoader.ConfigurationFiles(
            resolve(parent, row[3]), resolve(parent, row[4]), resolve(parent, row[5]), resolve(parent, row[6]),
            resolve(parent, row[7]), resolve(parent, row[8]), resolve(parent, row[9]), resolve(parent, row[10]))));
        }
        points.sort(Comparator.comparing((Point point) -> point.profile).thenComparingInt(point -> point.conditionCount));
        return points;
    }

    private static Path resolve(Path parent, String value)
    {
        Path path = Path.of(value);
        return path.isAbsolute() ? path : parent.resolve(path).normalize();
    }

    private static String metadata(Path manifest, List<Point> points, String experimentName)
    {
        return String.format(Locale.ROOT,
                             "experiment=%s%nmanifest=%s%npoints=%d%nmethod=getAbacPermissions(AuthenticatedUser, IResource)%n" +
                             "loader_calls_per_point=1%nanchor_request=benchmark_user_0001,data/abac_benchmark/resource_0001,SELECT%n" +
                             "discarded_runs=%d%nauthorizations_per_run=%d%nmeasured_runs=%d%npoint_order=randomized; seed=%d%n" +
                             "timed_interval=one getAbacPermissions call; loading, correctness checks, and warm-up calls excluded%n",
                             experimentName, manifest, points.size(), DISCARDED_RUNS, AUTHORIZATIONS_PER_RUN, MEASURED_RUNS, ORDER_SEED);
    }

    private static final class Point
    {
        final String profile;
        final int conditionCount;
        final String id;
        final AbacConfigurationLoader.ConfigurationFiles files;

        private Point(String profile, int conditionCount, String id, AbacConfigurationLoader.ConfigurationFiles files)
        {
            this.profile = profile;
            this.conditionCount = conditionCount;
            this.id = id;
            this.files = files;
        }
    }

    private static final class Statistics
    {
        final double mean;
        final double median;
        final double stddev;
        final long p95;
        final long min;
        final long max;

        private Statistics(double mean, double median, double stddev, long p95, long min, long max)
        {
            this.mean = mean;
            this.median = median;
            this.stddev = stddev;
            this.p95 = p95;
            this.min = min;
            this.max = max;
        }

        static Statistics of(List<Long> values)
        {
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            double sum = 0;
            for (long value : sorted)
                sum += value;
            double mean = sum / sorted.size();
            double sumSquares = 0;
            for (long value : sorted)
                sumSquares += (value - mean) * (value - mean);
            int middle = sorted.size() / 2;
            double median = sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
            return new Statistics(mean, median, Math.sqrt(sumSquares / sorted.size()),
                                  sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1),
                                  sorted.get(0), sorted.get(sorted.size() - 1));
        }
    }
}
