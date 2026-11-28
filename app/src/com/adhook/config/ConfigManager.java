package com.adhook.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import com.adhook.config.model.GlobalConfig;
import com.adhook.config.security.ConfigInputPolicy;
import com.adhook.config.security.RuntimeStatsEnvelope;
import com.adhook.config.security.RuntimeStatsPolicy;
import com.adhook.core.AdHookLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADHook 本地配置管理器（主 App 内使用，单例）。
 * 负责持久化存储全局 SDK 配置、各宿主应用开关与自定义 UI 规则。
 *
 * 设计要点：
 * 1. save() 仅序列化内存快照，私有 JSON 写盘投递到单线程 Executor，FIFO 保证保存顺序；
 *    正式 API101 remote preferences 在独立发布工作线程提交，发布确认与保存排队分别报告；
 * 2. load() 解析失败时保留不覆盖的损坏备份；主文件缺失但备份仍在时继续隔离，
 *    绝不用默认配置覆盖用户数据；
 * 3. 本地配置仅所有者可读；旧 CE/DE 镜像保留内容但撤销共享权限，不再用于宿主配置读取。
 */
public final class ConfigManager {

    private static final String CONFIG_FILE_NAME = "adhook_config.json";
    private static final String CONFIG_BAK_FILE_NAME = CONFIG_FILE_NAME + ".bak";
    private static final String CONFIG_TMP_FILE_NAME = CONFIG_FILE_NAME + ".tmp";
    private static volatile ConfigManager sInstance;

    private final Context context;
    private final File configFile;
    /** 单线程串行落盘，保证多次 save() 的写入顺序，同时把磁盘 IO 移出主线程。 */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "adhook-config-io");
        thread.setDaemon(true);
        return thread;
    });

    private volatile GlobalConfig globalConfig = new GlobalConfig();
    private long configUpdatedAt = 1L;
    private final Map<String, AppConfig> appConfigs = Collections.synchronizedMap(new LinkedHashMap<>());
    /** 各宿主最新完整进程快照；只在独立统计存储持久化，不写入规则配置。 */
    private final Map<String, RuntimeStatsEnvelope> runtimeStatsMap = new ConcurrentHashMap<>();

    public static final class AppRuntimeStats {
        public final Map<String, String> featureStates = new ConcurrentHashMap<>();
        public final Map<String, Integer> hitCounts = new ConcurrentHashMap<>();
        public volatile long updatedAt = 0L;
        /** 0 表示旧版无进程元数据；否则为当前保存的进程快照数量。 */
        public int processCount = 0;
        /** 生产端或聚合预算截断；为 true 时显示的命中数可能不完整。 */
        public boolean truncated = false;
    }
    /** 配置文件损坏标记：为 true 时说明用户数据已备份但未加载，禁止自动 save 覆盖。 */
    private volatile boolean configBroken = false;
    /** 已排队但尚未落盘的写入数量：load() 看到它大于 0 时跳过重载，避免用旧磁盘内容回滚内存里刚改的配置。 */
    private final AtomicInteger pendingWrites = new AtomicInteger(0);
    private volatile boolean lastWriteSucceeded = true;

    private ConfigManager(Context context) {
        this.context = context.getApplicationContext();
        this.configFile = new File(this.context.getFilesDir(), CONFIG_FILE_NAME);
        // Revoke permissions granted by old releases without deleting or rewriting their data.
        securePrivateStorage(this.context);
        Context deContext = this.context.createDeviceProtectedStorageContext();
        if (deContext != null) securePrivateStorage(deContext);
        boolean loaded = load();
        loadRuntimeStats();
        if (!loaded) {
            AdHookLog.w("[ConfigManager] 配置不可用或存在待恢复备份，原文件/备份保留；"
                    + "不自动创建空配置，请修复文件或恢复完整备份");
        }
        // Read-only initialization must not create a config file or app entries.
        // Legacy mirror contents are preserved privately, but are neither read nor refreshed.
    }

    public static ConfigManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ConfigManager.class) {
                if (sInstance == null) {
                    sInstance = new ConfigManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 从本地文件加载配置。
     *
     * @return true=成功加载或首次配置且无待恢复材料；false=损坏、待恢复或无法确认
     */
    public synchronized boolean load() {
        if (pendingWrites.get() > 0) {
            // save() 是异步落盘：这里若读盘会把内存中较新的配置回滚成旧文件内容，直接跳过重载
            AdHookLog.d("[ConfigManager] skip load: " + pendingWrites.get() + " pending writes");
            return true;
        }
        if (!configFile.exists()) {
            // Renaming a broken file must not turn the next process lifetime into a fresh install.
            if (configBroken || hasBrokenConfigBackup()) {
                configBroken = true;
                return false;
            }
            return true;
        }

        try (FileInputStream fis = new FileInputStream(configFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());

            // 先解析到临时结构，全部成功后再替换内存状态，避免半解析污染
            JSONObject gObj = root.optJSONObject("global_sdk");
            GlobalConfig parsedGlobal = (gObj != null) ? GlobalConfig.fromJson(gObj) : new GlobalConfig();
            Map<String, AppConfig> parsedApps = parseApps(root.optJSONObject("apps"));

            this.globalConfig = parsedGlobal;
            this.configUpdatedAt = Math.max(1L, root.optLong("updated_at", 1L));
            synchronized (appConfigs) {
                appConfigs.clear();
                appConfigs.putAll(parsedApps);
            }
            configBroken = false;
            return true;
        } catch (Throwable t) {
            AdHookLog.e("[ConfigManager] Failed to load config from " + configFile.getAbsolutePath(), t);
            backupBrokenConfig();
            configBroken = true;
            return false;
        }
    }

    /**
     * 把损坏的配置文件改名备份，保留用户数据供人工恢复；已有备份从不删除或覆盖。
     */
    private void backupBrokenConfig() {
        try {
            File bak = new File(configFile.getParentFile(), CONFIG_BAK_FILE_NAME);
            if (bak.exists()) bak = new File(configFile.getParentFile(), CONFIG_BAK_FILE_NAME
                    + "." + System.currentTimeMillis() + "." + java.util.UUID.randomUUID());
            if (!makeOwnerOnly(configFile)) return;
            // No REPLACE_EXISTING: even a colliding backup remains untouched.
            java.nio.file.Files.move(configFile.toPath(), bak.toPath());
            AdHookLog.w("[ConfigManager] 损坏配置已备份: " + bak.getAbsolutePath());
        } catch (Throwable t) {
            AdHookLog.w("[ConfigManager] 备份损坏配置异常: " + t.getMessage());
        }
    }

    private static boolean isBrokenConfigBackup(String name) {
        return CONFIG_BAK_FILE_NAME.equals(name) || name.startsWith(CONFIG_BAK_FILE_NAME + ".");
    }

    private boolean hasBrokenConfigBackup() {
        File directory = configFile.getParentFile();
        if (directory == null || !directory.exists()) return false;
        File[] backups = directory.listFiles((dir, name) -> isBrokenConfigBackup(name));
        // An unreadable directory is UNKNOWN, not proof that recovery material is absent.
        return backups == null || backups.length != 0;
    }

    /**
     * 保存当前内存配置到本地文件（异步落盘，写入排队成功即返回 true）。
     *
     * @return true=已成功排入写盘队列；false=快照构建失败或线程池不可用
     */
    public synchronized boolean save() {
        if (configBroken) return false;
        final String prettyJson;
        final String compactJson;
        final String launchJson;
        try {
            JSONObject root = buildRootJson();
            prettyJson = root.toString(2);
            compactJson = root.toString();
            launchJson = HookLaunchSnapshot.fromConfig(root);
        } catch (Throwable t) {
            AdHookLog.e("[ConfigManager] Failed to build config json", t);
            return false;
        }

        pendingWrites.incrementAndGet();
        try {
            ioExecutor.execute(() -> {
                try {
                    lastWriteSucceeded = writeConfigFile(prettyJson, compactJson, launchJson);
                } finally {
                    pendingWrites.decrementAndGet();
                }
            });
            // Queue acceptance is desired policy, not a remote acknowledgement.
            notifyConfigPolicyChanged();
            return true;
        } catch (Throwable t) {
            pendingWrites.decrementAndGet();
            AdHookLog.e("[ConfigManager] Failed to enqueue config save", t);
            return false;
        }
    }

    /** Each notification is independent and cannot reverse queue acceptance or a completed local commit. */
    private void notifyConfigPolicyChanged() {
        notifyPolicy(() -> RemoteConfigPublisher.expectVersion(configUpdatedAt));
        notifyPolicy(() -> ConfigClient.invalidateCache(null));
    }

    private static void notifyPolicy(Runnable notification) {
        try { notification.run(); }
        catch (Throwable failure) { logPolicyFailure("Config policy notification unavailable", failure); }
    }

    private static void logPolicyFailure(String message, Throwable failure) {
        try { AdHookLog.e("[ConfigManager] " + message, failure); }
        catch (Throwable ignored) { /* Diagnostics cannot reverse a completed file commit. */ }
    }

    /**
     * 构建完整配置 JSON 快照（主线程仅做内存序列化）。
     */
    private JSONObject buildRootJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        configUpdatedAt = Math.max(configUpdatedAt + 1L, System.currentTimeMillis());
        root.put("updated_at", configUpdatedAt);
        root.put("global_sdk", globalConfig.toJson());

        JSONObject appsObj = new JSONObject();
        synchronized (appConfigs) {
            for (Map.Entry<String, AppConfig> entry : appConfigs.entrySet()) {
                appsObj.put(entry.getKey(), entry.getValue().toJson());
            }
        }
        root.put("apps", appsObj);
        return root;
    }

    /**
     * 真正的磁盘写入：临时文件 + rename 原子替换，再同步写入 SharedPreferences。
     * 运行在单线程 IO Executor 上，不阻塞 UI。
     */
    private boolean writeConfigFile(String prettyJson, String compactJson, String launchJson) {
        boolean committed = false;
        try {
            File dir = configFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                AdHookLog.w("[ConfigManager] 配置目录创建失败: " + dir.getAbsolutePath());
            }

            if (!securePrivateStorage(context)) return false;
            File tempFile = new File(dir, CONFIG_TMP_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                if (!makeOwnerOnly(tempFile)) return false;
                fos.write(prettyJson.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }

            // Never delete the old file to make replacement work. Unsupported atomic moves fail closed.
            java.nio.file.Files.move(tempFile.toPath(), configFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            committed = true;
            // The owner-only permissions belong to the inode already checked before the move.
            // A post-commit publication error (including linkage failures) must not report an uncommitted file.
            RemoteConfigPublisher.publish(compactJson, launchJson);
            return true;
        } catch (Throwable t) {
            logPolicyFailure(committed ? "Local config saved; remote publication unavailable" : "Failed to save config", t);
            return committed;
        }
    }

    /** Owner-only storage includes old CE/DE mirrors; retiring a channel must revoke its old permissions. */
    private static boolean securePrivateStorage(Context storageContext) {
        File dataDir = storageContext.getDataDir();
        if (dataDir == null || !dataDir.exists()) return true;
        File filesDir = new File(dataDir, "files");
        File preferencesDir = new File(dataDir, "shared_prefs");
        boolean secured = makeOwnerOnly(dataDir);
        secured &= makeOwnerOnly(filesDir);
        secured &= makeOwnerOnly(preferencesDir);
        secured &= makeOwnerOnly(new File(filesDir, CONFIG_FILE_NAME));
        secured &= makeOwnerOnly(new File(filesDir, CONFIG_TMP_FILE_NAME));
        secured &= makeOwnerOnly(new File(filesDir, CONFIG_BAK_FILE_NAME));
        if (filesDir.exists()) {
            File[] backups = filesDir.listFiles((dir, name) -> isBrokenConfigBackup(name));
            if (backups == null) secured = false;
            else for (File backup : backups) secured &= makeOwnerOnly(backup);
        }
        secured &= makeOwnerOnly(new File(preferencesDir, "adhook_config.xml"));
        return secured;
    }

    private static boolean makeOwnerOnly(File path) {
        if (path == null || !path.exists()) return true;
        try {
            android.system.Os.chmod(path.getAbsolutePath(), path.isDirectory() ? 0700 : 0600);
            return true;
        } catch (android.system.ErrnoException | RuntimeException failure) {
            AdHookLog.e("[ConfigManager] Cannot secure private configuration storage", failure);
            return false;
        }
    }

    /** Publish only an existing persisted config; startup never creates/migrates app switches. */
    public synchronized void publishExistingRemoteConfig() {
        ioExecutor.execute(() -> {
            if (configBroken || !configFile.isFile()) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(configFile), StandardCharsets.UTF_8))) {
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                JSONObject root = new JSONObject(json.toString());
                RemoteConfigPublisher.publish(root.toString(), HookLaunchSnapshot.fromConfig(root));
            } catch (Exception error) {
                AdHookLog.w("[ConfigManager] Existing remote publication skipped: " + error.getMessage());
            }
        });
    }

    /** Pure memory app-side remote commit status; confirmed does not mean hosts consumed the version. */
    public RemotePublicationState getRemotePublicationState() {
        return RemoteConfigPublisher.getState();
    }

    /**
     * IO-caller-only local commit. Drain older queued snapshots before writing on the caller thread.
     * A rejected/timed-out barrier starts no new write; there is no newly queued late commit to cancel.
     * Remote delivery/host consumption is deliberately not part of the return value.
     */
    public synchronized boolean saveSynchronously() {
        if (configBroken || !awaitWriteQueueIdle()) return false;
        long previousVersion = configUpdatedAt;
        try {
            JSONObject root = buildRootJson();
            String launch = HookLaunchSnapshot.fromConfig(root);
            boolean saved = writeConfigFile(root.toString(2), root.toString(), launch);
            if (!saved) configUpdatedAt = previousVersion;
            lastWriteSucceeded = saved;
            if (saved) notifyConfigPolicyChanged();
            return saved;
        } catch (Exception failure) {
            configUpdatedAt = previousVersion;
            lastWriteSucceeded = false;
            AdHookLog.e("[ConfigManager] Synchronous config save failed", failure);
            return false;
        }
    }

    /** IO-only queue barrier. A previous completed failure does not prevent a new explicit retry. */
    public boolean awaitWriteQueueIdle() {
        try {
            return ioExecutor.submit(() -> true).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            return false;
        }
    }

    /** Local IO barrier only, never a remote acknowledgement. */
    public boolean awaitPendingWrites() {
        try {
            return ioExecutor.submit(() -> lastWriteSucceeded).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final int MAX_STATS_APPS = 256;

    /** Compatibility entry point for local callers; validation still precedes all mutations. */
    public void updateRuntimeStats(String packageName, JSONObject statsJson) {
        if (statsJson != null) acceptRuntimeStats(packageName, RuntimeStatsPolicy.parse(statsJson.toString()));
    }

    /** Provider has already verified UID and rate; legacy reports still replace the full app. */
    public synchronized boolean acceptRuntimeStats(String packageName, RuntimeStatsPolicy.Snapshot snapshot) {
        return acceptRuntimeStats(packageName, snapshot, false);
    }

    public synchronized boolean acceptRuntimeStats(String packageName, RuntimeStatsPolicy.Snapshot snapshot,
                                                   boolean truncated) {
        return storeRuntimeStats(packageName,
                RuntimeStatsEnvelope.legacy(packageName, snapshot, System.currentTimeMillis(), truncated));
    }

    /** Each named process supplies its latest full snapshot, not a cumulative delta or patch. */
    public synchronized boolean acceptRuntimeStats(String packageName, RuntimeStatsPolicy.Snapshot snapshot,
                                                   String processName, String sessionId) {
        return acceptRuntimeStats(packageName, snapshot, processName, sessionId, false);
    }

    public synchronized boolean acceptRuntimeStats(String packageName, RuntimeStatsPolicy.Snapshot snapshot,
                                                   String processName, String sessionId, boolean truncated) {
        if (!ConfigInputPolicy.isPackageName(packageName) || snapshot == null) return false;
        return storeRuntimeStats(packageName, RuntimeStatsEnvelope.replace(packageName, runtimeStatsMap.get(packageName),
                snapshot, processName, sessionId, System.currentTimeMillis(), truncated));
    }

    /** Called under the manager monitor; enqueue failure rolls back both data and diagnostics. */
    private boolean storeRuntimeStats(String packageName, RuntimeStatsEnvelope envelope) {
        if (!ConfigInputPolicy.isPackageName(packageName) || envelope == null
                || (!runtimeStatsMap.containsKey(packageName) && runtimeStatsMap.size() >= MAX_STATS_APPS)) return false;
        final String json;
        try {
            json = envelope.toJson().toString();
        } catch (JSONException ignored) {
            return false;
        }
        RuntimeStatsEnvelope previous = runtimeStatsMap.put(packageName, envelope);
        try {
            ioExecutor.execute(() -> {
                try {
                    if (!context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE)
                            .edit().putString(packageName, json).commit()) {
                        AdHookLog.w("[ConfigManager] Failed to persist runtime stats");
                    }
                } catch (RuntimeException ignored) {
                    AdHookLog.w("[ConfigManager] Failed to persist runtime stats");
                }
            });
            return true;
        } catch (RuntimeException ignored) {
            if (previous == null) runtimeStatsMap.remove(packageName);
            else runtimeStatsMap.put(packageName, previous);
            return false;
        }
    }

    private static AppRuntimeStats statsFromEnvelope(RuntimeStatsEnvelope envelope) {
        AppRuntimeStats stats = new AppRuntimeStats();
        stats.featureStates.putAll(envelope.aggregate.states);
        stats.hitCounts.putAll(envelope.aggregate.hits);
        stats.updatedAt = envelope.updatedAt;
        stats.processCount = envelope.processes.size();
        stats.truncated = envelope.truncated;
        return stats;
    }

    private void loadRuntimeStats() {
        try {
            Map<String, ?> all = context.getSharedPreferences("adhook_runtime_stats", Context.MODE_PRIVATE).getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (runtimeStatsMap.size() >= MAX_STATS_APPS) break;
                if (!ConfigInputPolicy.isPackageName(entry.getKey()) || !(entry.getValue() instanceof String)) continue;
                RuntimeStatsEnvelope envelope = RuntimeStatsEnvelope.parse(entry.getKey(), (String) entry.getValue());
                if (envelope != null) runtimeStatsMap.put(entry.getKey(), envelope);
            }
        } catch (RuntimeException ignored) {
            // Invalid legacy or versioned data is never copied into the live store or rewritten.
        }
    }

    /** Detached UI snapshot, never expose per-process storage or live concurrent maps. */
    public synchronized AppRuntimeStats getRuntimeStats(String packageName) {
        RuntimeStatsEnvelope source = packageName == null ? null : runtimeStatsMap.get(packageName);
        return source == null ? null : statsFromEnvelope(source);
    }

    /** Legacy live object; legacy mutation must hold this manager's monitor. */
    public synchronized GlobalConfig getGlobalConfig() {
        return globalConfig;
    }

    /** Authorized Provider caller gets only its package and global policy under one manager lock. */
    public synchronized JSONObject getHookRuntimeSnapshot(String packageName) throws JSONException {
        if (!ConfigInputPolicy.isPackageName(packageName) || configBroken) return null;
        JSONObject apps = new JSONObject();
        AppConfig app = findAppConfig(packageName);
        if (app != null) apps.put(packageName, app.toJson());
        return new JSONObject().put("version", 1).put("updated_at", configUpdatedAt)
                .put("global_sdk", globalConfig.toJson()).put("apps", apps);
    }

    public synchronized GlobalConfig getGlobalConfigSnapshot() {
        return GlobalConfig.fromJson(globalConfig.toJson());
    }

    public synchronized boolean isExcludeFromRecents() {
        return !configBroken && globalConfig.isExcludeFromRecents();
    }

    /** A detached default is returned for a missing package, with no insertion or authorization. */
    public synchronized AppConfig getAppConfigSnapshot(String packageName) {
        if (!ConfigInputPolicy.isPackageName(packageName)) return null;
        AppConfig existing = findAppConfig(packageName);
        return existing == null ? new AppConfig(packageName) : existing.copy();
    }

    public synchronized Map<String, AppConfig> snapshotAppConfigs() {
        Map<String, AppConfig> copy = new LinkedHashMap<>();
        synchronized (appConfigs) {
            for (Map.Entry<String, AppConfig> entry : appConfigs.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().copy());
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    // Lock order: SubscriptionManager -> ConfigManager -> appConfigs. Never call SubscriptionManager here.
    private interface AppEdit { boolean apply(AppConfig config); }

    /** IO-caller-only: each edit returns its own local commit result, not queue acceptance. */
    private synchronized boolean editApp(String packageName, boolean create, AppEdit edit) {
        if (!ConfigInputPolicy.isPackageName(packageName) || configBroken) return false;
        if (!awaitWriteQueueIdle()) return false;
        AppConfig previous = findAppConfig(packageName);
        if (previous == null && (!create || appConfigs.size() >= 2000)) return false;
        AppConfig next = previous == null ? new AppConfig(packageName) : previous.copy();
        if (!edit.apply(next)) return false;
        synchronized (appConfigs) { appConfigs.put(packageName, next); }
        if (saveSynchronously()) return true;
        synchronized (appConfigs) {
            if (previous == null) appConfigs.remove(packageName);
            else appConfigs.put(packageName, previous);
        }
        return false;
    }

    public boolean setFeatureEnabled(String pkg, String feature, boolean enabled) {
        if (!ConfigInputPolicy.isFeatureId(feature)) return false;
        return editApp(pkg, true, config -> {
            if (!config.getNativeFeatures().containsKey(feature) && config.getNativeFeatures().size() >= 256) return false;
            config.setFeatureEnabled(feature, enabled);
            return true;
        });
    }

    public boolean setInspectorEnabled(String pkg, boolean enabled) {
        return editApp(pkg, true, config -> { config.setInspectorEnabled(enabled); return true; });
    }

    public boolean upsertCustomRule(String pkg, CustomRule rule, int maxRules) {
        if (rule == null || !ConfigInputPolicy.isRuleId(rule.getId()) || maxRules <= 0) return false;
        return editApp(pkg, true, config -> {
            boolean replacing = false;
            for (CustomRule existing : config.getCustomRules()) {
                if (existing != null && rule.getId().equals(existing.getId())) { replacing = true; break; }
            }
            if (!replacing && config.getCustomRules().size() >= maxRules) return false;
            config.addCustomRule(rule.copy());
            return true;
        });
    }

    public boolean setRuleEnabled(String pkg, String ruleId, boolean enabled) {
        if (!ConfigInputPolicy.isRuleId(ruleId)) return false;
        return editApp(pkg, false, config -> {
            for (CustomRule rule : config.getCustomRules()) {
                if (rule != null && ruleId.equals(rule.getId())) { rule.setEnabled(enabled); return true; }
            }
            return false;
        });
    }

    public boolean removeCustomRule(String pkg, String ruleId) {
        return ConfigInputPolicy.isRuleId(ruleId) && editApp(pkg, false, config -> config.removeCustomRule(ruleId));
    }

    /** IO-only, self-UID Provider or console writer; success means this local commit completed. */
    public synchronized boolean setSdkEnabled(String sdk, boolean enabled) {
        if (sdk == null || configBroken) return false;
        GlobalConfig previous = globalConfig;
        GlobalConfig next = getGlobalConfigSnapshot();
        switch (sdk.toLowerCase(java.util.Locale.ROOT)) {
            case "exclude_from_recents", "excludefromrecents" -> next.setExcludeFromRecents(enabled);
            case "rule_toast", "ruletoast" -> next.setRuleToastEnabled(enabled);
            default -> { return false; }
        }
        if (!awaitWriteQueueIdle()) return false;
        globalConfig = next;
        if (saveSynchronously()) return true;
        globalConfig = previous;
        return false;
    }

    /**
     * 读取已存在的应用配置，不创建新条目（供 Provider 读路径与外部调用使用，避免内存膨胀）。
     */
    public synchronized AppConfig findAppConfig(String packageName) {
        if (packageName == null) {
            return null;
        }
        synchronized (appConfigs) {
            return appConfigs.get(packageName);
        }
    }

    /** Legacy creating getter. Prefer getAppConfigSnapshot for all presentation/read paths. */
    public synchronized AppConfig getAppConfig(String packageName) {
        if (packageName == null) return null;
        synchronized (appConfigs) {
            AppConfig config = appConfigs.get(packageName);
            if (config == null) {
                config = new AppConfig(packageName);
                appConfigs.put(packageName, config);
            }
            return config;
        }
    }

    public Map<String, AppConfig> getAllAppConfigs() {
        return appConfigs;
    }

    public boolean isConfigBroken() {
        return configBroken;
    }

    /**
     * 获取当前内存配置的完整 JSON 快照。
     */
    public synchronized JSONObject snapshotJson() throws JSONException {
        return buildRootJson();
    }

    /**
     * 从完整快照恢复配置（完全覆盖内存，并落盘刷新跨进程状态）。
     *
     * @param root 包含 global_sdk 与 apps 的 JSONObject
     * @return true 表示恢复排盘成功
     */
    public synchronized boolean restoreSnapshot(JSONObject root) {
        if (!com.adhook.config.security.ConfigSnapshotPolicy.isValid(root)) return false;
        GlobalConfig previousGlobal = globalConfig;
        Map<String, AppConfig> previousApps = new LinkedHashMap<>(appConfigs);
        boolean previousBroken = configBroken;
        try {
            GlobalConfig parsedGlobal = GlobalConfig.fromJson(root.getJSONObject("global_sdk"));
            Map<String, AppConfig> parsedApps = parseApps(root.getJSONObject("apps"));
            globalConfig = parsedGlobal;
            synchronized (appConfigs) {
                appConfigs.clear();
                appConfigs.putAll(parsedApps);
            }
            configBroken = false;
            if (save()) return true;
        } catch (Throwable t) {
            AdHookLog.e("[ConfigManager] Failed to restore snapshot", t);
        }
        globalConfig = previousGlobal;
        synchronized (appConfigs) {
            appConfigs.clear();
            appConfigs.putAll(previousApps);
        }
        configBroken = previousBroken;
        return false;
    }

    /**
     * 导出全量配置为格式化 JSON 字符串。
     */
    public synchronized String exportAllJson() {
        try {
            JSONObject root = buildRootJson();
            root.put("type", "adhook_full_backup");
            return root.toString(2);
        } catch (Throwable t) {
            AdHookLog.e("[ConfigManager] Export failed", t);
            return "";
        }
    }

    /**
     * 导出单个应用的规则配置为 JSON 字符串。
     */
    public synchronized String exportAppJson(String packageName) {
        try {
            AppConfig config = findAppConfig(packageName);
            if (config == null) {
                config = new AppConfig(packageName);
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("type", "adhook_app_rules");
            root.put("package", packageName);
            root.put("updated_at", System.currentTimeMillis());
            root.put("data", config.toJson());
            return root.toString(2);
        } catch (Throwable t) {
            AdHookLog.e("[ConfigManager] Export app failed: " + packageName, t);
            return "";
        }
    }

    /**
     * 从 JSON 文本导入规则，兼容三种格式：
     * 1. Subscription 订阅格式（顶层 apps 是 JSONArray，元素含 id/name/rules）——本地规则「导出→导入」闭环；
     * 2. 单应用规则（type=adhook_app_rules 或含 package 字段）；
     * 3. 全量备份（apps 是 JSONObject）。
     *
     * @param jsonStr JSON 字符串
     * 必须由 IO 调用方执行；完整预检和本地原子提交后才替换内存，损坏状态拒绝普通合并。
     * @return 本地保存成功提示；不表示远程发布、宿主消费或效果已确认
     */
    public synchronized String importJson(String jsonStr) throws JSONException {
        if (configBroken) throw new IllegalStateException("当前配置损坏，未导入；请先使用完整备份恢复或修复原文件");
        JSONObject incoming = com.adhook.config.security.ConfigImportPolicy.normalize(jsonStr);
        JSONObject incomingApps = incoming.getJSONObject("apps");
        Map<String, AppConfig> stagedApps = new LinkedHashMap<>(snapshotAppConfigs());
        GlobalConfig stagedGlobal = getGlobalConfigSnapshot();
        // Only the full format carries global settings; an absent global block preserves current values.
        JSONObject original = new JSONObject(jsonStr);
        if (original.opt("apps") instanceof JSONObject && original.has("global_sdk")) {
            stagedGlobal = GlobalConfig.fromJson(incoming.getJSONObject("global_sdk"));
        }
        int ruleCount = 0;
        Iterator<String> keys = incomingApps.keys();
        while (keys.hasNext()) {
            String pkg = keys.next();
            AppConfig imported = AppConfig.fromJson(incomingApps.getJSONObject(pkg));
            AppConfig current = stagedApps.get(pkg);
            if (current == null) current = new AppConfig(pkg);
            current.getNativeFeatures().putAll(imported.getNativeFeatures());
            for (CustomRule rule : imported.getCustomRules()) {
                current.addCustomRule(rule.copy());
                ruleCount++;
            }
            stagedApps.put(pkg, current);
        }
        JSONObject stagedRoot = new JSONObject().put("version", 1)
                .put("updated_at", Math.max(configUpdatedAt + 1L, System.currentTimeMillis()))
                .put("global_sdk", stagedGlobal.toJson());
        JSONObject appsJson = new JSONObject();
        for (Map.Entry<String, AppConfig> entry : stagedApps.entrySet()) {
            appsJson.put(entry.getKey(), entry.getValue().toJson());
        }
        stagedRoot.put("apps", appsJson);
        if (!com.adhook.config.security.ConfigSnapshotPolicy.isValid(stagedRoot)) {
            throw new IllegalArgumentException("合并后配置超限或无效，原配置未修改");
        }
        // Called by ConsoleRepository on IO. Holding this monitor excludes new config save submissions.
        if (!awaitWriteQueueIdle()) throw new IllegalStateException("配置写入队列不可用或未完成，未开始导入");
        String launch = HookLaunchSnapshot.fromConfig(stagedRoot);
        if (!writeConfigFile(stagedRoot.toString(2), stagedRoot.toString(), launch)) {
            throw new IllegalStateException("导入未保存，原配置保持不变");
        }
        // Commit memory only after the local atomic file replacement has completed successfully.
        globalConfig = stagedGlobal;
        synchronized (appConfigs) {
            appConfigs.clear();
            appConfigs.putAll(stagedApps);
        }
        configUpdatedAt = stagedRoot.getLong("updated_at");
        lastWriteSucceeded = true;
        notifyConfigPolicyChanged();
        return "规则导入已保存：已合并 " + incomingApps.length() + " 个应用、" + ruleCount
                + " 条规则；框架发布与宿主生效请查看独立状态。";
    }

    /**
     * 将 apps JSONObject 反序列化为 Map<String, AppConfig>。
     */
    private static Map<String, AppConfig> parseApps(JSONObject appsObj) {
        Map<String, AppConfig> parsedApps = new LinkedHashMap<>();
        if (appsObj != null) {
            Iterator<String> keys = appsObj.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                if (!isValidPackageName(pkg)) {
                    continue;
                }
                JSONObject aObj = appsObj.optJSONObject(pkg);
                if (aObj != null) {
                    AppConfig appConfig = AppConfig.fromJson(aObj);
                    if (appConfig != null) {
                        appConfig.setPackageName(pkg);
                        parsedApps.put(pkg, appConfig);
                    }
                }
            }
        }
        return parsedApps;
    }

    /**
     * 基础包名校验：只接受常见 Android 包名格式，防止导入脏数据创建无效 AppConfig 条目。
     */
    private static boolean isValidPackageName(String pkg) {
        return ConfigInputPolicy.isPackageName(pkg);
    }
}
