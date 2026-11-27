package com.adhook.plugin;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * User-managed market source list ({@code files/plugins/sources.json}).
 *
 * <p>Atomic writes; a malformed file is preserved as {@code .corrupt-<ts>} and freezes writes
 * instead of silently dropping the user's sources. URL validation is shared with the market
 * client: http/https only, no userinfo/fragment, at most 2048 characters.</p>
 */
public final class PluginSourceStore {

    private static final String FILE_NAME = "sources.json";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_STORE_BYTES = 2 * 1024 * 1024;
    private static volatile PluginSourceStore instance;

    private final File file;
    private final List<PluginMarketSource> sources = new ArrayList<>();
    private boolean healthy = true;
    private String loadWarning;

    public PluginSourceStore(Context context) {
        this(new File(context.getFilesDir(), "plugins"));
    }

    public PluginSourceStore(File pluginsDirectory) {
        File directory = pluginsDirectory != null ? pluginsDirectory : new File(".");
        this.file = new File(directory, FILE_NAME);
        load();
    }

    public static PluginSourceStore get(Context context) {
        File pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginSourceStore current = instance;
        if (current != null && current.file.getParentFile() != null
                && current.file.getParentFile().equals(pluginsDirectory)) {
            return current;
        }
        synchronized (PluginSourceStore.class) {
            current = instance;
            if (current == null || current.file.getParentFile() == null
                    || !current.file.getParentFile().equals(pluginsDirectory)) {
                current = new PluginSourceStore(pluginsDirectory);
                instance = current;
            }
            return current;
        }
    }

    public synchronized List<PluginMarketSource> list() {
        return new ArrayList<>(sources);
    }

    public synchronized PluginMarketSource get(long sourceId) {
        int index = indexOf(sourceId);
        return index >= 0 ? sources.get(index) : null;
    }

    public synchronized boolean upsert(PluginMarketSource source) {
        if (!healthy || source == null || source.getId() <= 0L) return false;
        if (source.getName().isEmpty() || source.getName().length() > PluginConstants.MAX_NAME_LENGTH
                || PluginConstants.hasControlCharacters(source.getName())) return false;
        if (!isValidUrl(source.getUrl())) return false;
        if (source.getLastError().length() > PluginConstants.MAX_URL_LENGTH) return false;
        int index = indexOf(source.getId());
        if (index < 0 && sources.size() >= PluginConstants.MAX_SOURCES) return false;
        if (index >= 0) {
            if (!sources.get(index).getUrl().equals(source.getUrl())) {
                // A changed URL is a different source; rewriting it silently is not allowed.
                return false;
            }
        }
        List<PluginMarketSource> snapshot = new ArrayList<>(sources);
        if (index >= 0) sources.set(index, source);
        else sources.add(source);
        if (save()) return true;
        sources.clear();
        sources.addAll(snapshot);
        return false;
    }

    public synchronized boolean remove(long sourceId) {
        if (!healthy) return false;
        int index = indexOf(sourceId);
        if (index < 0) return false;
        List<PluginMarketSource> snapshot = new ArrayList<>(sources);
        sources.remove(index);
        if (save()) return true;
        sources.clear();
        sources.addAll(snapshot);
        return false;
    }

    public synchronized boolean setEnabled(long sourceId, boolean enabled) {
        if (!healthy) return false;
        int index = indexOf(sourceId);
        if (index < 0) return false;
        if (sources.get(index).isEnabled() == enabled) return true;
        List<PluginMarketSource> snapshot = new ArrayList<>(sources);
        sources.set(index, sources.get(index).withEnabled(enabled));
        if (save()) return true;
        sources.clear();
        sources.addAll(snapshot);
        return false;
    }

    public synchronized boolean updateMeta(long sourceId, long lastUpdated, String lastError) {
        if (!healthy) return false;
        int index = indexOf(sourceId);
        if (index < 0) return false;
        String safeError = lastError != null ? lastError : "";
        if (safeError.length() > PluginConstants.MAX_URL_LENGTH) {
            safeError = safeError.substring(0, PluginConstants.MAX_URL_LENGTH);
        }
        List<PluginMarketSource> snapshot = new ArrayList<>(sources);
        sources.set(index, sources.get(index).withMeta(Math.max(0L, lastUpdated), safeError));
        if (save()) return true;
        sources.clear();
        sources.addAll(snapshot);
        return false;
    }

    public synchronized boolean isHealthy() { return healthy; }

    public synchronized int count() { return sources.size(); }

    public synchronized String getLoadWarning() { return loadWarning; }

    /** Shared URL policy for sources, redirects and release URLs. */
    public static boolean isValidUrl(String value) {
        return PluginConstants.isValidHttpUrl(value);
    }

    static void resetForTests() {
        instance = null;
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            String text = PluginFiles.readText(file, MAX_STORE_BYTES);
            JSONObject root = new JSONObject(text);
            if (!(root.opt("schema") instanceof Number)
                    || ((Number) root.opt("schema")).intValue() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("schema");
            }
            Object rawSources = root.opt("sources");
            if (!(rawSources instanceof JSONArray)) throw new IllegalArgumentException("sources");
            JSONArray array = (JSONArray) rawSources;
            if (array.length() > PluginConstants.MAX_SOURCES) throw new IllegalArgumentException("limit");
            Set<Long> ids = new LinkedHashSet<>();
            List<PluginMarketSource> parsed = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                Object raw = array.opt(i);
                if (!(raw instanceof JSONObject)) throw new IllegalArgumentException("entry");
                PluginMarketSource source = PluginMarketSource.fromJson((JSONObject) raw);
                if (source == null) throw new IllegalArgumentException("entry");
                if (!ids.add(source.getId())) throw new IllegalArgumentException("duplicate");
                parsed.add(source);
            }
            sources.addAll(parsed);
        } catch (Exception malformed) {
            healthy = false;
            sources.clear();
            boolean preserved = preserveCorruptCopy();
            loadWarning = preserved
                    ? "插件来源记录损坏，已保留损坏副本并暂停写入"
                    : "插件来源记录损坏，损坏副本保留失败；已暂停写入";
        }
    }

    private boolean preserveCorruptCopy() {
        try {
            File copy = PluginFiles.uniqueSibling(file, ".corrupt");
            java.nio.file.Files.copy(file.toPath(), copy.toPath());
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private boolean save() {
        if (!healthy) return false;
        String text;
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA_VERSION);
            JSONArray array = new JSONArray();
            for (PluginMarketSource source : sources) array.put(source.toJson());
            root.put("sources", array);
            text = root.toString();
        } catch (org.json.JSONException failure) {
            return false;
        }
        if (!PluginFiles.writeAtomically(file, text.getBytes(StandardCharsets.UTF_8))) return false;
        try {
            return text.equals(PluginFiles.readText(file, MAX_STORE_BYTES));
        } catch (Exception failure) {
            return false;
        }
    }

    private int indexOf(long sourceId) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getId() == sourceId) return i;
        }
        return -1;
    }
}
