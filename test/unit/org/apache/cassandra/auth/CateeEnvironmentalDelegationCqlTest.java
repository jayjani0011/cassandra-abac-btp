package org.apache.cassandra.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import org.apache.cassandra.auth.catee.CateeClient;

import static org.junit.Assert.*;

/** Real CQL/SGX delegation, persistence, revocation and lifecycle checks. */
public class CateeEnvironmentalDelegationCqlTest extends CateeCqlTestSupport
{
    private static final String[] SETTINGS = {
        "SET ENV_ATTRIBUTE worktime = 10,0,11,0",
        "SET ENV_ATTRIBUTE workday = sunday,saturday",
        "SET ENV_ATTRIBUTE subnet = delegated='10.0.0.0/8'"
    };

    private void expectSettingsDenied(String message) throws Throwable
    {
        for (String command : SETTINGS) assertInvalidMessageNet(message, command);
    }

    @Test
    public void environmentalDelegationSurvivesRestartAndIsRevokedAndDeleted() throws Throwable
    {
        useSuperUser();
        executeNet("CREATE ROLE ea_delegate WITH LOGIN=true AND PASSWORD='test-password'");
        executeNet("CREATE ROLE ea_other WITH LOGIN=true AND PASSWORD='test-password'");
        executeNet("CREATE ROLE ea_parent WITH LOGIN=false");
        executeNet("GRANT ea_parent TO ea_delegate");
        CateeClient client = new CateeClient(socket, 2000);
        int ea = 512, policy = 128;
        client.grantAdminPrivilege("ea_parent", ea); // Parent grants must not substitute for a primary-role grant.
        useUser("ea_delegate", "test-password");
        expectSettingsDenied("SET_EA_ADMIN");
        useSuperUser();
        assertInvalidMessageNet("LOGIN = true", "GRANT ABAC_ADMIN { SET_EA_ADMIN } TO ea_parent");
        assertInvalidMessageNet("does not exist", "GRANT ABAC_ADMIN { SET_EA_ADMIN } TO ea_missing");
        executeNet("GRANT ABAC_ADMIN { SET_EA_ADMIN, CREATE_POLICY } TO ea_delegate");
        useUser("ea_delegate", "test-password");
        com.datastax.driver.core.Session existingSession = sessionNet();
        for (String command : SETTINGS) executeNet(command);
        assertFalse(client.checkWorktime(840));
        assertTrue(client.checkEnvironment(0, 630, 167772161, "delegated", true, true, true, true, true));
        assertInvalidMessageNet("Only superusers", "GRANT ABAC_ADMIN { SET_EA_ADMIN } TO ea_other");
        assertInvalidMessageNet("Only superusers", "REVOKE ABAC_ADMIN { SET_EA_ADMIN } FROM ea_delegate");
        assertInvalidMessageNet("has no CATEE", "CREATE ATTRIBUTE ea_forbidden WITH TYPE text");
        assertInvalidMessageNet("hour/minute", "SET ENV_ATTRIBUTE worktime = 9,60,17,0");
        assertInvalidMessageNet("Invalid workday", "SET ENV_ATTRIBUTE workday = funday");
        assertInvalidMessageNet("host bits", "SET ENV_ATTRIBUTE subnet = delegated='10.0.0.1/8'");
        assertTrue(client.checkEnvironment(0, 630, 167772161, "delegated", true, true, true, true, true));
        useSuperUser();
        restartService();
        useUser("ea_delegate", "test-password");
        for (String command : SETTINGS) executeNet(command);
        useSuperUser();
        executeNet("REVOKE ABAC_ADMIN { SET_EA_ADMIN } FROM ea_delegate");
        assertTrue(client.checkAdminPrivilege("ea_delegate", policy));
        assertFalse(client.checkAdminPrivilege("ea_delegate", ea));
        try { existingSession.execute(SETTINGS[0]); fail("Cached session retained delegation"); }
        catch (com.datastax.driver.core.exceptions.UnauthorizedException expected) { }
        restartService();
        useUser("ea_delegate", "test-password");
        expectSettingsDenied("SET_EA_ADMIN");
        useSuperUser();
        Path pending = Path.of(socket + ".sealed.tmp");
        Path stateFile = Path.of(socket + ".sealed");
        byte[] acknowledged = Files.readAllBytes(stateFile);
        Files.writeString(pending, "preserve", java.nio.file.StandardOpenOption.CREATE_NEW);
        try
        {
            assertInvalidMessageNet("Required CATEE grant update failed", "GRANT ABAC_ADMIN { SET_EA_ADMIN } TO ea_delegate");
            assertTrue(service.waitFor(3, TimeUnit.SECONDS));
            assertNotEquals(0, service.exitValue());
            assertArrayEquals(acknowledged, Files.readAllBytes(stateFile));
        }
        finally { Files.deleteIfExists(pending); }
        restartService();
        useUser("ea_delegate", "test-password");
        expectSettingsDenied("SET_EA_ADMIN");
        useSuperUser();
        executeNet("GRANT ABAC_ADMIN { SET_EA_ADMIN } TO ea_delegate");
        executeNet("DROP ROLE ea_delegate");
        assertFalse(client.checkAdminPrivilege("ea_delegate", ea | policy));
        executeNet("CREATE ROLE ea_delegate WITH LOGIN=true AND PASSWORD='test-password'");
        try { existingSession.execute(SETTINGS[1]); fail("Recreated role inherited delegation"); }
        catch (com.datastax.driver.core.exceptions.UnauthorizedException expected) { }
        executeNet("GRANT ABAC_ADMIN { SET_EA_ADMIN } TO ea_delegate");
        useUser("ea_delegate", "test-password");
        terminateService(true);
        expectSettingsDenied("Required CATEE administration check failed");
        useSuperUser();
        assertInvalidMessageNet("Required CATEE window update failed", SETTINGS[0]);
        assertInvalidMessageNet("Required CATEE workday update failed", SETTINGS[1]);
        assertInvalidMessageNet("Required CATEE subnet update failed", SETTINGS[2]);
        restartService();
        useUser("ea_delegate", "test-password");
        for (String command : SETTINGS) executeNet(command);
        useSuperUser();
        executeNet("SET ENV_ATTRIBUTE worktime = 9,0,17,0");
        executeNet("SET ENV_ATTRIBUTE workday = monday,tuesday,wednesday,thursday,friday");
        executeNet("DROP ROLE ea_delegate");
        executeNet("DROP ROLE ea_other");
        executeNet("DROP ROLE ea_parent");
    }
}
