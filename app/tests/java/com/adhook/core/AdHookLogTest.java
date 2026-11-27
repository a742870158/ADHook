package com.adhook.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class AdHookLogTest {
    private static final int DEBUG = 3;
    private static final int INFO = 4;
    private static final int WARN = 5;
    private static final int ERROR = 6;

    @Test public void productionBridgesErrorsOnly() {
        AdHookLog.BridgePolicy policy = new AdHookLog.BridgePolicy();
        assertFalse(policy.acquire(DEBUG, "debug", 0));
        assertFalse(policy.acquire(INFO, "info", 0));
        assertFalse(policy.acquire(WARN, "warn", 0));
        assertTrue(policy.acquire(ERROR, "error", 0));
    }

    @Test public void explicitOffAlsoDisablesErrors() {
        AdHookLog.BridgePolicy policy = new AdHookLog.BridgePolicy();
        policy.setMode(AdHookLog.BridgeMode.OFF);
        assertFalse(policy.acquire(ERROR, "error", 0));
    }

    @Test public void explicitAllEnablesDiagnosticLevels() {
        AdHookLog.BridgePolicy policy = new AdHookLog.BridgePolicy();
        policy.setMode(AdHookLog.BridgeMode.ALL);
        assertTrue(policy.acquire(DEBUG, "debug", 0));
        assertTrue(policy.acquire(INFO, "info", 0));
    }

    @Test public void repeatedErrorsAreDeduplicatedWithoutPermanentSuppression() {
        AdHookLog.BridgePolicy policy = new AdHookLog.BridgePolicy();
        assertTrue(policy.acquire(ERROR, "same", 0));
        assertFalse(policy.acquire(ERROR, "same", 1_500));
        assertTrue(policy.acquire(ERROR, "same", 10_000));
    }

    @Test public void distinctMessagesCannotBypassTheWindowBudget() {
        AdHookLog.BridgePolicy policy = new AdHookLog.BridgePolicy();
        for (int i = 0; i < AdHookLog.BridgePolicy.MAX_PER_WINDOW; i++) {
            assertTrue(policy.acquire(ERROR, "message" + i, 0));
        }
        assertFalse(policy.acquire(ERROR, "overflow", 999));
        assertTrue(policy.acquire(ERROR, "overflow", 1_000));
    }

    @Test public void cacheRemainsBounded() {
        AdHookLog.BridgePolicy policy = new AdHookLog.BridgePolicy();
        for (int i = 0; i < 500; i++) {
            assertTrue(policy.acquire(ERROR, "message" + i, i * 1_000L));
        }
        assertEquals(AdHookLog.BridgePolicy.MAX_KEYS, policy.cachedMessageCount());
    }
}
