package com.adhook.config.security;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** All-or-nothing bounded validation; accepted packets replace, never grow, a host snapshot. */
public final class RuntimeStatsPolicy {
    public static final int MAX_JSON_LENGTH = 16 * 1024;
    public static final int MAX_FIELDS_PER_SECTION = 128;
    public static final int MAX_HIT_COUNT = 1_000_000_000;

    private RuntimeStatsPolicy() {}

    public static boolean isStatsKey(String value) {
        return ConfigInputPolicy.isFeatureId(value) || (value != null && value.startsWith("adhook:v2:")
                && ConfigInputPolicy.isRuleId(value));
    }

    public static final class Snapshot {
        public final Map<String, String> states;
        public final Map<String, Integer> hits;

        private Snapshot(Map<String, String> states, Map<String, Integer> hits) {
            this.states = Collections.unmodifiableMap(states);
            this.hits = Collections.unmodifiableMap(hits);
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("states", new JSONObject(states)).put("hits", new JSONObject(hits));
        }
    }

    /** Reject coercions, unknown fields, deep objects and trailing content before touching state. */
    public static Snapshot parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_LENGTH) return null;
        try {
            // Only root -> section objects are supported; reject pathological nesting before JSON parsing.
            boolean quoted = false;
            boolean escaped = false;
            int depth = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') quoted = false;
                } else if (c == '"') quoted = true;
                else if (c == '[' || c == '\'' || c == '#') return null;
                else if (c == '{' && ++depth > 2) return null;
                else if (c == '}' && --depth < 0) return null;
            }
            if (quoted || depth != 0) return null;
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) return null;
            JSONObject root = (JSONObject) value;
            if (root.length() == 0 || root.length() > 2) return null;
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"states".equals(key) && !"hits".equals(key)) return null;
                if (!(root.opt(key) instanceof JSONObject)) return null;
            }
            Map<String, String> states = new LinkedHashMap<>();
            Map<String, Integer> hits = new LinkedHashMap<>();
            JSONObject stateObject = root.optJSONObject("states");
            if (stateObject != null) {
                if (stateObject.length() > MAX_FIELDS_PER_SECTION) return null;
                keys = stateObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object state = stateObject.opt(key);
                    if (!isStatsKey(key) || !(state instanceof String)
                            || !("OK".equals(state) || "FAILED".equals(state) || "DISABLED".equals(state))) return null;
                    states.put(key, (String) state);
                }
            }
            JSONObject hitObject = root.optJSONObject("hits");
            if (hitObject != null) {
                if (hitObject.length() > MAX_FIELDS_PER_SECTION) return null;
                keys = hitObject.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object count = hitObject.opt(key);
                    if (!isStatsKey(key) || !(count instanceof Integer || count instanceof Long)) return null;
                    long number = ((Number) count).longValue();
                    if (number < 0 || number > MAX_HIT_COUNT) return null;
                    hits.put(key, (int) number);
                }
            }
            return new Snapshot(states, hits);
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
