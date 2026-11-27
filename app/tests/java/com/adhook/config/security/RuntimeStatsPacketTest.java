package com.adhook.config.security;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeStatsPacketTest {
    private String managed(int index) {
        return "adhook:v2:676b64:736f75726365:636f6d2e686f7374:" + "61".repeat(120) + String.format("%08x", index);
    }

    @Test public void serverAcceptsNativeAndCanonicalStatsTogether() throws Exception {
        JSONObject states = new JSONObject().put("native-splash", "OK");
        JSONObject hits = new JSONObject().put("native-splash", 3).put(managed(1), 7);
        RuntimeStatsPolicy.Snapshot snapshot = RuntimeStatsPolicy.parse(new JSONObject().put("states", states).put("hits", hits).toString());
        assertNotNull(snapshot); assertEquals("OK", snapshot.states.get("native-splash"));
        assertEquals(Integer.valueOf(3), snapshot.hits.get("native-splash"));
        assertEquals(Integer.valueOf(7), snapshot.hits.get(managed(1)));
    }

    @Test public void oversizedProducerPacketKeepsNativeStatesAndHitsFirst() throws Exception {
        JSONObject states = new JSONObject();
        JSONObject hits = new JSONObject();
        for (int i = 0; i < 200; i++) { states.put(managed(i), "OK"); hits.put(managed(i), i); }
        states.put("native-splash", "OK"); hits.put("native-splash", 17);
        JSONObject source = new JSONObject().put("states", states).put("hits", hits);
        assertTrue(source.toString().length() > RuntimeStatsPolicy.MAX_JSON_LENGTH);
        RuntimeStatsPacket packet = RuntimeStatsPacket.prepare(source);
        assertNotNull(packet); assertTrue(packet.truncated);
        assertTrue(packet.json.length() <= RuntimeStatsPolicy.MAX_JSON_LENGTH);
        RuntimeStatsPolicy.Snapshot result = RuntimeStatsPolicy.parse(packet.json);
        assertNotNull(result);
        assertEquals("OK", result.states.get("native-splash"));
        assertEquals(Integer.valueOf(17), result.hits.get("native-splash"));
        assertTrue(result.states.size() <= 128); assertTrue(result.hits.size() <= 128);
        assertEquals(201, states.length()); // Producer data and rule execution are untouched.
        assertEquals(201, hits.length());
    }

    @Test public void captureIsImmutableAndDoesNotFlagAnOrdinarySmallReport() throws Exception {
        JSONObject hits = new JSONObject().put("native", 1);
        RuntimeStatsPacket packet = RuntimeStatsPacket.prepare(new JSONObject().put("hits", hits));
        assertNotNull(packet); assertFalse(packet.truncated);
        hits.put("native", 999);
        assertEquals(Integer.valueOf(1), RuntimeStatsPolicy.parse(packet.json).hits.get("native"));
    }
}
