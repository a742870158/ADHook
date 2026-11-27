package com.adhook.config.security;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class RuntimeStatsEnvelopeTest {
    private static final String HOST = "com.stats.host";
    private static final String MAIN = HOST;
    private static final String REMOTE = HOST + ":remote";
    private static final String SESSION_A = "11111111111111111111111111111111";
    private static final String SESSION_B = "22222222222222222222222222222222";
    private static final String SESSION_C = "33333333333333333333333333333333";

    private RuntimeStatsPolicy.Snapshot packet(String json) {
        RuntimeStatsPolicy.Snapshot snapshot = RuntimeStatsPolicy.parse(json);
        assertNotNull(json, snapshot);
        return snapshot;
    }

    private RuntimeStatsEnvelope replace(RuntimeStatsEnvelope previous, String process, String session, String json) {
        RuntimeStatsEnvelope next = RuntimeStatsEnvelope.replace(HOST, previous, packet(json), process, session, 123456L);
        assertNotNull(next);
        return next;
    }

    @Test public void auxiliarySnapshotDoesNotEraseMainProcessCountsOrStates() {
        RuntimeStatsEnvelope main = replace(null, MAIN, SESSION_A,
                "{\"states\":{\"splash\":\"OK\"},\"hits\":{\"splash\":11}}");
        RuntimeStatsEnvelope both = replace(main, REMOTE, SESSION_B,
                "{\"states\":{\"native\":\"FAILED\"},\"hits\":{\"native\":4}}");
        assertEquals(Integer.valueOf(11), both.aggregate.hits.get("splash"));
        assertEquals(Integer.valueOf(4), both.aggregate.hits.get("native"));
        assertEquals("OK", both.aggregate.states.get("splash"));
        assertEquals("FAILED", both.aggregate.states.get("native"));
        assertEquals(2, both.processes.size());
        assertEquals(1, main.processes.size()); // Copy-on-write, no mutation of the prior accepted state.
    }

    @Test public void repeatedFullSnapshotsAreNotAddedAsDeltas() {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":9}}");
        stats = replace(stats, REMOTE, SESSION_B, "{\"hits\":{\"splash\":7}}");
        for (int i = 0; i < 10; i++) {
            stats = replace(stats, MAIN, SESSION_A, "{\"hits\":{\"splash\":9}}");
            stats = replace(stats, REMOTE, SESSION_B, "{\"hits\":{\"splash\":7}}");
            assertEquals(Integer.valueOf(16), stats.aggregate.hits.get("splash"));
        }
    }

    @Test public void newSessionResetsOnlyItsNamedProcess() {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":10}}");
        stats = replace(stats, REMOTE, SESSION_B, "{\"hits\":{\"splash\":4,\"remoteOnly\":3}}");
        stats = replace(stats, MAIN, SESSION_C, "{\"hits\":{\"splash\":1}}");
        assertEquals(Integer.valueOf(5), stats.aggregate.hits.get("splash"));
        assertEquals(Integer.valueOf(3), stats.aggregate.hits.get("remoteOnly"));
        assertEquals(SESSION_C, stats.processes.get(MAIN).sessionId);
        assertEquals(SESSION_B, stats.processes.get(REMOTE).sessionId);
        assertEquals(2, stats.processes.size());
    }

    @Test public void statePrecedenceIsFailedThenOkThenDisabledAndOldStatesAreReplaced() {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"states\":{\"splash\":\"DISABLED\"}}");
        stats = replace(stats, REMOTE, SESSION_B, "{\"states\":{\"splash\":\"FAILED\"}}");
        assertEquals("FAILED", stats.aggregate.states.get("splash"));
        stats = replace(stats, MAIN, SESSION_A, "{\"states\":{\"splash\":\"OK\"}}");
        assertEquals("FAILED", stats.aggregate.states.get("splash"));
        stats = replace(stats, MAIN, SESSION_C, "{\"hits\":{}}");
        assertEquals("FAILED", stats.aggregate.states.get("splash"));
        stats = replace(stats, REMOTE, SESSION_B, "{\"states\":{\"splash\":\"DISABLED\"}}");
        assertEquals("DISABLED", stats.aggregate.states.get("splash"));
    }

    @Test public void sumsAreSaturatedAndCanDecreaseAfterReplacement() {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":700000000}}");
        stats = replace(stats, REMOTE, SESSION_B, "{\"hits\":{\"splash\":600000000}}");
        assertEquals(Integer.valueOf(RuntimeStatsPolicy.MAX_HIT_COUNT), stats.aggregate.hits.get("splash"));
        stats = replace(stats, MAIN, SESSION_C, "{\"hits\":{\"splash\":4}}");
        assertEquals(Integer.valueOf(600000004), stats.aggregate.hits.get("splash"));
    }

    @Test public void rotatingKeysWithinOneProcessNeverAccumulate() {
        RuntimeStatsEnvelope stats = replace(null, REMOTE, SESSION_B, "{\"hits\":{\"remoteOnly\":2}}");
        for (int i = 0; i < 300; i++) {
            stats = replace(stats, MAIN, SESSION_A,
                    "{\"states\":{\"f" + i + "\":\"OK\"},\"hits\":{\"f" + i + "\":1}}");
            assertEquals(1, stats.processes.get(MAIN).snapshot.hits.size());
            assertEquals(1, stats.processes.get(MAIN).snapshot.states.size());
            assertEquals(2, stats.aggregate.hits.size());
            assertEquals(1, stats.aggregate.states.size());
        }
        assertFalse(stats.aggregate.hits.containsKey("f0"));
        assertTrue(stats.aggregate.hits.containsKey("f299"));
    }

    @Test public void ninthProcessIsRejectedButExistingSlotCanStillBeReplaced() {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"main\":7}}");
        for (int i = 1; i < RuntimeStatsEnvelope.MAX_PROCESSES; i++) {
            stats = replace(stats, HOST + ":p" + i, SESSION_B, "{\"hits\":{\"remote\":1}}");
        }
        assertEquals(8, stats.processes.size());
        assertNull(RuntimeStatsEnvelope.replace(HOST, stats, packet("{\"hits\":{\"overflow\":1}}"),
                HOST + ":p8", SESSION_C, 999999L));
        assertEquals(Integer.valueOf(7), stats.aggregate.hits.get("main"));
        assertEquals(123456L, stats.updatedAt);
        RuntimeStatsEnvelope changed = replace(stats, MAIN, SESSION_C, "{\"hits\":{\"newMain\":1}}");
        assertEquals(8, changed.processes.size());
        assertFalse(changed.aggregate.hits.containsKey("main"));
        assertEquals(Integer.valueOf(7), changed.aggregate.hits.get("remote"));
    }

    @Test public void legacySnapshotsRemainWholeAppReplacementsAndAreNotDoubleCounted() throws Exception {
        RuntimeStatsEnvelope old = RuntimeStatsEnvelope.parse(HOST, "{\"hits\":{\"splash\":23}}");
        assertNotNull(old);
        assertEquals(0, old.processes.size());
        assertEquals(0L, old.updatedAt);
        RuntimeStatsEnvelope named = replace(old, MAIN, SESSION_A, "{\"hits\":{\"splash\":2}}");
        named = replace(named, REMOTE, SESSION_B, "{\"hits\":{\"splash\":3}}");
        assertEquals(Integer.valueOf(5), named.aggregate.hits.get("splash"));
        RuntimeStatsEnvelope legacy = RuntimeStatsEnvelope.legacy(HOST, packet("{\"hits\":{\"new\":7}}"), 91L);
        assertNotNull(legacy);
        assertEquals(0, legacy.processes.size());
        assertFalse(legacy.aggregate.hits.containsKey("splash"));
        assertEquals(Integer.valueOf(7), legacy.aggregate.hits.get("new"));
        RuntimeStatsEnvelope restored = RuntimeStatsEnvelope.parse(HOST, legacy.toJson().toString());
        assertNotNull(restored);
        assertEquals(91L, restored.updatedAt);
        assertEquals(legacy.aggregate.hits, restored.aggregate.hits);
    }

    @Test public void versionedRoundTripPreservesProcessSessionsTimesAndProducerTruncation() throws Exception {
        RuntimeStatsEnvelope stats = RuntimeStatsEnvelope.replace(HOST, null, packet("{\"hits\":{\"splash\":7}}"),
                MAIN, SESSION_A, 123456789L, true);
        stats = RuntimeStatsEnvelope.replace(HOST, stats, packet("{\"hits\":{\"splash\":3}}"),
                REMOTE, SESSION_B, 123456999L, false);
        assertNotNull(stats);
        JSONObject stored = stats.toJson();
        assertEquals(2, stored.getInt("schemaVersion"));
        assertEquals(123456999L, stored.getLong("updatedAt"));
        assertTrue(stored.getJSONObject("processes").getJSONObject(MAIN).getBoolean("truncated"));
        assertFalse(stored.has("legacySnapshot"));
        RuntimeStatsEnvelope restored = RuntimeStatsEnvelope.parse(HOST, stored.toString());
        assertNotNull(restored);
        assertTrue(restored.truncated);
        assertEquals(2, restored.processes.size());
        assertEquals(SESSION_B, restored.processes.get(REMOTE).sessionId);
        assertEquals(Integer.valueOf(10), restored.aggregate.hits.get("splash"));
        assertEquals(stats.updatedAt, restored.updatedAt);
        RuntimeStatsEnvelope complete = RuntimeStatsEnvelope.replace(HOST, restored, packet("{\"hits\":{\"splash\":1}}"),
                MAIN, SESSION_C, 123457000L, false);
        assertNotNull(complete);
        assertFalse(complete.truncated); // Replacing the only truncated process clears its old diagnostic.
        assertEquals(Integer.valueOf(4), complete.aggregate.hits.get("splash"));
    }

    @Test public void missingOptionalStoredTruncationIsFalseButItsTypeIsStrict() throws Exception {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":1}}");
        JSONObject stored = stats.toJson();
        JSONObject process = stored.getJSONObject("processes").getJSONObject(MAIN);
        process.remove("truncated");
        RuntimeStatsEnvelope restored = RuntimeStatsEnvelope.parse(HOST, stored.toString());
        assertNotNull(restored);
        assertFalse(restored.truncated);
        process.put("truncated", "false");
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        process.put("truncated", JSONObject.NULL);
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
    }

    @Test public void aggregateFieldBoundsAreIndependentOfEachProcessPacket() throws Exception {
        RuntimeStatsEnvelope stats = null;
        for (int process = 0; process < 8; process++) {
            JSONObject states = new JSONObject();
            JSONObject hits = new JSONObject();
            for (int feature = 0; feature < 64; feature++) {
                String key = String.format(java.util.Locale.ROOT, "p%d-f%03d", process, feature);
                states.put(key, "OK"); hits.put(key, 1);
            }
            stats = RuntimeStatsEnvelope.replace(HOST, stats, packet(new JSONObject()
                    .put("states", states).put("hits", hits).toString()), HOST + ":p" + process, SESSION_A, process);
            assertNotNull(stats);
            assertEquals(64, stats.processes.get(HOST + ":p" + process).snapshot.hits.size());
        }
        assertTrue(stats.truncated);
        assertEquals(128, stats.aggregate.hits.size());
        assertEquals(128, stats.aggregate.states.size());
        assertTrue(stats.aggregate.toJson().toString().length() <= RuntimeStatsPolicy.MAX_JSON_LENGTH);
        assertTrue(stats.toJson().toString().length() <= RuntimeStatsEnvelope.MAX_JSON_LENGTH);
        RuntimeStatsEnvelope restored = RuntimeStatsEnvelope.parse(HOST, stats.toJson().toString());
        assertNotNull(restored);
        assertEquals(8, restored.processes.size());
        assertTrue(restored.truncated);
        assertEquals(stats.aggregate.hits, restored.aggregate.hits);
        assertEquals(stats.aggregate.states, restored.aggregate.states);
    }

    @Test public void aggregatePayloadBoundsRetainNativeTelemetryBeforeLongManagedIds() throws Exception {
        RuntimeStatsEnvelope stats = null;
        for (int process = 0; process < 8; process++) {
            JSONObject states = new JSONObject().put("native-splash", "OK");
            JSONObject hits = new JSONObject().put("native-splash", 2);
            for (int key = 0; key < 3; key++) hits.put(managed(process * 3 + key), 1);
            RuntimeStatsPolicy.Snapshot snapshot = packet(new JSONObject().put("states", states).put("hits", hits).toString());
            stats = RuntimeStatsEnvelope.replace(HOST, stats, snapshot, HOST + ":p" + process, SESSION_A, process);
            assertNotNull(stats);
        }
        assertTrue(stats.truncated);
        assertEquals("OK", stats.aggregate.states.get("native-splash"));
        assertEquals(Integer.valueOf(16), stats.aggregate.hits.get("native-splash"));
        assertTrue(stats.aggregate.hits.size() < 25);
        assertTrue(stats.aggregate.toJson().toString().length() <= RuntimeStatsPolicy.MAX_JSON_LENGTH);
        assertNotNull(RuntimeStatsPolicy.parse(stats.aggregate.toJson().toString()));
        assertNotNull(RuntimeStatsEnvelope.parse(HOST, stats.toJson().toString()));
    }

    private String managed(int index) {
        return "adhook:v2:676b64:736f75726365:636f6d2e73746174732e686f7374:" + "61".repeat(900)
                + String.format(java.util.Locale.ROOT, "%08x", index);
    }

    @Test public void rejectsWholeStoredEnvelopeForInvalidInnerPacketMetadataOrUnknownFields() throws Exception {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":1}}");
        JSONObject stored = stats.toJson();
        stored.getJSONObject("processes").getJSONObject(MAIN).getJSONObject("snapshot")
                .getJSONObject("hits").put("poison", -1);
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.put("aggregate", new JSONObject());
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.put("schemaVersion", "2");
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.put("updatedAt", -1);
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.put("updatedAt", 2.5);
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.getJSONObject("processes").getJSONObject(MAIN).put("sessionId", "bad");
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.getJSONObject("processes").getJSONObject(MAIN).remove("sessionId");
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.getJSONObject("processes").put("com.other.host",
                stored.getJSONObject("processes").getJSONObject(MAIN));
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson(); stored.put("legacySnapshot", packet("{\"hits\":{}}").toJson());
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        assertNull(RuntimeStatsEnvelope.parse(HOST, stats.toJson() + " trailing"));
        assertNull(RuntimeStatsEnvelope.parse(HOST, " ".repeat(RuntimeStatsEnvelope.MAX_JSON_LENGTH + 1)));
        assertNull(RuntimeStatsEnvelope.parse(HOST, "{\"schemaVersion\":2,\"processes\":" + "{".repeat(8000)));
        assertNull(RuntimeStatsEnvelope.replace(HOST, stats, packet("{\"hits\":{}}"), MAIN, "bad", 0L));
        assertNull(RuntimeStatsEnvelope.replace("com.other.host", stats, packet("{\"hits\":{}}"),
                "com.other.host", SESSION_A, 0L));
    }

    @Test public void rejectsOversizedStoredProcessCountAndPacketWithoutWeakeningServerPolicy() throws Exception {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":1}}");
        JSONObject stored = stats.toJson();
        JSONObject processes = stored.getJSONObject("processes");
        JSONObject process = processes.getJSONObject(MAIN);
        for (int i = 0; i < 8; i++) processes.put(HOST + ":p" + i, process);
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        stored = stats.toJson();
        JSONObject hits = stored.getJSONObject("processes").getJSONObject(MAIN)
                .getJSONObject("snapshot").getJSONObject("hits");
        for (int i = 0; i < 128; i++) hits.put("f" + i, 1);
        assertNull(RuntimeStatsEnvelope.parse(HOST, stored.toString()));
        assertNull(RuntimeStatsPolicy.parse(stored.toString())); // An envelope is never a valid wire statsJson.
    }

    @Test public void acceptedMapsAndJsonCopiesCannotMutateStoredProcessState() throws Exception {
        RuntimeStatsEnvelope stats = replace(null, MAIN, SESSION_A, "{\"hits\":{\"splash\":1}}");
        try { stats.processes.clear(); fail("expected immutable process map"); }
        catch (UnsupportedOperationException expected) { }
        try { stats.processes.get(MAIN).snapshot.hits.put("poison", 2); fail("expected immutable snapshot"); }
        catch (UnsupportedOperationException expected) { }
        JSONObject copy = stats.toJson();
        copy.getJSONObject("processes").getJSONObject(MAIN).getJSONObject("snapshot")
                .getJSONObject("hits").put("poison", 2);
        assertFalse(stats.aggregate.hits.containsKey("poison"));
        assertFalse(stats.processes.get(MAIN).snapshot.hits.containsKey("poison"));
    }
}
