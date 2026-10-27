package org.apache.cassandra.cql3.statements;

import java.io.IOException;

import org.apache.cassandra.audit.AuditLogContext;
import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.auth.CassandraAuthorizer;
import org.apache.cassandra.auth.IAuthorizer;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.messages.ResultMessage;

/** PATEE-style environmental configuration; supports working days and hours. */
public final class SetEnvironmentAttributeStatement extends AuthenticationStatement
{
    private final String attribute;
    private final String[] values;

    public SetEnvironmentAttributeStatement(String attribute, String[] values)
    {
        this.attribute = attribute;
        this.values = values.clone();
    }

    public void authorize(ClientState state)
    {
        state.ensureNotAnonymous();
        if (!state.isSuper())
            throw new UnauthorizedException("Only superusers may configure CATEE environmental attributes");
    }

    public void validate(ClientState state)
    {
        state.ensureNotAnonymous();
        if ("workday".equals(attribute)) workdays();
        else window();
    }

    private byte[] workdays()
    {
        String[] names = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};
        byte[] selected = new byte[7];
        if (values.length == 0)
            throw new InvalidRequestException("workday requires at least one day");
        for (String value : values)
        {
            int day = java.util.Arrays.asList(names).indexOf(value);
            if (day < 0) throw new InvalidRequestException("Invalid workday: " + value);
            selected[day] = 1;
        }
        return selected;
    }

    private int[] window()
    {
        if (!"worktime".equals(attribute))
            throw new InvalidRequestException("Unsupported CATEE environmental attribute: " + attribute);
        if (values.length != 4)
            throw new InvalidRequestException("worktime requires start hour, start minute, end hour, end minute");
        int[] time = new int[4];
        try
        {
            for (int i = 0; i < time.length; ++i)
                time[i] = Integer.parseInt(values[i]);
        }
        catch (NumberFormatException e)
        {
            throw new InvalidRequestException("worktime values must be valid integers");
        }
        if (time[0] < 0 || time[0] > 23 || time[2] < 0 || time[2] > 23
            || time[1] < 0 || time[1] > 59 || time[3] < 0 || time[3] > 59)
            throw new InvalidRequestException("Invalid worktime hour/minute");
        int start = time[0] * 60 + time[1], end = time[2] * 60 + time[3];
        if (start >= end)
            throw new InvalidRequestException("worktime start must be earlier than end");
        return new int[] { start, end };
    }

    public ResultMessage execute(ClientState state)
    {
        authorize(state);
        byte[] days = "workday".equals(attribute) ? workdays() : null;
        int[] window = days == null ? window() : null;
        IAuthorizer authorizer = DatabaseDescriptor.getAuthorizer();
        if (!(authorizer instanceof CassandraAuthorizer))
            throw new InvalidRequestException("CATEE is not enabled");
        try
        {
            if (days != null) ((CassandraAuthorizer) authorizer).setCateeWorkdays(days);
            else ((CassandraAuthorizer) authorizer).setCateeTimeWindow(window[0], window[1]);
        }
        catch (InvalidRequestException e) { throw e; }
        catch (IOException | RuntimeException | LinkageError e)
        {
            throw new InvalidRequestException("Required CATEE " + (days == null ? "window" : "workday") + " update failed: " + e.getMessage());
        }
        return null;
    }

    public AuditLogContext getAuditLogContext()
    {
        return new AuditLogContext(AuditLogEntryType.SET_ENV_ATTRIBUTE);
    }
}
