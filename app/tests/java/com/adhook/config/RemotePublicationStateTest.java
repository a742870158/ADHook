package com.adhook.config;

import org.junit.Test;

import static org.junit.Assert.*;

public class RemotePublicationStateTest {
    @Test public void desiredCloseIsNeverRolledBackOrAcknowledgedByAnOlderCommit() {
        RemotePublicationState state = new RemotePublicationState(1L, 1L, null, 1);
        assertTrue(state.isConfirmed());
        state = state.desired(2L);
        assertFalse(state.isConfirmed());
        assertEquals(2L, state.desiredVersion);
        assertEquals(1L, state.publishedVersion);
        assertSame(state, state.result(1L, true, null, 1));
        state = state.result(2L, false, "COMMIT_FAILED", 4);
        assertEquals(2L, state.desiredVersion);
        assertEquals(1L, state.publishedVersion);
        assertEquals("COMMIT_FAILED", state.error);
        assertEquals(4, state.attempts);
        assertFalse(state.isConfirmed());
        state = state.result(2L, true, null, 4);
        assertTrue(state.isConfirmed());
        assertEquals(2L, state.publishedVersion);
    }

    @Test public void unavailableAndDeadServiceRemainAnExplicitUnconfirmedState() {
        RemotePublicationState state = new RemotePublicationState(0L, 0L, null, 0).desired(5L);
        state = state.result(5L, false, "SERVICE_UNAVAILABLE", 0);
        assertFalse(state.isConfirmed());
        assertEquals(0L, state.publishedVersion);
        state = state.result(5L, true, null, 1);
        assertTrue(state.isConfirmed());
        state = state.result(5L, false, "SERVICE_DIED", 0);
        assertFalse(state.isConfirmed());
        assertEquals(5L, state.publishedVersion);
        assertSame(state, state.desired(4L));
    }
}
