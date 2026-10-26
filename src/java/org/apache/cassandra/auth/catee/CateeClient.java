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

    /** Returns the predicate result; errors throw instead of becoming a decision. */
    public boolean checkWorktime(int minute) throws IOException
    {
        if (minute < 0 || minute >= 1440)
            throw new IllegalArgumentException("Minute must be between 0 and 1439");

        return predicate(request("CHECK " + minute));
    }

    public void setTimeWindow(int startMinute, int endMinute) throws IOException
    {
        if (startMinute < 0 || startMinute >= endMinute || endMinute >= 1440)
            throw new IllegalArgumentException("Window requires 0 <= start < end < 1440");
        requireSuccess(request("SET_TIMEWINDOW " + startMinute + " " + endMinute));
    }

    public void grantAdminPrivilege(String role, int mask) throws IOException
    {
        requireSuccess(request(adminRequest("GRANT_ADMIN_PRIV", role, mask)));
    }

    public void revokeAdminPrivilege(String role, int mask) throws IOException
    {
        requireSuccess(request(adminRequest("REVOKE_ADMIN_PRIV", role, mask)));
    }

    public boolean checkAdminPrivilege(String role, int mask) throws IOException
    {
        return predicate(request(adminRequest("CHECK_ADMIN_PRIV", role, mask)));
    }

    public void beginRoleDeletion(String role) throws IOException
    {
        requireSuccess(request("BEGIN_ROLE_DELETE " + encodeRole(role)));
    }

    public void finishRoleDeletion(String role) throws IOException
    {
        requireSuccess(request("FINISH_ROLE_DELETE " + encodeRole(role)));
    }

    private static String adminRequest(String operation, String role, int mask)
    {
        if (mask == 0)
            throw new IllegalArgumentException("A nonzero privilege mask is required");
        return operation + " " + encodeRole(role) + " " + Integer.toUnsignedString(mask);
    }

    private static String encodeRole(String role)
    {
        if (role == null || role.isEmpty() || role.indexOf('\0') >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(role))
            throw new IllegalArgumentException("A valid role name is required");
        byte[] bytes = role.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 255)
            throw new IllegalArgumentException("Role name exceeds 255 UTF-8 bytes");
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes)
        {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static boolean predicate(String response) throws IOException
    {
        if (response.equals("ALLOW HW DEBUG")) return true;
        if (response.equals("DENY HW DEBUG")) return false;
        throw new IOException("CATEE returned an error or invalid response");
    }

    private static void requireSuccess(String response) throws IOException
    {
        if (!response.equals("OK HW DEBUG"))
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
