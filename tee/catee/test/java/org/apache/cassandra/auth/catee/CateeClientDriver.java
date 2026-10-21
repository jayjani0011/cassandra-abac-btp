package org.apache.cassandra.auth.catee;

import java.nio.file.Path;

/** Test/manual entry point; not part of Cassandra's authorization path. */
public final class CateeClientDriver
{
    public static void main(String[] args)
    {
        try
        {
            if (args.length != 3)
                throw new IllegalArgumentException("Usage: SOCKET_PATH MINUTE TIMEOUT_MS");
            CateeClient client = new CateeClient(Path.of(args[0]), Integer.parseInt(args[2]));
            boolean allowed = client.checkWorktime(Integer.parseInt(args[1]));
            System.out.println(allowed ? "ALLOW HW DEBUG" : "DENY HW DEBUG");
        }
        catch (Exception | LinkageError error)
        {
            System.err.println("CATEE client failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
