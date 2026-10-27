package org.apache.cassandra.auth.catee;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

/** Database-side IPC helper: connect, send one request, read one response, close. */
public final class CateeClient
{
    private final Path socketPath;
    private final int timeoutMillis;

    public CateeClient(Path socketPath, int timeoutMillis)
    {
        if (socketPath == null || !socketPath.isAbsolute())
            throw new IllegalArgumentException("Socket path must be absolute");
        if (timeoutMillis <= 0 || timeoutMillis > 60000)
            throw new IllegalArgumentException("Timeout must be between 1 and 60000 ms");
        this.socketPath = socketPath;
        this.timeoutMillis = timeoutMillis;
    }

    /** Convenience predicate for callers requesting worktime=true; comparison stays in SGX. */
    public boolean checkWorktime(int minute) throws IOException
    {
        return checkEnvironment(0, minute, 0, "", false, true, false, true, false);
    }

    /** PATEE-style observations, expected values and required-check flags. */
    public boolean checkEnvironment(int day, int minute, long clientIp, String subnet,
                                    boolean expectedDay, boolean expectedTime,
                                    boolean checkDay, boolean checkTime, boolean checkSubnet) throws IOException
    {
        if (day < 0 || day > 6 || minute < 0 || minute >= 1440
            || clientIp < 0 || clientIp > 0xffffffffL || subnet == null
            || subnet.length() > 63 || (!subnet.isEmpty() && !subnet.matches("[A-Za-z0-9_.-]+"))
            || subnet.equals("-"))
            throw new IllegalArgumentException("Invalid environmental observation");
        return predicate(request("CHECK_ENV " + day + " " + minute + " " + clientIp + " "
                                 + (subnet.isEmpty() ? "-" : subnet) + " "
                                 + (expectedDay ? 1 : 0) + " " + (expectedTime ? 1 : 0) + " "
                                 + (checkDay ? 1 : 0) + " " + (checkTime ? 1 : 0) + " " + (checkSubnet ? 1 : 0)));
    }

    public void setWorkdays(byte[] days) throws IOException
    {
        if (days == null || days.length != 7)
            throw new IllegalArgumentException("Seven workday values are required");
        StringBuilder request = new StringBuilder("SET_WORKDAY");
        for (byte day : days)
        {
            if (day != 0 && day != 1) throw new IllegalArgumentException("Workday values must be 0 or 1");
            request.append(' ').append(day);
        }
        if (!request(request.toString()).equals("OK HW DEBUG"))
            throw new IOException("CATEE returned an error or invalid response");
    }

    public void setTimeWindow(int startMinute, int endMinute) throws IOException
    {
        if (startMinute < 0 || startMinute >= endMinute || endMinute >= 1440)
            throw new IllegalArgumentException("Window requires 0 <= start < end < 1440");
        if (!request("SET_TIMEWINDOW " + startMinute + " " + endMinute).equals("OK HW DEBUG"))
            throw new IOException("CATEE returned an error or invalid response");
    }

    private static boolean predicate(String response) throws IOException
    {
        if (response.equals("ALLOW HW DEBUG")) return true;
        if (response.equals("DENY HW DEBUG")) return false;
        throw new IOException("CATEE returned an error or invalid response");
    }

    private String request(String request) throws IOException
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance())
        {
            socket.connect(AFUNIXSocketAddress.of(socketPath), remainingMillis(deadline));
            socket.getOutputStream().write((request + "\n").getBytes(StandardCharsets.US_ASCII));

            InputStream input = socket.getInputStream();
            StringBuilder response = new StringBuilder();
            for (int i = 0; i < 64; ++i)
            {
                socket.setSoTimeout(remainingMillis(deadline));
                int value = input.read();
                if (value < 0)
                    throw new IOException("Incomplete CATEE response");
                if (value == '\n')
                {
                    return response.toString();
                }
                if (value < 32 || value > 126)
                    throw new IOException("Non-ASCII CATEE response");
                response.append((char) value);
            }
            throw new IOException("Oversized CATEE response");
        }
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException
    {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            throw new SocketTimeoutException("CATEE response deadline exceeded");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
