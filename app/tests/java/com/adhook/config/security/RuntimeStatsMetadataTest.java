package com.adhook.config.security;

import org.junit.Test;

import static org.junit.Assert.*;

public class RuntimeStatsMetadataTest {
    private static final String HOST = "com.stats.host";
    private static final String SESSION = "1234567890abcdef1234567890abcdef";

    @Test public void acceptsMainPrivateAndStableSharedProcessSlots() {
        assertTrue(RuntimeStatsMetadata.isValid(HOST, HOST, SESSION));
        assertTrue(RuntimeStatsMetadata.isValid(HOST, HOST + ":remote", SESSION));
        assertTrue(RuntimeStatsMetadata.isValid(HOST, HOST + ":PushService_2", SESSION));
        assertTrue(RuntimeStatsMetadata.isValid(HOST, HOST + ":shared-12ab90", SESSION));
        assertTrue(RuntimeStatsMetadata.isValid(HOST, HOST + ":" + "a".repeat(128), SESSION));
        assertTrue(RuntimeStatsMetadata.isSessionId(SESSION.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test public void rejectsWrongPackageUnboundedAndNonAsciiProcessNames() {
        for (String process : new String[]{null, "", "com.other.host", HOST + "other", HOST + ":",
                HOST + ":remote:other", HOST + ":remote/name", HOST + ":remote name", HOST + ":remote\n",
                HOST + ":远程", HOST + ":" + "a".repeat(129)}) {
            assertFalse(String.valueOf(process), RuntimeStatsMetadata.isValid(HOST, process, SESSION));
        }
        assertFalse(RuntimeStatsMetadata.isValid("not a package", "not a package", SESSION));
        assertFalse(RuntimeStatsMetadata.isValid(null, HOST, SESSION));
    }

    @Test public void rejectsMissingMalformedAndNonUuidSessionMetadata() {
        for (String session : new String[]{null, "", "1".repeat(31), "1".repeat(33), "g".repeat(32),
                "12345678-1234-1234-1234-1234567890ab", "1".repeat(31) + "\n"}) {
            assertFalse(String.valueOf(session), RuntimeStatsMetadata.isValid(HOST, HOST, session));
        }
        assertFalse(RuntimeStatsMetadata.isValid(HOST, null, SESSION));
        assertFalse(RuntimeStatsMetadata.isValid(HOST, HOST, null));
    }
}
