package com.adhook.config.security;

import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded producer-side capture; server-side validation remains strict and independent. */
public final class RuntimeStatsPacket {
    public final String json;
    public final boolean truncated;

    private RuntimeStatsPacket(String json, boolean truncated) {
        this.json = json;
        this.truncated = truncated;
    }

    /** Preserve ordinary feature states AND hits before spending space on managed-rule telemetry. */
    public static RuntimeStatsPacket prepare(JSONObject source) {
        if (source == null) return null;
        try {
            Capture capture = new Capture();
            JSONObject states = source.optJSONObject("states");
            JSONObject hits = source.optJSONObject("hits");
            capture.truncated = (source.has("states") && states == null) || (source.has("hits") && hits == null);
            Iterator<String> rootKeys = source.keys();
            while (rootKeys.hasNext()) {
                String key = rootKeys.next();
                if (!"states".equals(key) && !"hits".equals(key)) capture.truncated = true;
            }
            capture.add(states, true, false);
            capture.add(hits, false, false);
            capture.add(states, true, true);
            capture.add(hits, false, true);
            String json = new JSONObject().put("states", new JSONObject(capture.states))
                    .put("hits", new JSONObject(capture.hits)).toString();
            return json.length() <= RuntimeStatsPolicy.MAX_JSON_LENGTH
                    ? new RuntimeStatsPacket(json, capture.truncated) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class Capture {
        final Map<String, String> states = new LinkedHashMap<>();
        final Map<String, Integer> hits = new LinkedHashMap<>();
        // Conservatively reserve punctuation/root overhead; validated keys and values are ASCII.
        int size = 64;
        boolean truncated;

        void add(JSONObject source, boolean isState, boolean managedPass) {
            if (source == null) return;
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                boolean managed = key.startsWith("adhook:v2:");
                if (managed != managedPass) continue;
                if (!RuntimeStatsPolicy.isStatsKey(key)) { truncated = true; continue; }
                Object value = source.opt(key);
                if (isState) {
                    if (!("OK".equals(value) || "FAILED".equals(value) || "DISABLED".equals(value))) {
                        truncated = true; continue;
                    }
                } else {
                    if (!(value instanceof Integer || value instanceof Long)
                            || ((Number) value).longValue() < 0 || ((Number) value).longValue() > RuntimeStatsPolicy.MAX_HIT_COUNT) {
                        truncated = true; continue;
                    }
                }
                int cost = key.length() + value.toString().length() + 10;
                int fields = isState ? states.size() : hits.size();
                if (fields >= RuntimeStatsPolicy.MAX_FIELDS_PER_SECTION || size + cost > RuntimeStatsPolicy.MAX_JSON_LENGTH) {
                    truncated = true; continue;
                }
                size += cost;
                if (isState) states.put(key, (String) value);
                else hits.put(key, ((Number) value).intValue());
            }
        }
    }
}
