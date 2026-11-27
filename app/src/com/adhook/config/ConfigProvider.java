package com.adhook.config;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import com.adhook.config.security.CallerPolicy;
import com.adhook.config.security.ConfigInputPolicy;
import com.adhook.config.security.HostAuthorizationStore;
import com.adhook.config.security.RuntimeStatsMetadata;
import com.adhook.config.security.RuntimeStatsPolicy;
import com.adhook.config.security.StatsRateLimiter;
import com.adhook.core.AdHookLog;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Exported IPC boundary: resolve the Binder caller once, authorize before reading or creating state. */
public class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "com.adhook.config";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_GET_APP_CONFIG = "getAppConfig";
    public static final String METHOD_GET_HOOK_RUNTIME = "getHookRuntime";
    public static final String METHOD_GET_GLOBAL_CONFIG = "getGlobalConfig";
    public static final String METHOD_IS_FEATURE_ENABLED = "isFeatureEnabled";
    public static final String METHOD_IS_INSPECTOR_ENABLED = "isInspectorEnabled";
    public static final String METHOD_ADD_CUSTOM_RULE = "addCustomRule";
    public static final String METHOD_DELETE_CUSTOM_RULE = "deleteCustomRule";
    public static final String METHOD_SET_FEATURE_ENABLED = "setFeatureEnabled";
    public static final String METHOD_SET_INSPECTOR_ENABLED = "setInspectorEnabled";
    public static final String METHOD_SET_GLOBAL_SDK_ENABLED = "setGlobalSdkEnabled";
    public static final String METHOD_REPORT_RUNTIME_STATS = "reportRuntimeStats";

    private static final int MAX_RULE_JSON_LENGTH = 16 * 1024;
    private static final int MAX_RULES_PER_APP = 500;
    private final StatsRateLimiter statsLimiter = new StatsRateLimiter();
    private volatile HostAuthorizationStore authorizationStore;

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        if (context == null || method == null) return result;
        try {
            int uid = Binder.getCallingUid();
            CallerPolicy caller = resolveCaller(uid);
            boolean globalRead = METHOD_GET_GLOBAL_CONFIG.equals(method);
            boolean globalWrite = METHOD_SET_GLOBAL_SDK_ENABLED.equals(method);
            if (globalWrite ? !caller.canWriteGlobal()
                    : globalRead ? !caller.canReadGlobal() : !caller.canAccessPackage(arg)) return result;

            // No ConfigManager initialization (and no disk writes) for unauthorized requests.
            switch (method) {
                case METHOD_GET_HOOK_RUNTIME -> {
                    // Package authorization above remains mandatory; never reveal another host's config.
                    org.json.JSONObject snapshot = manager().getHookRuntimeSnapshot(arg);
                    if (snapshot != null) result.putString("json", snapshot.toString());
                }
                case METHOD_GET_APP_CONFIG -> result.putString("json",
                        manager().getAppConfigSnapshot(arg).toJson().toString());
                case METHOD_GET_GLOBAL_CONFIG -> result.putString("json",
                        manager().getGlobalConfigSnapshot().toJson().toString());
                case METHOD_IS_FEATURE_ENABLED -> {
                    String feature = string(extras, "featureId");
                    if (!ConfigInputPolicy.isFeatureId(feature)) return result;
                    boolean defaultValue = bool(extras, "defaultValue", true);
                    result.putBoolean("enabled", manager().getAppConfigSnapshot(arg)
                            .isFeatureEnabled(feature, defaultValue));
                }
                case METHOD_IS_INSPECTOR_ENABLED -> result.putBoolean("enabled",
                        manager().getAppConfigSnapshot(arg).isInspectorEnabled());
                case METHOD_ADD_CUSTOM_RULE -> {
                    String json = string(extras, "ruleJson");
                    if (json == null || json.length() > MAX_RULE_JSON_LENGTH) return result;
                    CustomRule rule = com.adhook.config.security.RulePayloadPolicy.parse(json);
                    if (rule == null) return result;
                    if (!caller.canWriteGlobal()) {
                        // Host pickup may not impersonate a managed subscription or overwrite its identity.
                        rule.setSource(CustomRule.SOURCE_PICKER);
                        rule.setRuleNamespace("");
                        rule.setSubscriptionId("");
                        rule.setSubscriptionPackage("");
                        rule.setExternalRuleId("");
                        synchronized (manager()) {
                            AppConfig current = manager().getAppConfigSnapshot(arg);
                            for (CustomRule existing : current.getCustomRules()) {
                                if (rule.getId().equals(existing.getId()) && !isPickerRule(existing)) return result;
                            }
                            result.putBoolean("success", manager().upsertCustomRule(arg, rule, MAX_RULES_PER_APP));
                        }
                    } else result.putBoolean("success", manager().upsertCustomRule(arg, rule, MAX_RULES_PER_APP));
                }
                case METHOD_DELETE_CUSTOM_RULE -> {
                    String id = string(extras, "ruleId");
                    if (!ConfigInputPolicy.isRuleId(id)) return result;
                    result.putBoolean("success", manager().removeCustomRule(arg, id));
                }
                case METHOD_SET_FEATURE_ENABLED -> {
                    String feature = string(extras, "featureId");
                    if (!ConfigInputPolicy.isFeatureId(feature)) return result;
                    result.putBoolean("success", manager().setFeatureEnabled(arg, feature, bool(extras, "enabled", true)));
                }
                case METHOD_SET_INSPECTOR_ENABLED -> result.putBoolean("success",
                        manager().setInspectorEnabled(arg, bool(extras, "enabled", false)));
                case METHOD_SET_GLOBAL_SDK_ENABLED -> result.putBoolean("success",
                        manager().setSdkEnabled(string(extras, "sdk"), bool(extras, "enabled", true)));
                case METHOD_REPORT_RUNTIME_STATS -> {
                    boolean hasProcess = extras != null && extras.containsKey("processName");
                    boolean hasSession = extras != null && extras.containsKey("sessionId");
                    if (hasProcess != hasSession) return result;
                    String processName = string(extras, "processName");
                    String sessionId = string(extras, "sessionId");
                    if (hasProcess && !RuntimeStatsMetadata.isValid(arg, processName, sessionId)) return result;
                    if (extras != null && extras.containsKey("statsTruncated")
                            && !(extras.get("statsTruncated") instanceof Boolean)) return result;
                    boolean truncated = bool(extras, "statsTruncated", false);
                    RuntimeStatsPolicy.Snapshot stats = RuntimeStatsPolicy.parse(string(extras, "statsJson"));
                    if (stats == null || !statsLimiter.tryAcquire(uid, SystemClock.elapsedRealtime())) return result;
                    boolean success = hasProcess
                            ? manager().acceptRuntimeStats(arg, stats, processName, sessionId, truncated)
                            : manager().acceptRuntimeStats(arg, stats, truncated);
                    result.putBoolean("success", success);
                    if (success) context.getContentResolver().notifyChange(
                            CONTENT_URI.buildUpon().appendPath("runtime-stats").appendPath(arg).build(), null);
                }
                default -> { /* Unknown calls never create configuration entries. */ }
            }
        } catch (Throwable t) {
            // Binder is an untrusted boundary, including malformed Bundle values and parsers.
            AdHookLog.w("[ConfigProvider] Rejected/failed call: " + t.getClass().getSimpleName());
        }
        return result;
    }

    private ConfigManager manager() { return ConfigManager.getInstance(getContext()); }

    private CallerPolicy resolveCaller(int uid) {
        int selfUid = android.os.Process.myUid();
        if (uid == selfUid) return new CallerPolicy(uid, selfUid, Collections.emptySet(), Collections.emptySet());
        Set<String> owned = new HashSet<>();
        Set<String> authorized = new HashSet<>();
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            owned.addAll(Arrays.asList(packages));
            HostAuthorizationStore store = authorizationStore;
            if (store == null) {
                synchronized (this) {
                    if (authorizationStore == null) authorizationStore = new HostAuthorizationStore(getContext());
                    store = authorizationStore;
                }
            }
            for (String pkg : owned) if (store.isAuthorized(pkg, uid)) authorized.add(pkg);
        }
        return new CallerPolicy(uid, selfUid, owned, authorized);
    }

    @SuppressWarnings("deprecation")
    private static String string(Bundle extras, String key) {
        Object value = extras == null ? null : extras.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string");
        return (String) value;
    }

    @SuppressWarnings("deprecation")
    private static boolean bool(Bundle extras, String key, boolean defaultValue) {
        Object value = extras == null ? null : extras.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("Expected boolean");
        return (Boolean) value;
    }

    private static boolean isPickerRule(CustomRule rule) {
        return (rule.getSource() == null || CustomRule.SOURCE_PICKER.equals(rule.getSource()))
                && (rule.getRuleNamespace() == null || rule.getRuleNamespace().isEmpty());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
