package org.apache.cassandra.auth.catee;

import java.nio.file.Path;

/** Test/manual entry point; not part of Cassandra's authorization path. */
public final class CateeClientDriver
{
    public static void main(String[] args)
    {
        try
        {
            if (args.length == 5 && args[1].equals("ENV"))
            {
                if (!args[3].equals("true") && !args[3].equals("false"))
                    throw new IllegalArgumentException("Expected Boolean required");
                CateeClient client = new CateeClient(Path.of(args[0]), Integer.parseInt(args[4]));
                boolean matched = client.checkEnvironment(0, Integer.parseInt(args[2]), 0, "", false,
                                                          Boolean.parseBoolean(args[3]), false, true, false);
                System.out.println(matched ? "ALLOW HW DEBUG" : "DENY HW DEBUG");
                return;
            }
            if (args.length == 5 && args[1].equals("SET_TIMEWINDOW"))
            {
                CateeClient client = new CateeClient(Path.of(args[0]), Integer.parseInt(args[4]));
                client.setTimeWindow(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                System.out.println("OK HW DEBUG");
                return;
            }
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
