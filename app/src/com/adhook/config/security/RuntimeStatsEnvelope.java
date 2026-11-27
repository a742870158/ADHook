package com.adhook.config.security;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable, bounded storage for the latest full snapshot of each host process.
 * Session IDs replace only their process slot; reports are never lifetime deltas.
 * The aggregate is derived and bounded independently, never trusted from disk.
 */
public final class RuntimeStatsEnvelope {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_PROCESSES = 8;
    public static final int MAX_JSON_LENGTH = MAX_PROCESSES
            * (RuntimeStatsPolicy.MAX_JSON_LENGTH + RuntimeStatsMetadata.MAX_PROCESS_NAME_LENGTH + 256) + 256;

    public final long updatedAt;
    public final Map<String, ProcessSnapshot> processes;
    public final RuntimeStatsPolicy.Snapshot aggregate;
    public final boolean truncated;
    private final String packageName;
    private final RuntimeStatsPolicy.Snapshot legacySnapshot;
    private final boolean legacyTruncated;

    public static final class ProcessSnapshot {
        public final String sessionId;
        public final RuntimeStatsPolicy.Snapshot snapshot;
        public final boolean truncated;

        private ProcessSnapshot(String sessionId, RuntimeStatsPolicy.Snapshot snapshot, boolean truncated) {
            this.sessionId = sessionId;
            this.snapshot = snapshot;
            this.truncated = truncated;
        }
    }

    private RuntimeStatsEnvelope(String packageName, long updatedAt,
                                 Map<String, ProcessSnapshot> processes,
                                 RuntimeStatsPolicy.Snapshot legacySnapshot, boolean legacyTruncated, Aggregate result) {
        this.packageName = packageName;
        this.updatedAt = updatedAt;
        this.processes = Collections.unmodifiableMap(new LinkedHashMap<>(processes));
        this.legacySnapshot = legacySnapshot;
        this.legacyTruncated = legacyTruncated;
        this.aggregate = result.snapshot;
        this.truncated = result.truncated;
    }

    /** Legacy reports still replace the entire app, including any previously named process slots. */
    public static RuntimeStatsEnvelope legacy(String packageName, RuntimeStatsPolicy.Snapshot snapshot, long updatedAt) {
        return legacy(packageName, snapshot, updatedAt, false);
    }

    public static RuntimeStatsEnvelope legacy(String packageName, RuntimeStatsPolicy.Snapshot snapshot,
                                             long updatedAt, boolean truncated) {
        if (!ConfigInputPolicy.isPackageName(packageName) || updatedAt < 0) return null;
        RuntimeStatsPolicy.Snapshot validated = validateSnapshot(snapshot);
        return validated == null ? null : new RuntimeStatsEnvelope(packageName, updatedAt,
                Collections.emptyMap(), validated, truncated, new Aggregate(validated, truncated));
    }

    /**
     * Copy-on-write replacement prevents rotating feature/session IDs from accumulating.
     * Reject a ninth process without evicting another process (in particular the main process).
     * A first named report replaces a legacy baseline rather than counting the same host twice.
     */
    public static RuntimeStatsEnvelope replace(String packageName, RuntimeStatsEnvelope previous,
                                              RuntimeStatsPolicy.Snapshot snapshot, String processName,
                                              String sessionId, long updatedAt) {
        return replace(packageName, previous, snapshot, processName, sessionId, updatedAt, false);
    }

    public static RuntimeStatsEnvelope replace(String packageName, RuntimeStatsEnvelope previous,
                                              RuntimeStatsPolicy.Snapshot snapshot, String processName,
                                              String sessionId, long updatedAt, boolean truncated) {
        if (!RuntimeStatsMetadata.isValid(packageName, processName, sessionId) || updatedAt < 0
                || (previous != null && !packageName.equals(previous.packageName))) return null;
        RuntimeStatsPolicy.Snapshot validated = validateSnapshot(snapshot);
        if (validated == null) return null;
        Map<String, ProcessSnapshot> next = new LinkedHashMap<>();
        if (previous != null) next.putAll(previous.processes);
        if (!next.containsKey(processName) && next.size() >= MAX_PROCESSES) return null;
        next.put(processName, new ProcessSnapshot(sessionId, validated, truncated));
        Aggregate result = aggregate(next);
        return result == null ? null : new RuntimeStatsEnvelope(packageName, updatedAt, next, null, false, result);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject processObject = new JSONObject();
        for (Map.Entry<String, ProcessSnapshot> entry : processes.entrySet()) {
            ProcessSnapshot process = entry.getValue();
            processObject.put(entry.getKey(), new JSONObject().put("sessionId", process.sessionId)
                    .put("snapshot", process.snapshot.toJson()).put("truncated", process.truncated));
        }
        JSONObject root = new JSONObject().put("schemaVersion", SCHEMA_VERSION)
                .put("updatedAt", updatedAt).put("processes", processObject);
        if (legacySnapshot != null) root.put("legacySnapshot", legacySnapshot.toJson())
                .put("legacyTruncated", legacyTruncated);
        if (root.toString().length() > MAX_JSON_LENGTH) throw new JSONException("Runtime stats envelope too large");
        return root;
    }

    /** Accept the old strict plain packet or an all-or-nothing version-2 envelope. */
    public static RuntimeStatsEnvelope parse(String packageName, String json) {
        if (!ConfigInputPolicy.isPackageName(packageName) || json == null || json.isEmpty()
                || json.length() > MAX_JSON_LENGTH) return null;
        RuntimeStatsPolicy.Snapshot oldPacket = RuntimeStatsPolicy.parse(json);
        if (oldPacket != null) return legacy(packageName, oldPacket, 0L); // Old files had no timestamp.
        if (!hasBoundedStructure(json)) return null;
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) return null;
            JSONObject root = (JSONObject) value;
            boolean isLegacy = root.has("legacySnapshot");
            boolean hasLegacyTruncated = root.has("legacyTruncated");
            int expectedFields = isLegacy ? (hasLegacyTruncated ? 5 : 4) : 3;
            if (root.length() != expectedFields || !isInteger(root.opt("schemaVersion"))
                    || ((Number) root.opt("schemaVersion")).longValue() != SCHEMA_VERSION
                    || !isInteger(root.opt("updatedAt")) || ((Number) root.opt("updatedAt")).longValue() < 0
                    || !(root.opt("processes") instanceof JSONObject)
                    || (hasLegacyTruncated && !(root.opt("legacyTruncated") instanceof Boolean))) return null;
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"schemaVersion".equals(key) && !"updatedAt".equals(key) && !"processes".equals(key)
                        && !"legacySnapshot".equals(key) && !"legacyTruncated".equals(key)) return null;
            }
            long updatedAt = ((Number) root.opt("updatedAt")).longValue();
            JSONObject processObject = root.getJSONObject("processes");
            if (isLegacy) {
                if (processObject.length() != 0 || !(root.opt("legacySnapshot") instanceof JSONObject)) return null;
                return legacy(packageName, RuntimeStatsPolicy.parse(root.getJSONObject("legacySnapshot").toString()),
                        updatedAt, hasLegacyTruncated && (Boolean) root.opt("legacyTruncated"));
            }
            if (hasLegacyTruncated || processObject.length() == 0 || processObject.length() > MAX_PROCESSES) return null;
            Map<String, ProcessSnapshot> processes = new LinkedHashMap<>();
            keys = processObject.keys();
            while (keys.hasNext()) {
                String processName = keys.next();
                Object processValue = processObject.opt(processName);
                if (!(processValue instanceof JSONObject)) return null;
                JSONObject process = (JSONObject) processValue;
                Object sessionId = process.opt("sessionId");
                boolean hasTruncated = process.has("truncated");
                if (process.length() != (hasTruncated ? 3 : 2) || !(sessionId instanceof String)
                        || !RuntimeStatsMetadata.isValid(packageName, processName, (String) sessionId)
                        || !(process.opt("snapshot") instanceof JSONObject)
                        || (hasTruncated && !(process.opt("truncated") instanceof Boolean))) return null;
                RuntimeStatsPolicy.Snapshot snapshot = RuntimeStatsPolicy.parse(process.getJSONObject("snapshot").toString());
                if (snapshot == null) return null;
                processes.put(processName, new ProcessSnapshot((String) sessionId, snapshot,
                        hasTruncated && (Boolean) process.opt("truncated")));
            }
            Aggregate result = aggregate(processes);
            return result == null ? null : new RuntimeStatsEnvelope(packageName, updatedAt, processes, null, false, result);
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static RuntimeStatsPolicy.Snapshot validateSnapshot(RuntimeStatsPolicy.Snapshot snapshot) {
        if (snapshot == null) return null;
        try {
            return RuntimeStatsPolicy.parse(snapshot.toJson().toString());
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Aggregate aggregate(Map<String, ProcessSnapshot> processes) {
        Map<String, String> states = new TreeMap<>();
        Map<String, Integer> hits = new TreeMap<>();
        boolean truncated = false;
        for (ProcessSnapshot process : processes.values()) {
            truncated |= process.truncated;
            for (Map.Entry<String, String> entry : process.snapshot.states.entrySet()) {
                String previous = states.get(entry.getKey());
                if (previous == null || stateRank(entry.getValue()) > stateRank(previous)) {
                    states.put(entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, Integer> entry : process.snapshot.hits.entrySet()) {
                long sum = (long) hits.getOrDefault(entry.getKey(), 0) + entry.getValue();
                hits.put(entry.getKey(), (int) Math.min(sum, RuntimeStatsPolicy.MAX_HIT_COUNT));
            }
        }
        // Native feature telemetry is retained before managed-rule IDs; counts come first so
        // expensive state keys cannot consume the whole budget and hide all hit quantities.
        Capture capture = new Capture();
        capture.addHits(hits, false);
        capture.addStates(states, false);
        capture.addHits(hits, true);
        capture.addStates(states, true);
        try {
            RuntimeStatsPolicy.Snapshot snapshot = RuntimeStatsPolicy.parse(new JSONObject()
                    .put("states", new JSONObject(capture.states)).put("hits", new JSONObject(capture.hits)).toString());
            return snapshot == null ? null : new Aggregate(snapshot, truncated || capture.truncated);
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int stateRank(String state) {
        return "FAILED".equals(state) ? 3 : "OK".equals(state) ? 2 : 1;
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    /** Reject excessive nesting before JSONObject/JSONTokener can recurse into untrusted disk data. */
    private static boolean hasBoundedStructure(String json) {
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
            else if (c == '[' || c == '\'' || c == '#') return false;
            else if (c == '{' && ++depth > 5) return false;
            else if (c == '}' && --depth < 0) return false;
        }
        return !quoted && depth == 0;
    }

    private static final class Aggregate {
        final RuntimeStatsPolicy.Snapshot snapshot;
        final boolean truncated;
        Aggregate(RuntimeStatsPolicy.Snapshot snapshot, boolean truncated) {
            this.snapshot = snapshot;
            this.truncated = truncated;
        }
    }

    private static final class Capture {
        final Map<String, String> states = new LinkedHashMap<>();
        final Map<String, Integer> hits = new LinkedHashMap<>();
        // All validated keys/values are ASCII. Reserve more than the exact JSON punctuation cost.
        int size = 64;
        boolean truncated;

        boolean fits(String key, String value, int fields) {
            int cost = key.length() + value.length() + 10;
            if (fields >= RuntimeStatsPolicy.MAX_FIELDS_PER_SECTION || size + cost > RuntimeStatsPolicy.MAX_JSON_LENGTH) {
                truncated = true;
                return false;
            }
            size += cost;
            return true;
        }

        void addHits(Map<String, Integer> source, boolean managedPass) {
            for (Map.Entry<String, Integer> entry : source.entrySet()) {
                if (entry.getKey().startsWith("adhook:v2:") != managedPass) continue;
                if (fits(entry.getKey(), entry.getValue().toString(), hits.size())) hits.put(entry.getKey(), entry.getValue());
            }
        }

        void addStates(Map<String, String> source, boolean managedPass) {
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (entry.getKey().startsWith("adhook:v2:") != managedPass) continue;
                if (fits(entry.getKey(), entry.getValue(), states.size())) states.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
