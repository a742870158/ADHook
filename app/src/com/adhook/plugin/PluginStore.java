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
 * Installed plugin catalogue ({@code files/plugins/installed.json}).
 *
 * <p>The whole catalogue is one small JSON file, replaced atomically while the previous content
 * is retained as {@code installed.json.bak}. A malformed main file is never silently emptied: it
 * is copied to {@code .corrupt-<ts>}, the store becomes unhealthy and all further writes are
 * refused until the user restores or explicitly clears the data. A missing main file while a
 * backup exists is also treated as needing manual recovery, not as "no plugins installed".</p>
 */
public class PluginStore {

    private static final String FILE_NAME = "installed.json";
    private static final String BACKUP_SUFFIX = ".bak";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_STORE_BYTES = 8 * 1024 * 1024;

    private static volatile PluginStore instance;

    private final File file;
    private final File backup;
    private final List<InstalledPlugin> plugins = new ArrayList<>();
    private boolean healthy = true;
    private String loadWarning;

    public PluginStore(Context context) {
        this(new File(context.getFilesDir(), "plugins"));
    }

    public PluginStore(File pluginsDirectory) {
        File directory = pluginsDirectory != null ? pluginsDirectory : new File(".");
        this.file = new File(directory, FILE_NAME);
        this.backup = new File(directory, FILE_NAME + BACKUP_SUFFIX);
        load();
    }

    public static PluginStore get(Context context) {
        File pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginStore current = instance;
        if (current != null && current.file.getParentFile() != null
                && current.file.getParentFile().equals(pluginsDirectory)) {
            return current;
        }
        synchronized (PluginStore.class) {
            current = instance;
            if (current == null || current.file.getParentFile() == null
                    || !current.file.getParentFile().equals(pluginsDirectory)) {
                current = new PluginStore(pluginsDirectory);
                instance = current;
            }
            return current;
        }
    }

    /** Detached snapshot; callers may not mutate store state through the returned list. */
    public synchronized List<InstalledPlugin> list() {
        return new ArrayList<>(plugins);
    }

    public synchronized InstalledPlugin find(String pluginId) {
        int index = indexOf(pluginId);
        return index >= 0 ? plugins.get(index) : null;
    }

    public synchronized boolean upsert(InstalledPlugin plugin) {
        if (!healthy || plugin == null) return false;
        int index = indexOf(plugin.getId());
        if (index < 0 && plugins.size() >= PluginConstants.MAX_PLUGINS) return false;
        List<InstalledPlugin> snapshot = new ArrayList<>(plugins);
        if (index >= 0) plugins.set(index, plugin);
        else plugins.add(plugin);
        if (save()) return true;
        plugins.clear();
        plugins.addAll(snapshot);
        return false;
    }

    public synchronized boolean remove(String pluginId) {
        if (!healthy || pluginId == null) return false;
        int index = indexOf(pluginId);
        if (index < 0) return false;
        List<InstalledPlugin> snapshot = new ArrayList<>(plugins);
        plugins.remove(index);
        if (save()) return true;
        plugins.clear();
        plugins.addAll(snapshot);
        return false;
    }

    public synchronized boolean setEnabled(String pluginId, boolean enabled) {
        if (!healthy || pluginId == null) return false;
        int index = indexOf(pluginId);
        if (index < 0) return false;
        InstalledPlugin current = plugins.get(index);
        if (current.isEnabled() == enabled) return true;
        List<InstalledPlugin> snapshot = new ArrayList<>(plugins);
        plugins.set(index, current.copyWithEnabled(enabled));
        if (save()) return true;
        plugins.clear();
        plugins.addAll(snapshot);
        return false;
    }

    public synchronized boolean isHealthy() { return healthy; }

    public synchronized int count() { return plugins.size(); }

    public synchronized String getLoadWarning() { return loadWarning; }

    /** Test-only hook so suites do not share a cached store across temporary file directories. */
    static void resetForTests() {
        instance = null;
    }

    private void load() {
        if (!file.isFile()) {
            if (backup.isFile()) {
                healthy = false;
                loadWarning = "插件安装记录缺失但存在备份，已暂停写入；请检查并恢复 installed.json.bak";
            }
            return;
        }
        try {
            String text = PluginFiles.readText(file, MAX_STORE_BYTES);
            JSONObject root = new JSONObject(text);
            if (!(root.opt("schema") instanceof Number)
                    || ((Number) root.opt("schema")).intValue() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("schema");
            }
            Object rawPlugins = root.opt("plugins");
            if (!(rawPlugins instanceof JSONArray)) throw new IllegalArgumentException("plugins");
            JSONArray array = (JSONArray) rawPlugins;
            if (array.length() > PluginConstants.MAX_PLUGINS) throw new IllegalArgumentException("limit");
            Set<String> ids = new LinkedHashSet<>();
            List<InstalledPlugin> parsed = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                Object raw = array.opt(i);
                if (!(raw instanceof JSONObject)) throw new IllegalArgumentException("entry");
                InstalledPlugin plugin = InstalledPlugin.fromJson((JSONObject) raw);
                if (plugin == null) throw new IllegalArgumentException("entry");
                if (!ids.add(plugin.getId())) throw new IllegalArgumentException("duplicate");
                parsed.add(plugin);
            }
            plugins.addAll(parsed);
        } catch (Exception malformed) {
            healthy = false;
            plugins.clear();
            boolean preserved = preserveCorruptCopy();
            loadWarning = preserved
                    ? "插件安装记录损坏，已保留损坏副本并暂停写入；请恢复备份或由用户显式清理"
                    : "插件安装记录损坏，损坏副本保留失败；已暂停写入";
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
            for (InstalledPlugin plugin : plugins) array.put(plugin.toJson());
            root.put("plugins", array);
            text = root.toString();
        } catch (org.json.JSONException failure) {
            return false;
        }
        if (file.isFile()) {
            try {
                byte[] previous = PluginFiles.readAll(file, MAX_STORE_BYTES);
                if (!PluginFiles.writeAtomically(backup, previous)) return false;
            } catch (Exception failure) {
                return false;
            }
        }
        if (!PluginFiles.writeAtomically(file, text.getBytes(StandardCharsets.UTF_8))) return false;
        try {
            return text.equals(PluginFiles.readText(file, MAX_STORE_BYTES));
        } catch (Exception failure) {
            return false;
        }
    }

    private int indexOf(String pluginId) {
        if (pluginId == null) return -1;
        for (int i = 0; i < plugins.size(); i++) {
            if (pluginId.equals(plugins.get(i).getId())) return i;
        }
        return -1;
    }
}
