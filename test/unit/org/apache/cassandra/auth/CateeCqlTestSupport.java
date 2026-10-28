package org.apache.cassandra.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;

import org.apache.cassandra.auth.catee.CateeClient;
import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.cql3.CQLTester;

import static org.junit.Assert.*;

/** Shared real-hardware fixture; runtime files belong to the isolated test directory. */
public abstract class CateeCqlTestSupport extends CQLTester
{
    protected static Process service;
    protected static Path socket;
    private static Path log;

    @BeforeClass
    public static void setupHardwareAndAuthentication() throws Exception
    {
        Assume.assumeTrue("true".equals(System.getProperty("catee.test.hardware")));
        String configuredSocket = CassandraRelevantProperties.CATEE_SOCKET.getString();
        if (configuredSocket == null)
            throw new IllegalArgumentException("Pass cassandra.catee.socket before JVM startup");
        socket = Path.of(configuredSocket);
        log = socket.resolveSibling("service.log");
        startService(true);
        CateeClient initial = new CateeClient(socket, 2000);
        assertTrue(initial.checkWorktime(0));
        assertTrue(initial.checkWorktime(1439));
        // Restricted-window scenarios configure their fixture explicitly.
        initial.setTimeWindow(540, 1020);
        CassandraRelevantProperties.ORG_APACHE_CASSANDRA_DISABLE_MBEAN_REGISTRATION.setBoolean(true);
        requireAuthentication();
        requireNetwork();
    }

    private static void startService(boolean initialize) throws Exception
    {
        Path root = Path.of("tee/catee").toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(root.resolve("build/catee-service").toString(), socket.toString(),
                                                    root.resolve("build/enclave.signed.so").toString(), socket + ".sealed");
        if (initialize) builder.command().add("--init");
        service = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        CateeClient client = new CateeClient(socket, 2000);
        for (int i = 0; i < 100; ++i)
        {
            if (!service.isAlive()) fail("Hardware service failed; inspect " + log);
            try { client.checkWorktime(840); return; }
            catch (IOException e) { Thread.sleep(50); }
        }
        fail("Hardware service did not become ready");
    }

    protected static void terminateService(boolean abruptly) throws Exception
    {
        if (service != null && service.isAlive())
        {
            if (abruptly) service.destroyForcibly(); else service.destroy();
            if (!service.waitFor(3, TimeUnit.SECONDS))
            {
                service.destroyForcibly();
                assertTrue(service.waitFor(3, TimeUnit.SECONDS));
            }
        }
    }

    protected static void restartService() throws Exception
    {
        terminateService(false);
        Files.deleteIfExists(socket); // A verified dead test process may leave its socket behind.
        startService(false);
    }

    @AfterClass
    public static void stopHardware() throws Exception
    {
        terminateService(false);
        if (socket != null)
            for (String suffix : new String[] {"", ".sealed", ".sealed.lock", ".sealed.tmp"})
                Files.deleteIfExists(Path.of(socket + suffix));
        if (log != null) Files.deleteIfExists(log);
    }
}
