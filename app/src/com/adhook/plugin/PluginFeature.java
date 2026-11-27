package com.adhook.plugin;

import com.adhook.config.model.FeatureMeta;

import org.json.JSONObject;

/**
 * One independently switchable host-plugin feature.
 *
 * <p>Serialized key names follow the plugin wire contract: {@code defaultEnabled}. The parser
 * also accepts the legacy {@code default} spelling so older packaged samples remain readable,
 * but every serialization writes {@code defaultEnabled}.</p>
 */
public final class PluginFeature {

    private final String id;
    private final String name;
    private final String description;
    private final boolean defaultEnabled;
    private final boolean implemented;

    public PluginFeature(String id, String name, String description,
                         boolean defaultEnabled, boolean implemented) {
        this.id = id;
        this.name = name;
        this.description = description != null ? description : "";
        this.defaultEnabled = defaultEnabled;
        this.implemented = implemented;
    }

    /** Returns null for a malformed feature rather than silently repairing it. */
    public static PluginFeature fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"id".equals(key) && !"name".equals(key) && !"description".equals(key)
                        && !"defaultEnabled".equals(key) && !"implemented".equals(key)) return null;
            }
        } catch (RuntimeException malformed) {
            return null;
        }
        Object rawId = json.opt("id");
        Object rawName = json.opt("name");
        if (!(rawId instanceof String) || !(rawName instanceof String)) return null;
        String id = (String) rawId;
        String name = (String) rawName;
        if (!PluginConstants.isValidFeatureId(id)) return null;
        if (!validText(name, PluginConstants.MAX_NAME_LENGTH, false)) return null;
        Object rawDescription = json.opt("description");
        if (rawDescription != null && !(rawDescription instanceof String)) return null;
        String description = rawDescription instanceof String ? (String) rawDescription : "";
        if (!validText(description, PluginConstants.MAX_FEATURE_DESCRIPTION_LENGTH, true)) return null;
        Boolean defaults = optBoolean(json, "defaultEnabled");
        Boolean implemented = optBoolean(json, "implemented");
        return new PluginFeature(id, name, description,
                defaults != null && defaults, implemented == null || implemented);
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("description", description);
            json.put("defaultEnabled", defaultEnabled);
            json.put("implemented", implemented);
        } catch (org.json.JSONException ignored) {
            // JSONObject only rejects null names; all values here are non-null literals.
        }
        return json;
    }

    public FeatureMeta toFeatureMeta() {
        return new FeatureMeta(id, name, description, defaultEnabled, implemented);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isDefaultEnabled() { return defaultEnabled; }
    public boolean isImplemented() { return implemented; }

    /** Detached copy used by store snapshots; immutable fields make a plain new instance enough. */
    public PluginFeature copy() {
        return new PluginFeature(id, name, description, defaultEnabled, implemented);
    }

    private static Boolean optBoolean(JSONObject json, String key) {
        Object value = json.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static boolean validText(String value, int maxLength, boolean allowEmpty) {
        if (value == null || value.length() > maxLength) return false;
        if (!allowEmpty && value.isEmpty()) return false;
        return !PluginConstants.hasControlCharacters(value);
    }
}
