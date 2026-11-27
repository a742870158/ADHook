package com.adhook.plugin;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Last successfully downloaded index per source ({@code files/plugins/market/<sourceId>.json}).
 *
 * <p>Atomic write, bounded read. The cache is derived data: an unreadable or corrupt cache entry
 * is reported as absent (null) and left on disk for diagnosis, never silently returned as a
 * usable index.</p>
 */
public final class PluginMarketCache {

    private final File directory;

    public PluginMarketCache(Context context) {
        this(new File(context.getFilesDir(), "plugins"));
    }

    public PluginMarketCache(File pluginsDirectory) {
        File root = pluginsDirectory != null ? pluginsDirectory : new File(".");
        this.directory = new File(root, "market");
    }

    public File file(long sourceId) {
        if (sourceId <= 0L) return null;
        return new File(directory, sourceId + ".json");
    }

    /** Returns the raw cached index text, or null when absent/invalid/oversized. */
    public String read(long sourceId) {
        File file = file(sourceId);
        if (file == null || !file.isFile()) return null;
        try {
            if (file.length() > PluginConstants.MAX_INDEX_BYTES) return null;
            String text = PluginFiles.readText(file, PluginConstants.MAX_INDEX_BYTES);
            new JSONObject(text);
            return text;
        } catch (Exception malformed) {
            return null;
        }
    }

    public boolean write(long sourceId, String json) {
        File file = file(sourceId);
        if (file == null || json == null || json.isEmpty()) return false;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PluginConstants.MAX_INDEX_BYTES) return false;
        if (!PluginFiles.ensureDirectory(directory)) return false;
        if (!PluginFiles.writeAtomically(file, bytes)) return false;
        try {
            return json.equals(PluginFiles.readText(file, PluginConstants.MAX_INDEX_BYTES));
        } catch (Exception failure) {
            return false;
        }
    }

    public boolean clear(long sourceId) {
        File file = file(sourceId);
        if (file == null) return false;
        try {
            return java.nio.file.Files.deleteIfExists(file.toPath()) || !file.exists();
        } catch (Exception failure) {
            return false;
        }
    }
}
