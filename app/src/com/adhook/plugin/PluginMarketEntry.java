package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** One plugin listing inside a market index. */
public final class PluginMarketEntry {

    private final String id;
    private final String name;
    private final String summary;
    private final String author;
    private final List<String> targets;
    private final PluginMarketRelease release;

    public PluginMarketEntry(String id, String name, String summary, String author,
                             List<String> targets, PluginMarketRelease release) {
        this.id = id;
        this.name = name != null ? name : "";
        this.summary = summary != null ? summary : "";
        this.author = author != null ? author : "";
        this.targets = Collections.unmodifiableList(
                targets != null ? new ArrayList<>(targets) : new ArrayList<>());
        this.release = release;
    }

    /** Returns null for any invalid entry. */
    public static PluginMarketEntry fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"id".equals(key) && !"name".equals(key) && !"summary".equals(key)
                        && !"author".equals(key) && !"targets".equals(key)
                        && !"release".equals(key)) return null;
            }
            Object rawId = json.opt("id");
            if (!(rawId instanceof String) || !PluginConstants.isValidPluginId((String) rawId)) return null;
            Object rawName = json.opt("name");
            if (!(rawName instanceof String)) return null;
            String name = (String) rawName;
            if (name.isEmpty() || name.length() > PluginConstants.MAX_PLUGIN_NAME_LENGTH
                    || PluginConstants.hasControlCharacters(name)) return null;
            Object rawSummary = json.opt("summary");
            if (rawSummary != null && !(rawSummary instanceof String)) return null;
            String summary = rawSummary instanceof String ? (String) rawSummary : "";
            if (summary.length() > 200 || PluginConstants.hasControlCharacters(summary)) return null;
            Object rawAuthor = json.opt("author");
            if (!(rawAuthor instanceof String)) return null;
            String author = (String) rawAuthor;
            if (author.isEmpty() || author.length() > PluginConstants.MAX_NAME_LENGTH
                    || PluginConstants.hasControlCharacters(author)) return null;
            Object rawTargets = json.opt("targets");
            if (!(rawTargets instanceof JSONArray)) return null;
            JSONArray targetArray = (JSONArray) rawTargets;
            if (targetArray.length() < 1 || targetArray.length() > PluginConstants.MAX_TARGETS) return null;
            List<String> targets = new ArrayList<>();
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < targetArray.length(); i++) {
                Object value = targetArray.opt(i);
                if (!(value instanceof String) || !PluginConstants.isValidPackageName((String) value)) {
                    return null;
                }
                if (PluginConstants.isForbiddenTargetPackage((String) value)) return null;
                if (!unique.add((String) value)) return null;
                targets.add((String) value);
            }
            Object rawRelease = json.opt("release");
            if (!(rawRelease instanceof JSONObject)) return null;
            PluginMarketRelease release = PluginMarketRelease.fromJson((JSONObject) rawRelease);
            if (release == null) return null;
            return new PluginMarketEntry((String) rawId, name, summary, author, targets, release);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("summary", summary);
            json.put("author", author);
            JSONArray targetArray = new JSONArray();
            for (String target : targets) targetArray.put(target);
            json.put("targets", targetArray);
            json.put("release", release != null ? release.toJson() : new JSONObject());
        } catch (org.json.JSONException ignored) {
            // All values are pre-validated.
        }
        return json;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSummary() { return summary; }
    public String getAuthor() { return author; }
    public List<String> getTargets() { return targets; }
    public PluginMarketRelease getRelease() { return release; }
}
