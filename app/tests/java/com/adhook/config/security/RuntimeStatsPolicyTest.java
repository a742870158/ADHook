package com.adhook.config.security;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeStatsPolicyTest {
    @Test public void acceptsOnlyKnownStateAndBoundedIntegerCounts() throws Exception {
        RuntimeStatsPolicy.Snapshot snapshot = RuntimeStatsPolicy.parse("{\"states\":{\"splash\":\"OK\",\"native\":\"FAILED\",\"off\":\"DISABLED\"},\"hits\":{\"splash\":1000000000}}");
        assertNotNull(snapshot); assertEquals(Integer.valueOf(1_000_000_000), snapshot.hits.get("splash"));
        assertEquals("OK", snapshot.states.get("splash"));
        assertNotNull(RuntimeStatsPolicy.parse(snapshot.toJson().toString()));
    }

    @Test public void rejectsUnknownFieldsTypesStatesAndCoercedCounts() {
        for (String json : new String[]{
            "{\"evil\":1}", "{\"states\":[]}", "{\"states\":null}", "{\"states\":{\"splash\":\"arbitrary\"}}",
            "{\"hits\":{\"splash\":\"7\"}}", "{\"hits\":{\"splash\":true}}", "{\"hits\":{\"splash\":1.0}}",
            "{\"hits\":{\"splash\":-1}}", "{\"hits\":{\"splash\":1000000001}}", "{\"hits\":{\"splash\":9223372036854775807}}",
            "{\"states\":{\"bad/id\":\"OK\"}}", "{\"states\":{\"splash\":{\"nested\":1}}}", "{\"hits\":{}} trailing"
        }) assertNull(json, RuntimeStatsPolicy.parse(json));
    }

    @Test public void rejectsWholePacketRatherThanKeepingItsValidSubset() {
        assertNull(RuntimeStatsPolicy.parse("{\"states\":{\"good\":\"OK\",\"bad\":\"POISON\"},\"hits\":{\"good\":1}}"));
    }

    @Test public void boundsFieldCountsIdentifierLengthAndPayloadLength() throws Exception {
        JSONObject fields = new JSONObject();
        for (int i = 0; i < 128; i++) fields.put("f" + i, 1);
        assertNotNull(RuntimeStatsPolicy.parse(new JSONObject().put("hits", fields).toString()));
        fields.put("overflow", 1);
        assertNull(RuntimeStatsPolicy.parse(new JSONObject().put("hits", fields).toString()));
        assertNull(RuntimeStatsPolicy.parse(new JSONObject().put("hits", new JSONObject().put("x".repeat(129), 1)).toString()));
        assertNull(RuntimeStatsPolicy.parse(" ".repeat(RuntimeStatsPolicy.MAX_JSON_LENGTH + 1)));
        assertNull(RuntimeStatsPolicy.parse("{\"states\":" + "{".repeat(8000)));
    }

    @Test public void acceptsCanonicalRuleHitIdsWithoutAcceptingArbitraryLongFeatureIds() throws Exception {
        String id = "adhook:v2:676b64:736f75726365:636f6d2e686f7374:" + "61".repeat(180);
        assertNotNull(RuntimeStatsPolicy.parse(new JSONObject().put("hits", new JSONObject().put(id, 1)).toString()));
        assertNull(RuntimeStatsPolicy.parse(new JSONObject().put("hits", new JSONObject().put(id + ":bad", 1)).toString()));
    }

    @Test public void snapshotsAreImmutable() {
        RuntimeStatsPolicy.Snapshot snapshot = RuntimeStatsPolicy.parse("{\"hits\":{\"splash\":1}}");
        assertNotNull(snapshot);
        try { snapshot.hits.put("poison", 2); fail("expected immutable map"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void rateLimitIsPerUidAndHasBothFrequencyAndWindowCaps() {
        StatsRateLimiter limiter = new StatsRateLimiter();
        assertTrue(limiter.tryAcquire(12, 0));
        assertFalse(limiter.tryAcquire(12, 0)); assertFalse(limiter.tryAcquire(12, 999));
        assertTrue(limiter.tryAcquire(13, 0));
        for (int i = 1; i < StatsRateLimiter.MAX_REPORTS_PER_WINDOW; i++) assertTrue(limiter.tryAcquire(12, i * 1000L));
        assertFalse(limiter.tryAcquire(12, 59_999));
        assertTrue(limiter.tryAcquire(12, 60_000));
        assertFalse(limiter.tryAcquire(-1, 70_000));
    }
}
