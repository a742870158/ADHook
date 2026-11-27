package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed market index. Every field is validated before the index is exposed: a single malformed
 * entry rejects the whole document so the UI never mixes trusted and untrusted listings.
 */
public final class PluginMarketIndex {

    private final String name;
    private final long updatedAt;
    private final Map<String, PluginMarketEntry> entries;

    private PluginMarketIndex(String name, long updatedAt, Map<String, PluginMarketEntry> entries) {
        this.name = name;
        this.updatedAt = updatedAt;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Returns null when the document is missing, oversized or contains any invalid entry. */
    public static PluginMarketIndex parse(String json) {
        if (json == null || json.isEmpty()) return null;
        if (json.getBytes(StandardCharsets.UTF_8).length > PluginConstants.MAX_INDEX_BYTES) return null;
        if (PluginJson.hasDuplicateTopLevelKeys(json)) return null;
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception malformed) {
            return null;
        }
        try {
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"schema".equals(key) && !"name".equals(key) && !"updatedAt".equals(key)
                        && !"entries".equals(key)) return null;
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        if (!(root.opt("schema") instanceof Number)
                || ((Number) root.opt("schema")).intValue() != PluginConstants.SCHEMA_VERSION) return null;
        Object rawName = root.opt("name");
        if (!(rawName instanceof String)) return null;
        String name = (String) rawName;
        if (name.isEmpty() || name.length() > PluginConstants.MAX_PLUGIN_NAME_LENGTH
                || PluginConstants.hasControlCharacters(name)) return null;
        Object rawUpdatedAt = root.opt("updatedAt");
        long updatedAt = 0L;
        if (rawUpdatedAt != null) {
            if (!(rawUpdatedAt instanceof Number)) return null;
            updatedAt = ((Number) rawUpdatedAt).longValue();
            if (updatedAt < 0L) return null;
        }
        Object rawEntries = root.opt("entries");
        if (!(rawEntries instanceof JSONArray)) return null;
        JSONArray array = (JSONArray) rawEntries;
        if (array.length() > PluginConstants.MAX_PLUGINS) return null;
        Map<String, PluginMarketEntry> parsed = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Object rawEntry = array.opt(i);
            if (!(rawEntry instanceof JSONObject)) return null;
            PluginMarketEntry entry = PluginMarketEntry.fromJson((JSONObject) rawEntry);
            if (entry == null) return null;
            if (!ids.add(entry.getId())) return null;
            parsed.put(entry.getId(), entry);
        }
        return new PluginMarketIndex(name, updatedAt, parsed);
    }

    public PluginMarketEntry find(String pluginId) {
        return pluginId != null ? entries.get(pluginId) : null;
    }

    public PluginMarketRelease releaseFor(String pluginId) {
        PluginMarketEntry entry = find(pluginId);
        return entry != null ? entry.getRelease() : null;
    }

    /** Resolves a release zip URL (absolute or index-relative) to an absolute http(s) URL. */
    public static String resolveZipUrl(String indexUrl, PluginMarketRelease release) {
        if (release == null) return "";
        return release.resolvedZipUrl(indexUrl);
    }

    public List<PluginMarketEntry> entryList() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public String name() { return name; }

    public long updatedAt() { return updatedAt; }

    public int size() { return entries.size(); }
}
