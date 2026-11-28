package com.adhook.core;

import android.os.ParcelFileDescriptor;

import com.adhook.config.model.FeatureMeta;
import com.adhook.plugin.PluginConstants;
import com.adhook.plugin.PluginFeature;
import com.adhook.plugin.PluginRuntimeTable;
import com.adhook.runtime.HostContext;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Host-process bridge from the framework's remote plugin snapshot to an installed plugin entry.
 *
 * <p>Only framework remote preferences and remote files are used here. ConfigProvider, the module
 * app and host private storage are never touched from this class. The remote table is read once per
 * process and bounded by {@link PluginConstants#MAX_REMOTE_TABLE_BYTES}; any failure stays fail-open
 * so base generic hooks keep working. Plugin failures never throw into {@link HookDispatcher}.</p>
 */
public final class PluginBootstrap {

    private static final String TAG = "[PluginBootstrap] ";
    private static final Set<String> ATTEMPTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> DIAGNOSED = ConcurrentHashMap.newKeySet();
    private static volatile XposedModule module;
    private static volatile boolean tableRead;
    private static volatile PluginRuntimeTable table;
    private static volatile PluginDexLoader dexLoader = new PluginDexLoader.InMemory();
    private static volatile String unavailableReason;

    private PluginBootstrap() {}

    /** Called from the API101 entry on module load and on every package-ready callback. */
    public static void initialize(XposedModule xposedModule) {
        if (xposedModule == null || xposedModule == module) return;
        module = xposedModule;
        // A new module generation must not reuse a snapshot read from a previous generation.
        tableRead = false;
        table = null;
        unavailableReason = null;
    }

    public enum LoadResult {
        /** Plugin entry was loaded and install returned normally. */
        LOADED,
        /** Nothing to load for this package/process, or plugin routing is unavailable. */
        UNELIGIBLE,
        /** Dex/entry validation failed before the plugin's own hook registration started. */
        FAILED_BEFORE_ENTRY,
        /** The plugin entry started and then failed; regression hooks may already be installed. */
        FAILED_IN_ENTRY
    }

    /** Returns the enabled route for this host, or null when no compatible plugin is published. */
    public static PluginRuntimeTable.Route routeFor(String packageName, String processName) {
        PluginRuntimeTable snapshot = snapshot();
        if (snapshot == null) {
            warnOnce("snapshot", TAG + "no plugin snapshot for " + packageName + ": " + unavailableReason);
            return null;
        }
        PluginRuntimeTable.Route route = selectRoute(snapshot, packageName, processName,
                com.adhook.BuildConfig.VERSION_CODE);
        if (route == null) {
            warnOnce("route:" + packageName, TAG + "no plugin route for " + packageName
                    + " process=" + processName + " reason=" + unavailableReason);
        }
        return route;
    }

    /** Bounded production diagnostics: one line per package/snapshot reason per host process. */
    private static void warnOnce(String key, String message) {
        if (DIAGNOSED.add(key)) AdHookLog.w(message);
    }

    /** Pure route selection shared by production and JVM regression tests. */
    static PluginRuntimeTable.Route selectRoute(PluginRuntimeTable snapshot, String packageName,
                                                String processName, int baseVersionCode) {
        if (snapshot == null || packageName == null) return null;
        List<PluginRuntimeTable.Route> routes = snapshot.routes(packageName);
        if (routes == null || routes.isEmpty()) return null;
        for (PluginRuntimeTable.Route route : routes) {
            if (route == null || !eligible(route, baseVersionCode)) continue;
            if (!processAllowed(route, packageName, processName)) continue;
            return route;
        }
        return null;
    }

    /** True when a plugin route exists but was rejected by ABI/base-version/process policy. */
    public static String unavailableReason() {
        return unavailableReason;
    }

    public static LoadResult load(PluginRuntimeTable.Route route, HostContext host, String processName) {
        if (route == null || host == null) return LoadResult.UNELIGIBLE;
        String key = host.packageName + '|' + (processName == null ? host.processName : processName)
                + '|' + System.identityHashCode(host.classLoader);
        if (!ATTEMPTED.add(key)) return LoadResult.LOADED;
        try {
            byte[] dex = readDex(route.dexName);
            if (dex == null) {
                ATTEMPTED.remove(key);
                return LoadResult.FAILED_BEFORE_ENTRY;
            }
            String actual = sha256Hex(dex);
            if (!actual.equalsIgnoreCase(route.dexSha256)) {
                AdHookLog.e(TAG + "dex hash mismatch for " + route.pluginId, null);
                ATTEMPTED.remove(key);
                return LoadResult.FAILED_BEFORE_ENTRY;
            }
            ClassLoader pluginLoader = dexLoader.load(dex, PluginBootstrap.class.getClassLoader());
            Class<?> entry = Class.forName(route.entry, true, pluginLoader);
            if (!HostHook.class.isAssignableFrom(entry)) {
                AdHookLog.e(TAG + "entry does not implement HostHook: " + route.entry, null);
                ATTEMPTED.remove(key);
                return LoadResult.FAILED_BEFORE_ENTRY;
            }
            HostHook hook = (HostHook) entry.getDeclaredConstructor().newInstance();
            if (!hook.supports(host.packageName)) {
                AdHookLog.e(TAG + "entry rejected host package " + host.packageName + ": " + route.entry, null);
                ATTEMPTED.remove(key);
                return LoadResult.FAILED_BEFORE_ENTRY;
            }
            // Feature aliases are registered by the entry; manifest features stay the UI/registration source.
            try { hook.getFeatures(); }
            catch (Throwable aliasesFailed) {
                AdHookLog.w(TAG + "plugin feature alias registration failed; manifest features remain authoritative");
            }
            FeatureGuard.setCurrentPackage(host.packageName);
            FeatureGuard.configureHost(host.packageName, primaryOf(route, host.packageName),
                    processName == null ? host.processName : processName, featureMetas(route));
            try {
                hook.install(host);
            } catch (Throwable entryFailure) {
                // Registration may already be partially installed; do not replay another adapter.
                AdHookLog.e(TAG + "plugin entry failed for " + host.packageName + " (" + route.pluginId + ")",
                        entryFailure);
                return LoadResult.FAILED_IN_ENTRY;
            }
            AdHookLog.i(TAG + "loaded plugin " + route.pluginId + " v" + route.versionCode
                    + " for " + host.packageName + " (" + processName + ")");
            return LoadResult.LOADED;
        } catch (Throwable failure) {
            AdHookLog.e(TAG + "plugin dex/entry unavailable for " + host.packageName + " ("
                    + route.pluginId + ")", failure);
            ATTEMPTED.remove(key);
            return LoadResult.FAILED_BEFORE_ENTRY;
        }
    }

    static void setDexLoaderForTests(PluginDexLoader loader) {
        dexLoader = loader == null ? new PluginDexLoader.InMemory() : loader;
    }

    static void resetForTests() {
        ATTEMPTED.clear();
        DIAGNOSED.clear();
        tableRead = false;
        table = null;
        unavailableReason = null;
        module = null;
        dexLoader = new PluginDexLoader.InMemory();
    }

    private static PluginRuntimeTable snapshot() {
        if (tableRead) return table;
        synchronized (PluginBootstrap.class) {
            if (tableRead) return table;
            tableRead = true;
            XposedModule current = module;
            if (current == null) {
                unavailableReason = "MODULE_UNAVAILABLE";
                return null;
            }
            try {
                android.content.SharedPreferences preferences =
                        current.getRemotePreferences(PluginConstants.REMOTE_PREFS_GROUP);
                String json = preferences == null ? null
                        : preferences.getString(PluginConstants.REMOTE_TABLE_KEY, null);
                if (json == null || json.isEmpty()) {
                    unavailableReason = "NO_PLUGIN_PUBLICATION";
                    return null;
                }
                if (json.length() > PluginConstants.MAX_REMOTE_TABLE_BYTES
                        || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > PluginConstants.MAX_REMOTE_TABLE_BYTES) {
                    unavailableReason = "REMOTE_TABLE_TOO_LARGE";
                    return null;
                }
                table = PluginRuntimeTable.parse(json);
                if (table == null) {
                    unavailableReason = "REMOTE_TABLE_INVALID";
                    warnOnce("table-invalid", TAG + "plugin remote table is invalid; base hooks continue");
                } else {
                    AdHookLog.i(TAG + "plugin table loaded: packages=" + table.packageCount()
                            + " generation=" + table.generation());
                }
                return table;
            } catch (Throwable failure) {
                unavailableReason = "REMOTE_CHANNEL_UNAVAILABLE:" + failure.getClass().getSimpleName();
                AdHookLog.w(TAG + "plugin remote snapshot unavailable; base hooks continue");
                return null;
            }
        }
    }

    private static boolean eligible(PluginRuntimeTable.Route route) {
        return eligible(route, com.adhook.BuildConfig.VERSION_CODE);
    }

    /** Pure ABI/base-version gate; tests inject the base version instead of reading BuildConfig. */
    static boolean eligible(PluginRuntimeTable.Route route, int baseVersionCode) {
        if (route.abi != PluginConstants.PLUGIN_ABI) {
            unavailableReason = "PLUGIN_ABI_MISMATCH";
            return false;
        }
        if (route.minBaseVersionCode > baseVersionCode) {
            unavailableReason = "BASE_TOO_OLD";
            return false;
        }
        return true;
    }

    static boolean processAllowed(PluginRuntimeTable.Route route, String packageName, String processName) {
        List<String> processes = route.processes;
        if (processes == null || processes.isEmpty()) return true;
        String effective = processName == null ? packageName : processName;
        return processes.contains(effective);
    }

    public static String primaryOf(PluginRuntimeTable.Route route, String fallback) {
        String primary = route.primary;
        return primary == null || primary.isEmpty() ? fallback : primary;
    }

    /** Manifest features are the catalog/UI source; the entry may still register aliases. */
    public static List<FeatureMeta> featureMetas(PluginRuntimeTable.Route route) {
        List<PluginFeature> features = route.features;
        if (features == null || features.isEmpty()) return Collections.emptyList();
        List<FeatureMeta> result = new ArrayList<>(features.size());
        for (PluginFeature feature : features) {
            if (feature == null) continue;
            // Host defaults are always off; only an explicit per-feature preference enables it.
            result.add(new FeatureMeta(feature.getId(), feature.getName(), feature.getDescription(),
                    false, feature.isImplemented()));
        }
        return result;
    }

    private static byte[] readDex(String name) {
        if (name == null || name.isEmpty()) return null;
        XposedModule current = module;
        if (current == null) return null;
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = current.openRemoteFile(name);
            if (descriptor == null) return null;
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                descriptor = null;
                ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
                byte[] buffer = new byte[16 * 1024];
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    if (output.size() + read > PluginConstants.MAX_DEX_BYTES) {
                        AdHookLog.w(TAG + "plugin dex exceeds the size budget; plugin not loaded");
                        return null;
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (Throwable failure) {
            AdHookLog.w(TAG + "plugin dex read failed: " + failure.getClass().getSimpleName());
            return null;
        } finally {
            if (descriptor != null) {
                try { descriptor.close(); } catch (Throwable ignored) { }
            }
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(data);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Throwable failure) {
            return "";
        }
    }
}
