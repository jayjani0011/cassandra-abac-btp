package org.apache.cassandra.auth;

import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.cassandra.audit.AuditLogEntryType;
import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.statements.SetEnvironmentAttributeStatement;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.exceptions.UnauthorizedException;
import org.apache.cassandra.service.ClientState;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CateeEnvironmentConfigurationTest extends CQLTester
{
    private IAuthorizer previous;
    private ConfigurationAuthorizer authorizer;
    private ClientState state;

    private static class ConfigurationAuthorizer extends CassandraAuthorizer
    {
        int calls, start, end, failure;
        byte[] days;
        public void setCateeWorkdays(byte[] values) throws IOException
        {
            calls++;
            if (failure != 0) throw new IOException("Service unavailable");
            days = values.clone();
        }
        public void setCateeTimeWindow(int startMinute, int endMinute) throws IOException
        {
            calls++;
            if (failure == 1) throw new IOException("Service unavailable");
            if (failure == 2) throw new IllegalStateException("Transport failure");
            if (failure == 3) throw new UnsatisfiedLinkError("Transport unavailable");
            start = startMinute;
            end = endMinute;
        }
    }

    @Before
    public void configure()
    {
        previous = DatabaseDescriptor.getAuthorizer();
        authorizer = new ConfigurationAuthorizer();
        DatabaseDescriptor.setAuthorizer(authorizer);
        state = mock(ClientState.class);
        when(state.isSuper()).thenReturn(true);
    }

    @After
    public void restore()
    {
        DatabaseDescriptor.setAuthorizer(previous);
    }

    private SetEnvironmentAttributeStatement parse(String query)
    {
        return (SetEnvironmentAttributeStatement) QueryProcessor.parseStatement(query);
    }

    private void configure(String values)
    {
        SetEnvironmentAttributeStatement statement = parse("SET ENV_ATTRIBUTE worktime = " + values);
        statement.validate(state);
        statement.authorize(state);
        statement.execute(state);
    }

    @Test
    public void convertsHoursAndMinutesAndProvidesAuditContext()
    {
        configure("9, 15, 17, 30");
        assertEquals(555, authorizer.start);
        assertEquals(1050, authorizer.end);
        configure("0, 0, 23, 59");
        assertEquals(0, authorizer.start);
        assertEquals(1439, authorizer.end);
        configure("23, 58, 23, 59");
        assertEquals(1438, authorizer.start);
        assertEquals(AuditLogEntryType.SET_ENV_ATTRIBUTE,
                     parse("SET ENV_ATTRIBUTE worktime = 9,0,17,0").getAuditLogContext().auditLogEntryType);
    }

    @Test
    public void rejectsInvalidRangesAndIntegerOverflowBeforeSending()
    {
        for (String values : new String[] {"-1,0,17,0", "24,0,17,0", "9,-1,17,0", "9,60,17,0",
                                           "9,0,24,0", "9,0,17,-1", "9,0,17,60", "9,0,9,0",
                                           "17,0,9,0", "2147483648,0,17,0", "-2147483649,0,17,0"})
        {
            try { configure(values); fail(values); }
            catch (InvalidRequestException expected) { }
        }
        assertEquals(0, authorizer.calls);
    }

    @Test
    public void rejectsUnsupportedAttributesAndMalformedSyntax()
    {
        try { parse("SET ENV_ATTRIBUTE subnet = 9,0,17,0").execute(state); fail(); }
        catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("Unsupported")); }
        for (String query : new String[] {"SET ENV_ATTRIBUTE worktime = 9,0,17",
                                          "SET ENV_ATTRIBUTE worktime = 9,0,17,0,1",
                                          "SET ENV_ATTRIBUTE worktime = '9',0,17,0",
                                          "SET ENV_ATTRIBUTE worktime = ?,0,17,0",
                                          "SET ENV_ATTRIBUTE worktime = 9.5,0,17,0"})
        {
            try { QueryProcessor.parseStatement(query); fail(query); }
            catch (SyntaxException expected) { }
        }
        assertEquals(0, authorizer.calls);
    }

    @Test
    public void configuresNamedWorkingDaysAndRejectsInvalidNames()
    {
        parse("SET ENV_ATTRIBUTE workday = monday,tuesday,wednesday,thursday,friday").execute(state);
        assertArrayEquals(new byte[] {0,1,1,1,1,1,0}, authorizer.days);
        parse("set env_attribute workday = SUNDAY,saturday,sunday").execute(state);
        assertArrayEquals(new byte[] {1,0,0,0,0,0,1}, authorizer.days);
        for (String value : new String[] {"funday", "9,0,17,0"})
        {
            try { parse("SET ENV_ATTRIBUTE workday = " + value).execute(state); fail(value); }
            catch (InvalidRequestException expected) { }
        }
        assertEquals(2, authorizer.calls);
    }

    @Test
    public void workdayAuthorizationDisabledModeAndTransportFailure()
    {
        when(state.isSuper()).thenReturn(false);
        try { parse("SET ENV_ATTRIBUTE workday = monday").execute(state); fail(); }
        catch (UnauthorizedException expected) { }
        when(state.isSuper()).thenReturn(true);
        authorizer.failure = 1;
        try { parse("SET ENV_ATTRIBUTE workday = monday").execute(state); fail(); }
        catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("workday update failed")); }
        String previousSocket = CassandraRelevantProperties.CATEE_SOCKET.getString();
        try
        {
            CassandraRelevantProperties.CATEE_SOCKET.clearValue();
            DatabaseDescriptor.setAuthorizer(new CassandraAuthorizer());
            try { parse("SET ENV_ATTRIBUTE workday = monday").execute(state); fail(); }
            catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("not enabled")); }
        }
        finally
        {
            if (previousSocket == null) CassandraRelevantProperties.CATEE_SOCKET.clearValue();
            else CassandraRelevantProperties.CATEE_SOCKET.setString(previousSocket);
        }
    }

    @Test
    public void keywordDoesNotBreakOrdinaryIdentifiers()
    {
        assertNotNull(QueryProcessor.parseStatement("CREATE TABLE ks.env_attribute (id int PRIMARY KEY)"));
        assertNotNull(QueryProcessor.parseStatement("CREATE ROLE env_attribute WITH LOGIN=false"));
        parse("set env_attribute WORKTIME = 9,0,17,0").execute(state);
        assertEquals(1, authorizer.calls);
    }

    @Test
    public void rejectsNonSuperuserAndAnonymousEvenOnDirectExecution()
    {
        when(state.isSuper()).thenReturn(false);
        try { parse("SET ENV_ATTRIBUTE worktime = 9,0,17,0").execute(state); fail(); }
        catch (UnauthorizedException expected) { }
        when(state.isSuper()).thenReturn(true);
        doThrow(new UnauthorizedException("Anonymous caller")).when(state).ensureNotAnonymous();
        try { parse("SET ENV_ATTRIBUTE worktime = 9,0,17,0").execute(state); fail(); }
        catch (UnauthorizedException expected) { }
        assertEquals(0, authorizer.calls);
    }

    @Test
    public void rejectsDisabledAndOtherAuthorizers()
    {
        String previousSocket = CassandraRelevantProperties.CATEE_SOCKET.getString();
        try
        {
            CassandraRelevantProperties.CATEE_SOCKET.clearValue();
            DatabaseDescriptor.setAuthorizer(new CassandraAuthorizer());
            try { configure("9,0,17,0"); fail(); }
            catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("not enabled")); }
            DatabaseDescriptor.setAuthorizer(new AllowAllAuthorizer());
            try { configure("9,0,17,0"); fail(); }
            catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("not enabled")); }
        }
        finally
        {
            if (previousSocket == null) CassandraRelevantProperties.CATEE_SOCKET.clearValue();
            else CassandraRelevantProperties.CATEE_SOCKET.setString(previousSocket);
        }
    }

    @Test
    public void transportFailuresNeverReportSuccess()
    {
        for (int failure = 1; failure <= 3; ++failure)
        {
            authorizer.failure = failure;
            try { configure("9,0,17,0"); fail(); }
            catch (InvalidRequestException expected) { assertTrue(expected.getMessage().contains("window update failed")); }
        }
        assertEquals(3, authorizer.calls);
    }
}
