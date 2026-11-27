package com.adhook.plugin;

import org.json.JSONObject;

/**
 * One user-configured plugin market source. Identity is the numeric id; a URL is not an alias
 * for another source.
 */
public final class PluginMarketSource {

    private final long id;
    private final String name;
    private final String url;
    private final boolean enabled;
    private final long lastUpdated;
    private final String lastError;

    public PluginMarketSource(long id, String name, String url, boolean enabled,
                              long lastUpdated, String lastError) {
        this.id = id;
        this.name = name != null ? name : "";
        this.url = url != null ? url : "";
        this.enabled = enabled;
        this.lastUpdated = Math.max(0L, lastUpdated);
        this.lastError = lastError != null ? lastError : "";
    }

    /** Returns null for invalid persisted source metadata. */
    public static PluginMarketSource fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            Object rawId = json.opt("id");
            if (!(rawId instanceof Number) || ((Number) rawId).longValue() <= 0L) return null;
            long id = ((Number) rawId).longValue();
            Object rawName = json.opt("name");
            Object rawUrl = json.opt("url");
            if (!(rawName instanceof String) || !(rawUrl instanceof String)) return null;
            String name = (String) rawName;
            String url = (String) rawUrl;
            if (name.isEmpty() || name.length() > PluginConstants.MAX_NAME_LENGTH
                    || PluginConstants.hasControlCharacters(name)) return null;
            if (!PluginConstants.isValidHttpUrl(url)) return null;
            Object rawEnabled = json.opt("enabled");
            if (!(rawEnabled instanceof Boolean)) return null;
            Object rawUpdated = json.opt("lastUpdated");
            long lastUpdated = rawUpdated instanceof Number ? ((Number) rawUpdated).longValue() : 0L;
            if (lastUpdated < 0L) return null;
            Object rawError = json.opt("lastError");
            if (rawError != null && !(rawError instanceof String)) return null;
            String lastError = rawError instanceof String ? (String) rawError : "";
            if (lastError.length() > PluginConstants.MAX_URL_LENGTH
                    || PluginConstants.hasControlCharacters(lastError)) return null;
            return new PluginMarketSource(id, name, url, (Boolean) rawEnabled, lastUpdated, lastError);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("url", url);
            json.put("enabled", enabled);
            json.put("lastUpdated", lastUpdated);
            json.put("lastError", lastError);
        } catch (org.json.JSONException ignored) {
            // All values are pre-validated; org.json rejects only null names/values.
        }
        return json;
    }

    public PluginMarketSource withEnabled(boolean newEnabled) {
        return new PluginMarketSource(id, name, url, newEnabled, lastUpdated, lastError);
    }

    public PluginMarketSource withMeta(long newLastUpdated, String newLastError) {
        return new PluginMarketSource(id, name, url, enabled, newLastUpdated, newLastError);
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public boolean isEnabled() { return enabled; }
    public long getLastUpdated() { return lastUpdated; }
    public String getLastError() { return lastError; }
}
