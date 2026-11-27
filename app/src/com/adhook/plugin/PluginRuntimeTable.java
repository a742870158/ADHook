package com.adhook.plugin;

import com.adhook.core.AdHookLog;

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
import java.util.TreeMap;

/**
 * Published plugin routing snapshot consumed by host processes through framework
 * RemotePreferences.
 *
 * <pre>
 * {"schema":1,"abi":2,"generation":123,"updated_at":0,
 *  "packages":{"pkg":[{"id","name","versionCode","entry","dex","sha256","abi",
 *                       "minBaseVersionCode","primary","processes",[],"features":[]}]}}
 * </pre>
 *
 * <p>The parser is defensive: unknown/malformed routes are rejected wholesale (null) instead of
 * being partially trusted, and dex file names must match the deterministic publisher naming
 * scheme. A snapshot larger than {@link PluginConstants#MAX_REMOTE_TABLE_BYTES} is never
 * published.</p>
 */
public final class PluginRuntimeTable {

    private static final int MAX_PACKAGE_KEYS = 4096;

    /** One plugin route for one package key. */
    public static final class Route {
        public final String pluginId;
        public final String name;
        public final int versionCode;
        public final String entry;
        public final String dexName;
        public final String dexSha256;
        public final int abi;
        public final int minBaseVersionCode;
        public final String primary;
        public final List<String> processes;
        public final List<PluginFeature> features;

        public Route(String pluginId, String name, int versionCode, String entry, String dexName,
                     String dexSha256, int abi, int minBaseVersionCode, String primary,
                     List<String> processes, List<PluginFeature> features) {
            this.pluginId = pluginId;
            this.name = name;
            this.versionCode = versionCode;
            this.entry = entry;
            this.dexName = dexName;
            this.dexSha256 = dexSha256;
            this.abi = abi;
            this.minBaseVersionCode = minBaseVersionCode;
            this.primary = primary != null ? primary : "";
            this.processes = Collections.unmodifiableList(
                    processes != null ? new ArrayList<>(processes) : new ArrayList<>());
            this.features = Collections.unmodifiableList(
                    features != null ? new ArrayList<>(features) : new ArrayList<>());
        }
    }

    private final long generation;
    private final long updatedAt;
    private final Map<String, List<Route>> packages;

    private PluginRuntimeTable(long generation, long updatedAt, Map<String, List<Route>> packages) {
        this.generation = generation;
        this.updatedAt = updatedAt;
        this.packages = Collections.unmodifiableMap(new LinkedHashMap<>(packages));
    }

    public static String build(List<InstalledPlugin> plugins) {
        return build(plugins, System.currentTimeMillis(), null);
    }

    public static String build(List<InstalledPlugin> plugins, long generation) {
        return build(plugins, generation, null);
    }

    /**
     * Publisher overload: {@code dexHashesByPluginId} carries freshly computed dex hashes so the
     * table always names files by the bytes actually about to be uploaded.
     */
    public static String build(List<InstalledPlugin> plugins, long generation,
                               Map<String, String> dexHashesByPluginId) {
        try {
            return buildUnchecked(plugins, generation, dexHashesByPluginId);
        } catch (org.json.JSONException failure) {
            AdHookLog.w("[PluginRuntimeTable] Cannot serialize snapshot: "
                    + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static String buildUnchecked(List<InstalledPlugin> plugins, long generation,
                                         Map<String, String> dexHashesByPluginId)
            throws org.json.JSONException {
        Map<String, JSONArray> routesByPackage = new TreeMap<>();
        Map<String, Set<String>> seen = new LinkedHashMap<>();
        if (plugins != null) {
            for (InstalledPlugin plugin : plugins) {
                if (plugin == null || !plugin.isEnabled()) continue;
                if (!PluginConstants.isValidPluginId(plugin.getId())
                        || !PluginConstants.isValidClassName(plugin.getEntry())) {
                    AdHookLog.w("[PluginRuntimeTable] Skip plugin with invalid identity: " + plugin.getId());
                    continue;
                }
                if (plugin.getAbi() != PluginConstants.PLUGIN_ABI) {
                    AdHookLog.w("[PluginRuntimeTable] Skip plugin with incompatible ABI: " + plugin.getId());
                    continue;
                }
                String dexSha = dexHashesByPluginId != null
                        ? dexHashesByPluginId.get(plugin.getId()) : plugin.getDexSha256();
                if (!PluginConstants.isHexSha256(dexSha)) {
                    AdHookLog.w("[PluginRuntimeTable] Skip plugin without a valid dex hash: " + plugin.getId());
                    continue;
                }
                String dexName = dexFileName(plugin, dexSha);
                if (dexName.isEmpty()) {
                    AdHookLog.w("[PluginRuntimeTable] Skip plugin with invalid dex file name: " + plugin.getId());
                    continue;
                }
                JSONArray features = new JSONArray();
                for (PluginFeature feature : plugin.getFeatures()) features.put(feature.toJson());
                for (PluginTarget target : plugin.getTargets()) {
                    for (String packageName : target.coveredPackages()) {
                        if (!PluginConstants.isValidPackageName(packageName)
                                || PluginConstants.isForbiddenTargetPackage(packageName)) {
                            continue;
                        }
                        Set<String> packageSeen = seen.computeIfAbsent(packageName,
                                ignored -> new LinkedHashSet<>());
                        if (!packageSeen.add(plugin.getId())) continue;
                        JSONObject route = new JSONObject();
                        route.put("id", plugin.getId());
                        route.put("name", plugin.getName());
                        route.put("versionCode", plugin.getVersionCode());
                        route.put("entry", plugin.getEntry());
                        route.put("dex", dexName);
                        route.put("sha256", dexSha);
                        route.put("abi", plugin.getAbi());
                        route.put("minBaseVersionCode", plugin.getMinBaseVersionCode());
                        route.put("primary", target.getPrimary());
                        JSONArray processes = new JSONArray();
                        for (String process : target.getProcesses()) processes.put(process);
                        route.put("processes", processes);
                        route.put("features", features);
                        routesByPackage.computeIfAbsent(packageName, ignored -> new JSONArray())
                                .put(route);
                    }
                }
            }
        }
        JSONObject root = new JSONObject();
        root.put("schema", PluginConstants.SCHEMA_VERSION);
        root.put("abi", PluginConstants.PLUGIN_ABI);
        root.put("generation", generation);
        root.put("updated_at", 0L);
        JSONObject packages = new JSONObject();
        for (Map.Entry<String, JSONArray> entry : routesByPackage.entrySet()) {
            packages.put(entry.getKey(), entry.getValue());
        }
        root.put("packages", packages);
        String json = root.toString();
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PluginConstants.MAX_REMOTE_TABLE_BYTES) {
            AdHookLog.w("[PluginRuntimeTable] Snapshot exceeds " + PluginConstants.MAX_REMOTE_TABLE_BYTES
                    + " bytes (actual " + bytes + "); not publishing");
            return null;
        }
        return json;
    }

    /** Deterministic remote dex name for a plugin version and payload hash. */
    public static String dexFileName(InstalledPlugin plugin, String dexSha256) {
        if (plugin == null) return "";
        return expectedDexFileName(plugin.getId(), plugin.getVersionCode(), dexSha256);
    }

    private static String expectedDexFileName(String pluginId, int versionCode, String dexSha256) {
        if (!PluginConstants.isValidPluginId(pluginId)
                || !PluginConstants.isHexSha256(dexSha256)) return "";
        String identity = PluginFiles.sha256Hex(pluginId + versionCode);
        return "p_" + identity.substring(0, 16) + "_" + dexSha256.substring(0, 8) + ".dex";
    }

    /** Returns null for any malformed snapshot; no partial route is ever trusted. */
    public static PluginRuntimeTable parse(String json) {
        if (json == null || json.isEmpty()) return null;
        if (json.getBytes(StandardCharsets.UTF_8).length > PluginConstants.MAX_REMOTE_TABLE_BYTES) {
            return null;
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception malformed) {
            return null;
        }
        if (!(root.opt("schema") instanceof Number)
                || ((Number) root.opt("schema")).intValue() != PluginConstants.SCHEMA_VERSION) return null;
        if (!(root.opt("abi") instanceof Number)
                || ((Number) root.opt("abi")).intValue() != PluginConstants.PLUGIN_ABI) return null;
        Object rawGeneration = root.opt("generation");
        if (!(rawGeneration instanceof Number)) return null;
        long generation = ((Number) rawGeneration).longValue();
        if (generation < 0L) return null;
        Object rawUpdatedAt = root.opt("updated_at");
        long updatedAt = 0L;
        if (rawUpdatedAt != null) {
            if (!(rawUpdatedAt instanceof Number)) return null;
            updatedAt = ((Number) rawUpdatedAt).longValue();
            if (updatedAt < 0L) return null;
        }
        Object rawPackages = root.opt("packages");
        if (!(rawPackages instanceof JSONObject)) return null;
        JSONObject packageObject = (JSONObject) rawPackages;
        if (packageObject.length() > MAX_PACKAGE_KEYS) return null;
        Map<String, List<Route>> packages = new LinkedHashMap<>();
        int totalRoutes = 0;
        java.util.Iterator<String> keys = packageObject.keys();
        while (keys.hasNext()) {
            String packageName = keys.next();
            if (!PluginConstants.isValidPackageName(packageName)
                    || PluginConstants.isForbiddenTargetPackage(packageName)) return null;
            Object rawRoutes = packageObject.opt(packageName);
            if (!(rawRoutes instanceof JSONArray)) return null;
            JSONArray routeArray = (JSONArray) rawRoutes;
            if (routeArray.length() < 1 || routeArray.length() > PluginConstants.MAX_PLUGINS) return null;
            totalRoutes += routeArray.length();
            if (totalRoutes > PluginConstants.MAX_PLUGINS * PluginConstants.MAX_TARGETS) return null;
            List<Route> routes = new ArrayList<>(routeArray.length());
            Set<String> seenIds = new LinkedHashSet<>();
            for (int i = 0; i < routeArray.length(); i++) {
                Object rawRoute = routeArray.opt(i);
                if (!(rawRoute instanceof JSONObject)) return null;
                Route route = parseRoute((JSONObject) rawRoute);
                if (route == null) return null;
                if (!seenIds.add(route.pluginId)) return null;
                routes.add(route);
            }
            packages.put(packageName, Collections.unmodifiableList(routes));
        }
        return new PluginRuntimeTable(generation, updatedAt, packages);
    }

    private static Route parseRoute(JSONObject json) {
        Object rawId = json.opt("id");
        Object rawName = json.opt("name");
        Object rawVersionCode = json.opt("versionCode");
        Object rawEntry = json.opt("entry");
        Object rawDex = json.opt("dex");
        Object rawSha = json.opt("sha256");
        Object rawAbi = json.opt("abi");
        Object rawMinBase = json.opt("minBaseVersionCode");
        if (!(rawId instanceof String) || !(rawName instanceof String)
                || !(rawVersionCode instanceof Number) || !(rawEntry instanceof String)
                || !(rawDex instanceof String) || !(rawSha instanceof String)
                || !(rawAbi instanceof Number) || !(rawMinBase instanceof Number)) return null;
        String pluginId = (String) rawId;
        String name = (String) rawName;
        int versionCode = ((Number) rawVersionCode).intValue();
        String entry = (String) rawEntry;
        String dexName = (String) rawDex;
        String dexSha = (String) rawSha;
        int abi = ((Number) rawAbi).intValue();
        int minBaseVersionCode = ((Number) rawMinBase).intValue();
        if (!PluginConstants.isValidPluginId(pluginId)) return null;
        if (name.isEmpty() || name.length() > PluginConstants.MAX_NAME_LENGTH
                || PluginConstants.hasControlCharacters(name)) return null;
        if (versionCode <= 0) return null;
        if (!PluginConstants.isValidClassName(entry)) return null;
        if (!PluginConstants.isHexSha256(dexSha)) return null;
        if (!PluginConstants.isValidDexFileName(dexName)) return null;
        if (!dexName.equals(expectedDexFileName(pluginId, versionCode, dexSha))) return null;
        if (abi != PluginConstants.PLUGIN_ABI) return null;
        if (minBaseVersionCode < 0) return null;

        String primary = "";
        Object rawPrimary = json.opt("primary");
        if (rawPrimary != null) {
            if (!(rawPrimary instanceof String)) return null;
            primary = (String) rawPrimary;
            if (!primary.isEmpty() && (!PluginConstants.isValidPackageName(primary)
                    || PluginConstants.isForbiddenTargetPackage(primary))) return null;
        }
        List<String> processes = new ArrayList<>();
        Object rawProcesses = json.opt("processes");
        if (rawProcesses != null) {
            if (!(rawProcesses instanceof JSONArray)) return null;
            JSONArray processArray = (JSONArray) rawProcesses;
            if (processArray.length() > PluginConstants.MAX_PROCESSES) return null;
            Set<String> unique = new LinkedHashSet<>();
            for (int i = 0; i < processArray.length(); i++) {
                Object value = processArray.opt(i);
                if (!(value instanceof String)
                        || !PluginConstants.isValidProcessName((String) value)) return null;
                if (!unique.add((String) value)) return null;
            }
            processes.addAll(unique);
        }
        List<PluginFeature> features = new ArrayList<>();
        Object rawFeatures = json.opt("features");
        if (rawFeatures != null) {
            if (!(rawFeatures instanceof JSONArray)) return null;
            JSONArray featureArray = (JSONArray) rawFeatures;
            if (featureArray.length() > PluginConstants.MAX_FEATURES) return null;
            Set<String> featureIds = new LinkedHashSet<>();
            for (int i = 0; i < featureArray.length(); i++) {
                Object value = featureArray.opt(i);
                if (!(value instanceof JSONObject)) return null;
                PluginFeature feature = PluginFeature.fromJson((JSONObject) value);
                if (feature == null) return null;
                if (!featureIds.add(feature.getId())) return null;
                features.add(feature);
            }
        }
        return new Route(pluginId, name, versionCode, entry, dexName, dexSha, abi,
                minBaseVersionCode, primary, processes, features);
    }

    public List<Route> routes(String packageName) {
        List<Route> routes = packageName != null ? packages.get(packageName) : null;
        return routes != null ? routes : Collections.emptyList();
    }

    public Map<String, List<Route>> packages() {
        return packages;
    }

    public long generation() { return generation; }

    public long updatedAt() { return updatedAt; }

    public int packageCount() { return packages.size(); }
}
