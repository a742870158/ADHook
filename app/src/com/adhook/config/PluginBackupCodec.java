package com.adhook.config;

import android.content.Context;

import com.adhook.plugin.InstalledPlugin;
import com.adhook.plugin.PluginConstants;
import com.adhook.plugin.PluginMarketSource;
import com.adhook.plugin.PluginSourceStore;
import com.adhook.plugin.PluginStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Plugin metadata portion of the configuration backup.
 *
 * <p>Only market sources and the installed plugin identities travel with a backup. Installed
 * plugins are always enabled now; the legacy {@code enabled} field is kept in the envelope for
 * older readers but is ignored on restore. Plugin binaries, publisher trust pins (TOFU) and host
 * authorization are deliberately excluded: restoring a backup must never install code, trust a
 * publisher or authorize a host.
 * A plugin listed in the backup but absent on this device is reported as "needs reinstall" and is
 * never downloaded automatically.</p>
 *
 * <p>{@link #validate(JSONObject)} is pure and runs before any manager or file access, matching
 * the configuration-backup preflight contract. {@link #restore(Context, JSONObject)} is IO-only
 * and rolls its own partial writes back before throwing.</p>
 */
public final class PluginBackupCodec {

    public static final String KEY = "plugins";
    private static final int SCHEMA_VERSION = 1;

    private PluginBackupCodec() {}

    public static final class Outcome {
        public int restoredSources;
        public int restoredInstalled;
        public int pendingReinstall;
        public final List<String> warnings = new ArrayList<>();
    }

    /** Snapshot for a backup envelope; unhealthy stores are marked instead of silently emptied. */
    public static JSONObject export(Context context) {
        JSONObject block = new JSONObject();
        JSONArray sources = new JSONArray();
        JSONArray installed = new JSONArray();
        boolean unhealthy = false;
        PluginSourceStore sourceStore = PluginSourceStore.get(context);
        PluginStore store = PluginStore.get(context);
        unhealthy |= !sourceStore.isHealthy();
        unhealthy |= !store.isHealthy();
        try {
            if (sourceStore.isHealthy()) {
                for (PluginMarketSource source : sourceStore.list()) {
                    sources.put(new JSONObject()
                            .put("id", source.getId())
                            .put("name", source.getName())
                            .put("url", source.getUrl())
                            .put("enabled", source.isEnabled()));
                }
            }
            if (store.isHealthy()) {
                for (InstalledPlugin plugin : store.list()) {
                    installed.put(new JSONObject()
                            .put("id", plugin.getId())
                            .put("name", plugin.getName())
                            .put("versionCode", plugin.getVersionCode())
                            // Compatibility field; installed plugins are always enabled.
                            .put("enabled", true));
                }
            }
            block.put("schema", SCHEMA_VERSION);
            block.put("sources", sources);
            block.put("installed", installed);
            if (unhealthy) block.put("unhealthy", true);
        } catch (org.json.JSONException impossible) {
            // Values above are already validated primitives; never claim a complete block.
            block = new JSONObject();
            try {
                block.put("schema", SCHEMA_VERSION);
                block.put("sources", new JSONArray());
                block.put("installed", new JSONArray());
                block.put("unhealthy", true);
            } catch (org.json.JSONException ignored) {
            }
        }
        return block;
    }

    /** Pure preflight. Unknown keys, wrong types, duplicates and out-of-budget items are invalid. */
    public static boolean validate(JSONObject block) {
        if (block == null) return false;
        Iterator<String> keys = block.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"schema".equals(key) && !"sources".equals(key) && !"installed".equals(key)
                    && !"unhealthy".equals(key)) return false;
        }
        Object schema = block.opt("schema");
        if (!(schema instanceof Number) || ((Number) schema).intValue() != SCHEMA_VERSION) return false;
        Object unhealthy = block.opt("unhealthy");
        if (unhealthy != null && !(unhealthy instanceof Boolean)) return false;
        Object rawSources = block.opt("sources");
        if (!(rawSources instanceof JSONArray)) return false;
        JSONArray sources = (JSONArray) rawSources;
        if (sources.length() > PluginConstants.MAX_SOURCES) return false;
        Set<Long> sourceIds = new LinkedHashSet<>();
        for (int i = 0; i < sources.length(); i++) {
            Object raw = sources.opt(i);
            if (!(raw instanceof JSONObject)) return false;
            JSONObject source = (JSONObject) raw;
            Object id = source.opt("id");
            Object name = source.opt("name");
            Object url = source.opt("url");
            Object enabled = source.opt("enabled");
            if (!(id instanceof Number)) return false;
            long numericId = ((Number) id).longValue();
            if (numericId <= 0L) return false;
            if (!(name instanceof String) || !(url instanceof String) || !(enabled instanceof Boolean)) return false;
            String nameText = (String) name;
            if (nameText.isEmpty() || nameText.length() > PluginConstants.MAX_NAME_LENGTH
                    || PluginConstants.hasControlCharacters(nameText)) return false;
            if (!PluginSourceStore.isValidUrl((String) url)) return false;
            if (!sourceIds.add(numericId)) return false;
        }
        Object rawInstalled = block.opt("installed");
        if (!(rawInstalled instanceof JSONArray)) return false;
        JSONArray installed = (JSONArray) rawInstalled;
        if (installed.length() > PluginConstants.MAX_PLUGINS) return false;
        Set<String> pluginIds = new LinkedHashSet<>();
        for (int i = 0; i < installed.length(); i++) {
            Object raw = installed.opt(i);
            if (!(raw instanceof JSONObject)) return false;
            JSONObject plugin = (JSONObject) raw;
            Object id = plugin.opt("id");
            Object versionCode = plugin.opt("versionCode");
            Object enabled = plugin.opt("enabled");
            if (!(id instanceof String) || !PluginConstants.isValidPluginId((String) id)) return false;
            if (!(versionCode instanceof Number)) return false;
            if (((Number) versionCode).longValue() <= 0L) return false;
            if (!(enabled instanceof Boolean)) return false;
            Object name = plugin.opt("name");
            if (name != null && (!(name instanceof String)
                    || ((String) name).length() > PluginConstants.MAX_NAME_LENGTH
                    || PluginConstants.hasControlCharacters((String) name))) return false;
            if (!pluginIds.add((String) id)) return false;
        }
        return true;
    }

    /**
     * Merge plugin metadata back into this device: sources are added/updated by stable id (a URL
     * change for an existing id is a conflict and is never overwritten), installed enable flags are
     * applied only for plugins that already exist locally. Never downloads or installs anything.
     */
    public static Outcome restore(Context context, JSONObject block) {
        Outcome outcome = new Outcome();
        if (block == null) return outcome;
        if (!validate(block)) throw new IllegalStateException("插件备份元数据无效，未恢复插件");
        PluginSourceStore sourceStore = PluginSourceStore.get(context);
        PluginStore store = PluginStore.get(context);
        if (!sourceStore.isHealthy() || !store.isHealthy()) {
            outcome.warnings.add("插件安装/来源记录不健康，未恢复插件元数据；原记录已保留");
            return outcome;
        }
        if (Boolean.TRUE.equals(block.opt("unhealthy"))) {
            outcome.warnings.add("备份中的插件元数据不完整（创建时记录不健康），未恢复插件元数据");
            return outcome;
        }
        List<PluginMarketSource> previousSources = sourceStore.list();
        List<Long> addedSourceIds = new ArrayList<>();
        try {
            JSONArray sources = block.optJSONArray("sources");
            for (int i = 0; sources != null && i < sources.length(); i++) {
                JSONObject raw = sources.optJSONObject(i);
                long id;
                String name;
                String url;
                boolean enabled;
                try {
                    id = raw.getLong("id");
                    name = raw.getString("name");
                    url = raw.getString("url");
                    enabled = raw.getBoolean("enabled");
                } catch (org.json.JSONException malformed) {
                    throw new IllegalStateException("插件来源字段缺失", malformed);
                }
                PluginMarketSource existing = sourceStore.get(id);
                if (existing == null) {
                    if (!sourceStore.upsert(new PluginMarketSource(id, name, url, enabled, 0L, ""))) {
                        throw new IllegalStateException("来源写入失败");
                    }
                    addedSourceIds.add(id);
                    outcome.restoredSources++;
                } else if (!existing.getUrl().equals(url)) {
                    outcome.warnings.add("插件来源 id " + id + " 已指向其它地址，未覆盖本机来源");
                } else if (!sourceStore.setEnabled(id, enabled)) {
                    throw new IllegalStateException("来源状态写入失败");
                } else {
                    outcome.restoredSources++;
                }
            }
            JSONArray installed = block.optJSONArray("installed");
            for (int i = 0; installed != null && i < installed.length(); i++) {
                JSONObject raw = installed.optJSONObject(i);
                String id;
                int versionCode;
                try {
                    id = raw.getString("id");
                    versionCode = raw.getInt("versionCode");
                    // Legacy enabled=false is deliberately ignored; installed plugins are enabled.
                } catch (org.json.JSONException malformed) {
                    throw new IllegalStateException("插件元数据字段缺失", malformed);
                }
                InstalledPlugin current = store.find(id);
                if (current == null) {
                    outcome.pendingReinstall++;
                    outcome.warnings.add("插件 " + id + " 未安装，需从市场或本地 zip 重新安装");
                    continue;
                }
                if (current.getVersionCode() != versionCode) {
                    outcome.warnings.add("插件 " + id + " 本机版本为 " + current.getVersionCode()
                            + "，备份为 " + versionCode + "；保留本机已安装版本");
                }
                outcome.restoredInstalled++;
            }
            return outcome;
        } catch (RuntimeException failure) {
            rollbackSources(sourceStore, previousSources, addedSourceIds);
            throw new IllegalStateException("插件元数据恢复失败，已尽力回滚插件状态", failure);
        }
    }

    private static void rollbackSources(PluginSourceStore store, List<PluginMarketSource> previous,
                                        List<Long> addedIds) {
        for (Long id : addedIds) {
            try { store.remove(id); } catch (RuntimeException ignored) { }
        }
        for (PluginMarketSource source : previous) {
            try {
                PluginMarketSource current = store.get(source.getId());
                if (current == null) store.upsert(source);
                else if (current.isEnabled() != source.isEnabled()) store.setEnabled(source.getId(), source.isEnabled());
            } catch (RuntimeException ignored) { }
        }
    }

}
