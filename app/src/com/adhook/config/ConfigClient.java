package com.adhook.config;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import com.adhook.config.model.GlobalConfig;
import com.adhook.core.AdHookLog;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主进程内的轻量级配置读取客户端。
 * 具备内存缓存与故障自动降级机制，绝不中断宿主正常流程。
 *
 * 安全说明：仅正式 API101 LibXposed remote preferences 与受调用方鉴权保护的
 * ConfigProvider（仅工作线程兜底）。不读取 XSharedPreferences 或 CE/DE 文件镜像。
 * 宿主 initializeHookRuntime 后业务查询只读独立内存快照，未知策略不允许执行 Hook。
 * 旧版本的 /sdcard、/data/local/tmp 等外部可写文件回退通道已删除，防止任意应用投毒配置。
 *
 * 一致性：宿主工作线程按 CACHE_TTL_MS 刷新，版本推进后原子替换业务快照。
 * 来源不可用 / Binder 冻结会延后刷新，但不阻塞宿主安装或回调；未知状态保持业务放行。
 */
public final class ConfigClient {

    private static final Uri PROVIDER_URI = Uri.parse("content://" + ConfigProvider.AUTHORITY);
    private static volatile io.github.libxposed.api.XposedModule sXposedModule;
    /** A failed official remote read is logged once until the channel recovers. */
    private static volatile boolean sRemoteReadWarned = false;

    private static volatile Context sContext;
    private static volatile GlobalConfig sGlobalConfig;
    private static volatile long sGlobalConfigTimestamp = 0;
    /** 上一次读取是否走了「降级默认值」：降级时只做 1 秒短负缓存，避免热路径反复解析整份配置。 */
    private static volatile boolean sGlobalFallback = false;
    private static final Map<String, AppConfig> sAppConfigCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> sCacheTimestamps = new ConcurrentHashMap<>();
    /** 记录走降级默认值的包名，用短负缓存 TTL，避免把「默认空配置」当成 5 秒有效配置。 */
    private static final Set<String> sFallbackPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 每个进程只打印一次配置来源诊断，便于区分「读不到配置」与「开关本身为 false」。 */
    private static volatile boolean sSourceLogged = false;
    /** 两条受信通道都不可用的包名只提示一次，避免热路径刷日志。 */
    private static final Set<String> sNoSourceWarned =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** 每个包首次成功解析出应用配置时提示一次（来源、开关数量），用于现场核对「配置到底读到没有」。 */
    private static final Set<String> sAppConfigLogged =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 异步拉取执行器，确保主线程永不执行阻塞 Binder 跨进程呼叫，杜绝后台进程被冻结（cgroup freeze）引发 ANR。 */
    private static final java.util.concurrent.ScheduledExecutorService sAsyncExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "adhook-config-async");
                thread.setDaemon(true);
                return thread;
            });
    private static final Set<String> sRefreshingPackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile boolean sGlobalRefreshing = false;

    private static boolean isMainThread() {
        return android.os.Looper.myLooper() != null
                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }

    private static final long CACHE_TTL_MS = 5000;      // 正常配置缓存：5 秒
    private static final long FALLBACK_TTL_MS = 1000;   // 读取失败降级默认值：1 秒短负缓存

    /** 已见到的配置版本时间戳（updated_at），用于主进程保存后让宿主进程自动失效缓存。 */
    private static volatile long sSeenUpdatedAt = 0L;

    /** 解析后的配置快照短缓存：避免 getGlobalConfig 与 getAppConfig 在同一个 TTL 周期内重复解析整份 JSON。 */
    private static volatile JSONObject sSnapshotCache;
    private static volatile long sSnapshotCacheAt = 0L;
    private static final long SNAPSHOT_CACHE_MS = 1500L;

    private static volatile HookRuntimeSnapshot sHookRuntime = HookRuntimeSnapshot.empty();
    private static final Set<String> sRuntimePackages =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static boolean sRuntimeRefreshStarted;
    private static final HookProviderCalibration sProviderCalibration = new HookProviderCalibration();

    private ConfigClient() {
    }

    /** Early trusted preferences only. Provider and full-rule parsing always run asynchronously. */
    public static void initializeHookRuntime(String packageName) {
        if (!com.adhook.config.security.ConfigInputPolicy.isPackageName(packageName)) return;
        boolean added;
        synchronized (ConfigClient.class) {
            if (!sRuntimePackages.contains(packageName)
                    && sRuntimePackages.size() >= HookProviderCalibration.MAX_PACKAGES) return;
            added = sRuntimePackages.add(packageName);
        }
        if (added) publishHookRuntime(HookRuntimeSnapshot.parseLaunch(readTrustedLaunchJson()));
        synchronized (ConfigClient.class) {
            if (!sRuntimeRefreshStarted) {
                sRuntimeRefreshStarted = true;
                sAsyncExecutor.scheduleWithFixedDelay(ConfigClient::refreshHookRuntime,
                        0L, CACHE_TTL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
    }

    /** UNKNOWN is false: installed callbacks must leave the host business operation unchanged. */
    public static boolean isHookEnabledCached(String packageName) {
        AppConfig app = getAppConfigCached(packageName);
        return app != null && app.isHookEnabled();
    }

    /** Pure memory, no TTL/source reads. Snapshot objects are read-only to consumers. */
    public static AppConfig getAppConfigCached(String packageName) {
        return packageName == null ? null : sHookRuntime.apps.get(packageName);
    }

    /** Pure memory. Unknown global rule policy is conservative until a trusted snapshot arrives. */
    public static GlobalConfig getGlobalConfigCached() {
        return sHookRuntime.global;
    }

    /** Opaque coherent-cache identity: pure memory, no allocation or source reads. */
    public static Object hookRuntimeIdentityCached() { return sHookRuntime; }

    private static String readTrustedLaunchJson() {
        io.github.libxposed.api.XposedModule module = sXposedModule;
        if (module != null) {
            try {
                String json = module.getRemotePreferences("adhook_config")
                        .getString(HookLaunchSnapshot.KEY, null);
                if (HookRuntimeSnapshot.parseLaunch(json) != null) return json;
            } catch (Throwable ignored) {
                // An unavailable official channel leaves launch policy UNKNOWN; a worker may calibrate.
            }
        }
        return null; // No legacy file, XSharedPreferences or externally readable mirror channel.
    }

    private static synchronized void publishHookRuntime(HookRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.updatedAt < sHookRuntime.updatedAt
                || (snapshot.updatedAt == sHookRuntime.updatedAt && (sHookRuntime.complete || !snapshot.complete))) return;
        sHookRuntime = snapshot;
        notifyRuntimeRulesChanged();
    }

    private static void notifyRuntimeRulesChanged() {
        try {
            for (String pkg : sRuntimePackages) {
                com.adhook.core.FeatureGuard.onHookConfigChanged(pkg);
                com.adhook.inspector.VisualInspector.onHookConfigChanged(pkg);
            }
            com.adhook.engine.CustomRuleEngine.notifyRulesChanged();
        } catch (LinkageError | RuntimeException ignored) {
            // Installation may not yet have initialized the rules engine.
        }
    }

    private static void refreshHookRuntime() {
        HookRuntimeSnapshot complete = null;
        try {
            publishHookRuntime(HookRuntimeSnapshot.parseLaunch(readTrustedLaunchJson()));
            complete = HookRuntimeSnapshot.parseFull(readSnapshot());
        } catch (Throwable ignored) {
            // A broken trusted mirror must not prevent separately authorized calibration.
        }
        try {
            finishHookRuntimeRefresh(complete, getContext(), android.os.SystemClock.elapsedRealtime());
        } catch (Throwable ignored) {
            // Failure/denial never turns UNKNOWN into permission, and never blocks hook installation.
        }
    }

    /** Worker path: a valid-but-stale complete remote snapshot never short-circuits calibration. */
    static void finishHookRuntimeRefresh(HookRuntimeSnapshot complete, Context context, long now) {
        publishHookRuntime(complete);
        if (context == null || isMainThread()) return;
        String pkg = sProviderCalibration.take(now, sRuntimePackages);
        if (pkg == null) return;
        boolean success = false;
        try {
            Bundle response = context.getContentResolver().call(PROVIDER_URI,
                    ConfigProvider.METHOD_GET_HOOK_RUNTIME, pkg, null);
            String json = response == null ? null : response.getString("json");
            HookRuntimeSnapshot scoped = json == null ? null
                    : HookRuntimeSnapshot.parseFull(new JSONObject(json));
            if (scoped == null || (scoped.apps.size() > 0
                    && (scoped.apps.size() != 1 || !scoped.apps.containsKey(pkg)))) return;
            success = true;
            synchronized (ConfigClient.class) {
                if (scoped.updatedAt < sHookRuntime.updatedAt) return;
                Long packageVersion = sHookRuntime.appVersions.get(pkg);
                if (packageVersion != null && packageVersion == scoped.updatedAt
                        && sHookRuntime.updatedAt == scoped.updatedAt && sHookRuntime.isPackageComplete(pkg)) return;
                boolean changed = !sHookRuntime.isPackageComplete(pkg) || packageVersion == null
                        || scoped.updatedAt > packageVersion;
                Map<String, AppConfig> apps = new java.util.LinkedHashMap<>(sHookRuntime.apps);
                Map<String, Long> versions = new java.util.LinkedHashMap<>(sHookRuntime.appVersions);
                Set<String> completePackages = new java.util.LinkedHashSet<>(sHookRuntime.completePackages);
                apps.remove(pkg);
                apps.putAll(scoped.apps);
                versions.put(pkg, scoped.updatedAt);
                completePackages.add(pkg);
                // Package-scoped rules are complete, but this is not a coherent full root for all packages.
                // A same-version full remote root must still be allowed to catch up after a retry succeeds.
                sHookRuntime = new HookRuntimeSnapshot(scoped.updatedAt, false, scoped.global, apps, versions,
                        completePackages);
                if (changed) notifyRuntimeRulesChanged();
            }
        } catch (Throwable ignored) {
            // Caller authorization is unchanged; empty/denied/malformed responses consume the budget.
        } finally {
            // Completion-relative backoff cannot be bypassed by repeated Context-ready notifications.
            long completedAt = Math.max(now, android.os.SystemClock.elapsedRealtime());
            sProviderCalibration.completed(completedAt, success);
        }
    }

    public static void setXposedModule(io.github.libxposed.api.XposedModule module) {
        if (module != null && sXposedModule != module) {
            sXposedModule = module;
            sSnapshotCache = null;
            sSnapshotCacheAt = 0L;
        }
    }

    /** Full official remote policy is read and parsed only on a worker, never from local mirrors. */
    private static JSONObject readSnapshot() {
        long cacheNow = System.currentTimeMillis();
        JSONObject snapshotCached = sSnapshotCache;
        if (snapshotCached != null && (cacheNow - sSnapshotCacheAt) < SNAPSHOT_CACHE_MS) {
            return snapshotCached;
        }
        if (isMainThread()) return null;
        io.github.libxposed.api.XposedModule module = sXposedModule;
        if (module == null) return null;
        try {
            android.content.SharedPreferences remotePrefs = module.getRemotePreferences("adhook_config");
            String jsonStr = remotePrefs == null ? null : remotePrefs.getString("config_json", null);
            if (jsonStr == null || jsonStr.trim().isEmpty()) return null;
            JSONObject json = new JSONObject(jsonStr);
            if (HookRuntimeSnapshot.parseFull(json) == null) return null;
            sRemoteReadWarned = false;
            logSnapshotSource("LibXposed RemotePreferences", json);
            checkUpdatedAt(json);
            sSnapshotCache = json;
            sSnapshotCacheAt = System.currentTimeMillis();
            return json;
        } catch (Throwable failure) {
            if (!sRemoteReadWarned) {
                sRemoteReadWarned = true;
                AdHookLog.w("[ConfigClient] RemotePreferences read warning: " + failure.getClass().getSimpleName());
            }
            return null;
        }
    }

    /**
     * 每个进程只记录一次配置快照来源，便于现场区分「配置没读到」和「开关本来就是 false」。
     * 仅打印来源、apps 数量与当前宿主条目是否存在，不打印任何规则或完整 JSON。
     */
    private static void logSnapshotSource(String channel, JSONObject json) {
        if (sSourceLogged || json == null) return;
        sSourceLogged = true;
        try {
            JSONObject apps = json.optJSONObject("apps");
            String pkg = com.adhook.core.FeatureGuard.currentPackage();
            AdHookLog.i("[ConfigClient] snapshot source=" + channel + ", apps=" + (apps == null ? 0 : apps.length())
                    + ", hasCurrentApp=" + (apps != null && pkg != null && apps.has(pkg))
                    + ", updatedAt=" + json.optLong("updated_at", 0L));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 配置版本（updated_at）变化时立即失效本进程缓存，实现主进程保存后的快速生效。
     */
    private static void checkUpdatedAt(JSONObject json) {
        try {
            long updatedAt = json.optLong("updated_at", 0L);
            if (updatedAt > 0L && updatedAt != sSeenUpdatedAt) {
                sSeenUpdatedAt = updatedAt;
                invalidateCache(null);
                AdHookLog.i("[ConfigClient] 检测到配置版本变化，已失效本地缓存 updated_at=" + updatedAt);
            }
        } catch (Throwable ignored) {
            // 版本字段异常不影响读取
        }
    }

    public static void init(Context context) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        Context readyContext = appCtx != null ? appCtx : context;
        boolean refreshRuntime;
        synchronized (ConfigClient.class) {
            boolean wasEmpty = (sContext == null);
            boolean recoveringFallback = !sFallbackPackages.isEmpty();
            refreshRuntime = sContext != readyContext || recoveringFallback;
            sContext = readyContext;
            if (wasEmpty || recoveringFallback) {
                sFallbackPackages.clear();
                sAppConfigCache.clear();
                sCacheTimestamps.clear();
            }
        }
        // Repeated Activity readiness must not parse full snapshots again; the periodic worker remains active.
        if (refreshRuntime && !sRuntimePackages.isEmpty()) sAsyncExecutor.execute(ConfigClient::refreshHookRuntime);
        // Keep telemetry readiness/retry semantics independent from configuration refresh deduplication.
        com.adhook.core.FeatureGuard.onContextReady();
    }

    public static Context getContext() {
        Context ctx = sContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            android.app.Application app = com.adhook.runtime.HostContext.currentApplication();
            if (app != null) {
                init(app);
            }
        } catch (Throwable ignored) {
        }
        return sContext;
    }

    /**
     * 查询「本地自定义规则」总开关是否启用（供 CustomRuleEngine 消费）。
     */
    public static boolean isLocalRulesEnabled() {
        GlobalConfig gc = getGlobalConfig();
        return gc == null || gc.isLocalRulesEnabled();
    }

    /**
     * 获取全局配置。
     * 主线程绝不执行同步 Provider 跨进程 Binder IPC，避免后台模块进程被系统冻结（cgroup freeze）时挂死主线程导致 ANR。
     */
    public static GlobalConfig getGlobalConfig() {
        if (!sRuntimePackages.isEmpty()) return getGlobalConfigCached();
        long now = System.currentTimeMillis();
        GlobalConfig cached = sGlobalConfig;
        long ttl = sGlobalFallback ? FALLBACK_TTL_MS : CACHE_TTL_MS;
        if (cached != null && (now - sGlobalConfigTimestamp) < ttl) {
            return cached;
        }

        // Worker-only official remote snapshot (or an already validated memory cache).
        JSONObject snapshot = readSnapshot();
        if (snapshot != null) {
            JSONObject gObj = snapshot.optJSONObject("global_sdk");
            GlobalConfig result = (gObj != null) ? GlobalConfig.fromJson(gObj) : new GlobalConfig();
            sGlobalConfig = result;
            sGlobalConfigTimestamp = now;
            sGlobalFallback = false;
            return result;
        }

        // 2) 受信通道二：Provider 兜底（跨进程 Binder 调用）
        // 关键防护：主线程调用时，绝不同步执行跨进程 IPC！
        if (isMainThread()) {
            if (cached != null) {
                scheduleGlobalConfigRefresh();
                return cached;
            }
            // 主线程初次且读不到 snapshot：立即返回默认配置作为短负缓存，后台异步拉取
            GlobalConfig fallback = new GlobalConfig();
            sGlobalConfig = fallback;
            sGlobalConfigTimestamp = now;
            sGlobalFallback = true;
            scheduleGlobalConfigRefresh();
            return fallback;
        }

        // 后台线程可安全执行同步跨进程拉取
        return fetchGlobalConfigDirect(now);
    }

    private static void scheduleGlobalConfigRefresh() {
        if (sGlobalRefreshing) return;
        sGlobalRefreshing = true;
        sAsyncExecutor.execute(() -> {
            try {
                fetchGlobalConfigDirect(System.currentTimeMillis());
            } finally {
                sGlobalRefreshing = false;
            }
        });
    }

    private static GlobalConfig fetchGlobalConfigDirect(long now) {
        GlobalConfig result = null;
        boolean fromSource = false;
        Context ctx = getContext();
        if (ctx != null) {
            try {
                ContentResolver resolver = ctx.getContentResolver();
                Bundle res = resolver.call(PROVIDER_URI, ConfigProvider.METHOD_GET_GLOBAL_CONFIG, null, null);
                if (res != null) {
                    String jsonStr = res.getString("json");
                    if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                        result = GlobalConfig.fromJson(new JSONObject(jsonStr));
                        fromSource = true;
                    }
                }
            } catch (Throwable t) {
                AdHookLog.w("[ConfigClient] Provider getGlobalConfig fallback failed: " + t.getMessage());
            }
        }

        if (result == null) {
            result = (sGlobalConfig != null) ? sGlobalConfig : new GlobalConfig();
        }

        sGlobalConfig = result;
        sGlobalConfigTimestamp = now;
        sGlobalFallback = !fromSource;
        return result;
    }

    /**
     * 查询指定原生功能特性是否启用。
     */
    public static boolean isFeatureEnabled(String packageName, String featureId, boolean defaultValue) {
        if (packageName == null || featureId == null) {
            return defaultValue;
        }
        String pkg = com.adhook.core.FeatureGuard.runtimePackageFor(packageName);
        AppConfig config = getAppConfig(pkg);
        return com.adhook.core.FeatureGuard.configuredEnabled(config, pkg, featureId, defaultValue);
    }

    /**
     * 查询当前宿主是否开启了元素拾取器。
     */
    public static boolean isInspectorEnabled(String packageName) {
        if (packageName == null) {
            return false;
        }
        AppConfig config = getAppConfig(packageName);
        return config != null && config.isInspectorEnabled();
    }

    /**
     * 查询当前宿主是否开启了元素拾取器（穿透短缓存，确保开关刚在 ADHook 打开后切回宿主即刻生效）。
     */
    public static boolean isInspectorEnabledFresh(String packageName) {
        if (packageName == null) {
            return false;
        }
        if (sRuntimePackages.contains(packageName)) {
            AppConfig cached = getAppConfigCached(packageName);
            return isHookEnabledCached(packageName) && cached != null && cached.isInspectorEnabled();
        }
        if (isInspectorEnabled(packageName)) {
            return true;
        }

        // 若当前缓存为 false，强制失效快照与包级缓存，重新读取最新持久化配置
        sSnapshotCache = null;
        sSnapshotCacheAt = 0L;
        sAppConfigCache.remove(packageName);
        sCacheTimestamps.remove(packageName);

        AppConfig freshConfig = getAppConfig(packageName);
        if (freshConfig != null && freshConfig.isInspectorEnabled()) {
            return true;
        }

        // 非宿主兼容读取也不得在主线程同步进入 Provider。
        if (isMainThread()) {
            scheduleAppConfigRefresh(packageName);
            return false;
        }
        if (sContext != null) {
            try {
                Bundle res = sContext.getContentResolver().call(
                        PROVIDER_URI,
                        ConfigProvider.METHOD_IS_INSPECTOR_ENABLED,
                        packageName,
                        null
                );
                if (res != null && res.getBoolean("enabled", false)) {
                    if (freshConfig != null) {
                        freshConfig.setInspectorEnabled(true);
                    } else {
                        AppConfig cfg = new AppConfig(packageName);
                        cfg.setInspectorEnabled(true);
                        sAppConfigCache.put(packageName, cfg);
                        sCacheTimestamps.put(packageName, System.currentTimeMillis());
                    }
                    return true;
                }
            } catch (Throwable t) {
                AdHookLog.w("[ConfigClient] Provider isInspectorEnabled check failed: " + t.getMessage());
            }
        }

        return false;
    }

    /**
     * 获取指定应用的完整配置（带缓存）。
     * 主线程绝不执行同步 Provider 跨进程 Binder IPC，避免后台模块进程被系统冻结（cgroup freeze）时挂死主线程导致 ANR。
     */
    public static AppConfig getAppConfig(String packageName) {
        if (packageName == null) return null;
        if (sRuntimePackages.contains(packageName)) return getAppConfigCached(packageName);

        long now = System.currentTimeMillis();
        Long last = sCacheTimestamps.get(packageName);
        AppConfig cached = sAppConfigCache.get(packageName);
        long ttl = sFallbackPackages.contains(packageName) ? FALLBACK_TTL_MS : CACHE_TTL_MS;
        if (cached != null && last != null && (now - last) < ttl) {
            return cached;
        }

        // Worker-only official remote snapshot (or an already validated memory cache).
        JSONObject snapshot = readSnapshot();
        if (snapshot != null) {
            JSONObject apps = snapshot.optJSONObject("apps");
            AppConfig result = null;
            if (apps != null) {
                JSONObject appObj = apps.optJSONObject(packageName);
                if (appObj != null) {
                    result = AppConfig.fromJson(appObj);
                }
            }
            AppConfig value = (result != null) ? result : new AppConfig(packageName);
            if (sAppConfigLogged.add(packageName)) {
                AdHookLog.i("[ConfigClient] app config loaded pkg=" + packageName
                        + " nativeFeatures=" + value.getNativeFeatures().size());
            }
            sAppConfigCache.put(packageName, value);
            sCacheTimestamps.put(packageName, now);
            sFallbackPackages.remove(packageName);
            return value;
        }

        // 2) 受信通道二：Provider 兜底（跨进程 Binder 调用）
        // 关键防护：主线程调用时，绝不同步执行跨进程 IPC！
        if (isMainThread()) {
            if (cached != null) {
                scheduleAppConfigRefresh(packageName);
                return cached;
            }
            // 主线程初次且读不到 snapshot：立即返回默认配置作为短负缓存，后台异步拉取
            AppConfig fallback = new AppConfig(packageName);
            sAppConfigCache.put(packageName, fallback);
            sCacheTimestamps.put(packageName, now);
            sFallbackPackages.add(packageName);
            scheduleAppConfigRefresh(packageName);
            return fallback;
        }

        // 后台线程可安全执行同步跨进程拉取
        return fetchAppConfigDirect(packageName, now);
    }

    private static void scheduleAppConfigRefresh(String packageName) {
        if (packageName == null || !sRefreshingPackages.add(packageName)) return;
        sAsyncExecutor.execute(() -> {
            try {
                fetchAppConfigDirect(packageName, System.currentTimeMillis());
            } finally {
                sRefreshingPackages.remove(packageName);
            }
        });
    }

    private static AppConfig fetchAppConfigDirect(String packageName, long now) {
        AppConfig result = null;
        boolean fromSource = false;
        Context ctx = getContext();
        if (ctx != null) {
            try {
                Bundle res = ctx.getContentResolver().call(
                        PROVIDER_URI,
                        ConfigProvider.METHOD_GET_APP_CONFIG,
                        packageName,
                        null
                );
                if (res != null) {
                    String json = res.getString("json");
                    if (json != null && !json.trim().isEmpty()) {
                        result = AppConfig.fromJson(new JSONObject(json));
                        fromSource = true;
                    }
                }
            } catch (Throwable t) {
                AdHookLog.w("[ConfigClient] Provider getAppConfig fallback failed: " + t.getMessage());
            }
        }

        if (!fromSource) {
            // 两条受信通道都不可用：降级默认值并只做短负缓存，避免热路径反复重试解析
            if (sNoSourceWarned.add(packageName)) {
                AdHookLog.w("[ConfigClient] no config source for " + packageName
                        + " (contextReady=" + (ctx != null) + "); built-in defaults in use");
            }
            AppConfig cached = sAppConfigCache.get(packageName);
            AppConfig fallback = (cached != null && sFallbackPackages.contains(packageName))
                    ? cached : new AppConfig(packageName);
            sAppConfigCache.put(packageName, fallback);
            sCacheTimestamps.put(packageName, now);
            sFallbackPackages.add(packageName);
            return fallback;
        }

        // 配置源可用但该包没有配置条目：返回默认值并按正常 TTL 缓存（不是读取失败）
        AppConfig value = (result != null) ? result : new AppConfig(packageName);
        if (sAppConfigLogged.add(packageName)) {
            AdHookLog.i("[ConfigClient] app config loaded pkg=" + packageName
                    + " nativeFeatures=" + value.getNativeFeatures().size());
        }
        sAppConfigCache.put(packageName, value);
        sCacheTimestamps.put(packageName, now);
        sFallbackPackages.remove(packageName);
        return value;
    }

    /**
     * 宿主端保存自定义规则（交给主 App 的 ConfigManager 持久化）。
     * 不再写任何外部文件；任何失败都只返回 false，不抛出、不影响宿主。
     */
    public static boolean saveCustomRule(String packageName, CustomRule rule) {
        if (packageName == null || rule == null || isMainThread()) {
            return false;
        }

        // 先失效本地缓存，确保保存成功后下一次读取能尽快看到新规则
        invalidateCache(packageName);

        if (sContext == null) {
            AdHookLog.w("[ConfigClient] saveCustomRule skipped: context not initialized");
            return false;
        }

        try {
            Bundle extras = new Bundle();
            extras.putString("ruleJson", rule.toJson().toString());
            Bundle res = sContext.getContentResolver().call(
                    PROVIDER_URI,
                    ConfigProvider.METHOD_ADD_CUSTOM_RULE,
                    packageName,
                    extras
            );
            if (res != null && res.getBoolean("success", false)) {
                invalidateCache(packageName);
                return true;
            }
        } catch (Throwable t) {
            AdHookLog.w("[ConfigClient] saveCustomRule via Provider failed: " + t.getMessage());
        }
        return false;
    }

    /**
     * 清空缓存（配置变更时调用：主进程 save() 之后、Provider 写入之后、updated_at 变化时）。
     */
    public static void invalidateCache(String packageName) {
        // Package writes invalidate the shared snapshot too; otherwise it can resurrect stale rules.
        sSnapshotCache = null;
        sSnapshotCacheAt = 0L;
        if (packageName != null) {
            sAppConfigCache.remove(packageName);
            sCacheTimestamps.remove(packageName);
            sFallbackPackages.remove(packageName);
        } else {
            sAppConfigCache.clear();
            sCacheTimestamps.clear();
            sFallbackPackages.clear();
            sGlobalConfig = null;
            sGlobalConfigTimestamp = 0;
            sGlobalFallback = false;
            sSnapshotCache = null;
            sSnapshotCacheAt = 0L;
        }
        // Notify only after every relevant cache has been cleared; the engine schedules its own main-thread work.
        try {
            com.adhook.engine.CustomRuleEngine.notifyRulesChanged();
        } catch (LinkageError | RuntimeException ignored) {
            // The plain control app need not have Xposed classes available; cache invalidation still succeeded.
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService sStatsExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "adhook-stats-sync");
                t.setDaemon(true);
                return t;
            });
    private static final String STATS_SESSION_ID = java.util.UUID.randomUUID().toString().replace("-", "");
    private static final com.adhook.core.LatestStatsDelivery<JSONObject> STATS_DELIVERY =
            new com.adhook.core.LatestStatsDelivery<>(android.os.SystemClock::elapsedRealtime,
                    (task, delay) -> sStatsExecutor.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS),
                    ConfigClient::sendRuntimeStats, 2500L, 7);
    private static long sLastStatsTruncationLog = -1L;

    private static void reportStatsTruncation() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sLastStatsTruncationLog < 0 || now - sLastStatsTruncationLog >= 60_000L) {
            sLastStatsTruncationLog = now;
            AdHookLog.w("[ConfigClient] Runtime statistics truncated to bounded packet; rule execution unchanged");
        }
    }

    /** Called on each change; snapshot materialization and all Binder calls stay off the hook/UI thread. */
    public static void scheduleRuntimeStats(String packageName, java.util.function.Supplier<JSONObject> snapshot) {
        if (!com.adhook.config.security.ConfigInputPolicy.isPackageName(packageName)
                || "com.adhook".equals(packageName) || snapshot == null) return;
        STATS_DELIVERY.offer(packageName, snapshot);
    }

    /** Compatibility producer entry point: capture immutable bounded JSON before scheduling it. */
    public static void reportRuntimeStatsAsync(String packageName, JSONObject statsJson) {
        com.adhook.config.security.RuntimeStatsPacket packet =
                com.adhook.config.security.RuntimeStatsPacket.prepare(statsJson);
        if (packet == null) return;
        scheduleRuntimeStats(packageName, () -> {
            try { return new JSONObject(packet.json); }
            catch (org.json.JSONException ignored) { return null; }
        });
    }

    private static boolean sendRuntimeStats(String packageName, JSONObject statsJson) {
        Context ctx = getContext();
        if (ctx == null) return false;
        try {
            com.adhook.config.security.RuntimeStatsPacket packet =
                    com.adhook.config.security.RuntimeStatsPacket.prepare(statsJson);
            if (packet == null || com.adhook.config.security.RuntimeStatsPolicy.parse(packet.json) == null) return false;
            if (packet.truncated) reportStatsTruncation();
            String process = com.adhook.core.FeatureGuard.currentProcessName();
            if (process == null) process = packageName;
            else if (!process.equals(packageName) && !process.startsWith(packageName + ":")) {
                // Shared/named processes retain a stable, package-scoped slot rather than overwriting main.
                process = packageName + ":shared-" + Integer.toHexString(process.hashCode());
            }
            Bundle extras = new Bundle();
            extras.putString("statsJson", packet.json);
            extras.putString("processName", process);
            extras.putString("sessionId", STATS_SESSION_ID);
            extras.putBoolean("statsTruncated", packet.truncated);
            Bundle response = ctx.getContentResolver().call(PROVIDER_URI,
                    ConfigProvider.METHOD_REPORT_RUNTIME_STATS, packageName, extras);
            return response != null && response.getBoolean("success", false);
        } catch (Throwable ignored) {
            // Unavailable/denied IPC is telemetry failure, never permission to bypass authentication.
            return false;
        }
    }
}
