package com.adhook.engine;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.AppConfig;
import com.adhook.config.model.CustomRule;
import com.adhook.core.FeatureGuard;
import com.adhook.core.ViewCollapser;
import com.adhook.core.AdHookLog;


import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 通用声明式 UI 规则匹配与折叠执行引擎。
 * 监听 View 挂载、文字渲染与添加到窗口的动作，一旦命中自定义规则，自动执行 ViewCollapser 折叠。
 *
 * 性能说明（本类挂在 View.onAttachedToWindow / TextView.setText 两个全宿主最热路径上）：
 * 1. 资源 id 匹配前先按 id 高位过滤（只处理 0x7f 开头的本应用资源 id），
 *    避免 View.generateViewId() 产生的非资源 id 触发 Resources.NotFoundException（异常驱动控制流）；
 * 2. (规则目标 + 资源 id) 的匹配结果用 ConcurrentHashMap 缓存，同一 View 重复挂载/文本刷新时不再解析资源名；
 * 3. 热路径日志统一走 AdHookLog.d（仅 verbose 模式输出）；
 * 4. 本地开关控制本地来源，remote 由订阅投影 enabled 控制；无有效折叠规则时 O(1) 短路。
 *    本类只执行折叠，不执行任何点击。
 * 5. 绑定/回收及配置更新按 source/rule/target 释放 lease，不影响 native 的独立永久折叠。
 */
public final class CustomRuleEngine {

    private static volatile boolean sInstalled = false;
    private static volatile boolean sInstallAttempted;
    private static volatile boolean sObserversReady;
    private static final com.adhook.core.ObserverHookRegistry OBSERVERS = new com.adhook.core.ObserverHookRegistry();

    // 严禁作为纯类名折叠的高危宽泛容器类名单，防止整屏/整列表所有条目被全量误杀
    private static final Set<String> DANGEROUS_GENERIC_CLASSES = new HashSet<>(Arrays.asList(
            "android.view.View",
            "android.view.ViewGroup",
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "androidx.recyclerview.widget.RecyclerView",
            "android.widget.ListView",
            "android.widget.ScrollView",
            "androidx.core.widget.NestedScrollView",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "miuix.flexible.view.HyperCellLayout"
    ));

    /** Resource tables are weak keys; identical numeric ids in different tables cannot alias. */
    private static final Map<Resources, Map<String, Boolean>> sResIdMatchCache =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int RES_ID_CACHE_MAX_SIZE = 4096;

    /** 本应用资源 id 的包高位（0x7f），非该高位的 id 不做资源名解析。 */
    private static final int APP_RES_ID_PACKAGE = 0x7f;

    /** 本地开关与当前宿主有效折叠规则存在性缓存，5 秒最多刷新一次。
     *  Hook 只负责折叠，不执行点击。 */
    private static volatile boolean sHasActiveRulesForHost = true;
    private static volatile long sRulesEnabledCheckedAt = 0L;
    private static final long RULES_ENABLED_CHECK_INTERVAL_MS = 5000L;

    private static final RuleOwnership sOwnership = new RuleOwnership();
    private static final Map<View, String> sKnownRoots = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicLong sRulesGeneration = new AtomicLong();
    private static final AtomicBoolean sRefreshPosted = new AtomicBoolean();
    private static volatile long sReadGeneration = -1L;
    private static volatile String sRulesPackageName;
    private static volatile List<CustomRule> sActiveRules = Collections.emptyList();
    private static volatile Set<RuleKey> sActiveRuleKeys = Collections.emptySet();
    private static volatile RuleCandidateIndex sCandidates = new RuleCandidateIndex(Collections.emptyList());
    private static volatile Object sRulesRuntimeIdentity;
    private static final AtomicLong sBudgetWarningGeneration = new AtomicLong(-1L);

    private CustomRuleEngine() {
    }

    /**
     * ConfigClient calls this after invalidating its caches. Never synchronously read configuration
     * here: invalidation can itself originate inside a configuration read, or on a Binder thread.
     */
    public static void notifyRulesChanged() {
        sRulesEnabledCheckedAt = 0L;
        sRulesGeneration.incrementAndGet();
        sResIdMatchCache.clear();
        scheduleKnownViewsRefresh();
    }

    private static void scheduleKnownViewsRefresh() {
        if (!sRefreshPosted.compareAndSet(false, true)) return;
        try {
            Looper main = Looper.getMainLooper();
            if (main == null || !new Handler(main).post(() -> {
                sRefreshPosted.set(false);
                RuleTreeTraversal.cancelAll();
                // Keep a short-lived copy *before* removing old leases. This also reevaluates
                // detached but still live matching nodes whose last rule was just edited/deleted.
                List<RuleOwnership.Source> sources = sOwnership.snapshotSources();
                if (!canApplyObservedRules()) {
                    // Incomplete bind observation permits cleanup only, never matching intermediate host state.
                    for (RuleOwnership.Source source : sources) sOwnership.releaseSource(source.view());
                    return;
                }
                Map<View, String> roots;
                synchronized (sKnownRoots) {
                    roots = new LinkedHashMap<>(sKnownRoots);
                }
                Set<String> disabled = new HashSet<>();
                for (RuleOwnership.Source source : sources) {
                    if (!ConfigClient.isHookEnabledCached(source.packageName())) {
                        if (disabled.add(source.packageName())) sOwnership.retainRules(source.packageName(), Collections.emptySet());
                    } else if (source.view() != null) {
                        // One bounded traversal per actual root, not a second unbounded source loop.
                        roots.putIfAbsent(source.view().getRootView(), source.packageName());
                    }
                }
                for (Map.Entry<View, String> entry : roots.entrySet()) {
                    try {
                        applyToSubtree(entry.getValue(), entry.getKey());
                    } catch (Throwable failure) {
                        AdHookLog.w("[CustomRuleEngine] Root refresh failed: " + failure.getMessage());
                    }
                }
            })) {
                sRefreshPosted.set(false);
            }
        } catch (Throwable failure) {
            sRefreshPosted.set(false);
            AdHookLog.w("[CustomRuleEngine] Could not schedule rule refresh: " + failure.getMessage());
        }
    }

    /**
     * 挂载动态规则拦截引擎。
     */
    public static synchronized void install(final ClassLoader classLoader, final String packageName) {
        if (classLoader == null || packageName == null) return;
        sInstallAttempted = true;
        sObserversReady = false;
        List<Throwable> failures = new ArrayList<>();
        // Preferences gate actions, never observer discovery. Every required member retries independently.
        if (!sInstalled) {
            registerObserver(failures, View.class, "onAttachedToWindow", new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    if (param.thisObject instanceof View view) {
                        sKnownRoots.put(view.getRootView(), packageName);
                        matchFromObserver(packageName, view);
                    }
                }
            });
            HookCallback textHook = new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    if (param.thisObject instanceof TextView tv) matchFromObserver(packageName, tv);
                }
            };
            registerObserver(failures, TextView.class, "setText", textHook, CharSequence.class);
            registerObserver(failures, TextView.class, "setText", textHook, CharSequence.class, TextView.BufferType.class);
            registerObserver(failures, View.class, "setContentDescription", new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    if (param.thisObject instanceof View view) matchFromObserver(packageName, view);
                }
            }, CharSequence.class);
            registerObserver(failures, Activity.class, "onResume", new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    if (param.thisObject instanceof Activity act && act.getWindow() != null) {
                        RuleTreeTraversal.resumed(act);
                        View decor = act.getWindow().peekDecorView();
                        if (decor != null) {
                            sKnownRoots.put(decor, packageName);
                            WeakReference<Activity> activityReference = new WeakReference<>(act);
                            WeakReference<View> decorReference = new WeakReference<>(decor);
                            decor.post(() -> {
                                Activity current = activityReference.get();
                                View currentDecor = decorReference.get();
                                if (current == null || currentDecor == null || current.isFinishing() || current.isDestroyed()
                                        || current.getWindow() == null || current.getWindow().peekDecorView() != currentDecor) return;
                                if (canApplyObservedRules()) applyToActivity(packageName, current);
                                else sOwnership.releaseTree(currentDecor);
                            });
                        }
                    }
                }
            });
            HookCallback inactive = new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity activity) RuleTreeTraversal.cancelForActivity(activity);
                }
            };
            registerObserver(failures, Activity.class, "onPause", inactive);
            registerObserver(failures, Activity.class, "onDestroy", inactive);
            registerObserver(failures, View.class, "setId", new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    matchFromObserver(packageName, (View) param.thisObject);
                }
            }, int.class);
        }
        // Even after framework observers are complete, a newly unpacked loader may expose a library.
        try { ReusableViewHooks.install(classLoader, packageName); }
        catch (Throwable failure) { failures.add(failure); }
        if (!failures.isEmpty()) {
            IllegalStateException incomplete = new IllegalStateException("Custom rule observers partially unavailable");
            for (Throwable failure : failures) incomplete.addSuppressed(failure);
            throw incomplete;
        }
        sInstalled = true;
        sObserversReady = true;
        scheduleKnownViewsRefresh();
        AdHookLog.i("[CustomRuleEngine] Installed dynamic rule engine for " + packageName);
    }

    /** Runtime callback readiness only; direct public policy APIs retain their standalone contract. */
    static boolean canApplyObservedRules() { return !sInstallAttempted || sObserversReady; }

    private static void matchFromObserver(String packageName, View view) {
        if (!canApplyObservedRules()) {
            if (view != null) sOwnership.releaseSource(view);
            return;
        }
        matchAndApply(packageName, view);
    }

    private static void registerObserver(List<Throwable> failures, Class<?> type, String name,
                                         HookCallback callback, Object... parameters) {
        try { OBSERVERS.hook(HookHelpers.findMethodExact(type, name, parameters), callback); }
        catch (Throwable failure) { failures.add(failure); }
    }

    static boolean hasActiveRulesForHost(String packageName) {
        if (packageName == null || !ConfigClient.isHookEnabledCached(packageName)) return false;
        ensureRulesSwitchesRefreshed(packageName);
        return ruleStateIsCurrent(packageName) && sHasActiveRulesForHost;
    }

    /**
     * 检查并应用规则到目标 View。只执行 ACTION_COLLAPSE 折叠；
     * 点击类动作不属于本引擎职责，本类只处理折叠。
     */
    public static boolean matchAndApply(String packageName, View view) {
        if (view == null || packageName == null) return false;
        if (isOffMainThread()) {
            RuleTreeTraversal.start(packageName, view, sRulesGeneration.get());
            return false;
        }
        if (!ConfigClient.isHookEnabledCached(packageName)) {
            sOwnership.releaseSource(view);
            return false;
        }
        ensureRulesSwitchesRefreshed(packageName);

        // Never act on an intermediate adapter binding state.
        if (ReusableViewHooks.isBinding()) return false;
        if (!sHasActiveRulesForHost) {
            sOwnership.releaseSource(view);
            return false;
        }

        if (!ruleStateIsCurrent(packageName)) return false;
        NodeAttributes attributes;
        List<CustomRule> candidates;
        try {
            attributes = new NodeAttributes(view, sCandidates);
            candidates = sCandidates.candidates(view.getClass().getName(), attributes.resourceName,
                    attributes.resourceEntry, attributes.text, attributes.description);
        } catch (RuntimeException failure) {
            sOwnership.releaseSource(view);
            return false;
        }
        if (candidates == null) { abandonNode(packageName, view); return false; }
        Map<RuleKey, View> desired = null;
        boolean matched = false;
        long started = System.nanoTime();
        int evaluated = 0;
        for (CustomRule rule : candidates) {
            if (++evaluated % 16 == 0 && (System.nanoTime() - started > 4_000_000L || !ruleStateIsCurrent(packageName))) {
                abandonNode(packageName, view); return false;
            }
            if (!isMatched(view, rule, attributes)) continue;
            View target = resolveRuleTarget(view, rule);
            if (target != null) {
                if (desired == null) desired = new LinkedHashMap<>();
                desired.put(RuleKey.of(packageName, rule), target);
            }
            matched = true;
        }
        try {
            if (!ruleStateIsCurrent(packageName) || !attributes.current(view)) { sOwnership.releaseSource(view); return false; }
        } catch (RuntimeException changed) { sOwnership.releaseSource(view); return false; }
        Set<RuleKey> acquired = sOwnership.reconcile(view, desired != null ? desired : Collections.emptyMap());
        for (RuleKey key : acquired) {
            FeatureGuard.hit(key.id() != null ? key.id() : "custom-rule-collapse");
            AdHookLog.d("[CustomRuleEngine] Collapsed view for rule: " + key.id()
                    + " (" + key.target() + ", depth=" + key.depth() + ")");
        }
        return matched;
    }

    /** 针对当前 Activity 的整个 View 树批量应用规则。 */
    public static void applyToActivity(String packageName, Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View root = activity.getWindow().peekDecorView();
        if (root == null) return;
        sKnownRoots.put(root, packageName);
        applyToSubtree(packageName, root);
    }

    /** Includes the root and all children, even when a sibling/ancestor already matched. */
    static void applyToSubtree(String packageName, View root) {
        if (root == null || packageName == null) return;
        if (isOffMainThread()) {
            RuleTreeTraversal.start(packageName, root, sRulesGeneration.get());
            return;
        }
        if (!ConfigClient.isHookEnabledCached(packageName)) {
            sOwnership.retainRules(packageName, Collections.emptySet());
            return;
        }
        ensureRulesSwitchesRefreshed(packageName);
        if (!sHasActiveRulesForHost) {
            // Release only this package's rule leases; observers/known roots remain ready for hot enable.
            sOwnership.retainRules(packageName, Collections.emptySet());
            return;
        }
        RuleTreeTraversal.start(packageName, root, sRulesGeneration.get());
    }

    static void releaseForReuse(View item) {
        RuleTreeTraversal.changed(item);
        sOwnership.releaseTree(item);
    }

    private static boolean isOffMainThread() {
        return Looper.getMainLooper() != null && Looper.myLooper() != Looper.getMainLooper();
    }

    static boolean isTraversalCurrent(String pkg, long generation) {
        return canApplyObservedRules() && generation == sRulesGeneration.get() && ConfigClient.isHookEnabledCached(pkg);
    }

    private static boolean ruleStateIsCurrent(String pkg) {
        return pkg.equals(sRulesPackageName) && sReadGeneration == sRulesGeneration.get()
                && sRulesRuntimeIdentity == ConfigClient.hookRuntimeIdentityCached();
    }

    private static void abandonNode(String pkg, View view) {
        sOwnership.releaseSource(view);
        reportBudget(pkg, "Node");
    }

    static void reportBudget(String pkg, String kind) {
        long generation = sRulesGeneration.get();
        if (sBudgetWarningGeneration.getAndSet(generation) != generation) {
            AdHookLog.w("[CustomRuleEngine] " + kind + " budget exhausted; remaining host UI preserved for " + pkg);
        }
    }

    private static void ensureRulesSwitchesRefreshed(String packageName) {
        long now = System.currentTimeMillis();
        long generation = sRulesGeneration.get();
        boolean sameHost = packageName.equals(sRulesPackageName);
        if (sameHost && generation == sReadGeneration && sRulesRuntimeIdentity == ConfigClient.hookRuntimeIdentityCached()
                && (now - sRulesEnabledCheckedAt) < RULES_ENABLED_CHECK_INTERVAL_MS) return;
        refreshRulesSwitchesIfNeeded(packageName);
    }

    /** Reconcile deletions, same-id edits and disabled sources before any no-rules short circuit. */
    private static synchronized void refreshRulesSwitchesIfNeeded(String packageName) {
        long now = System.currentTimeMillis();
        long generation = sRulesGeneration.get();
        boolean sameHost = packageName.equals(sRulesPackageName);
        if (sameHost && generation == sReadGeneration && sRulesRuntimeIdentity == ConfigClient.hookRuntimeIdentityCached()
                && (now - sRulesEnabledCheckedAt) < RULES_ENABLED_CHECK_INTERVAL_MS) return;
        sRulesEnabledCheckedAt = now;
        sRulesPackageName = packageName;
        try {
            Object identity = ConfigClient.hookRuntimeIdentityCached();
            com.adhook.config.model.GlobalConfig global = ConfigClient.getGlobalConfigCached();
            boolean local = global != null && global.isLocalRulesEnabled();
            AppConfig config = ConfigClient.getAppConfigCached(packageName);
            List<CustomRule> rules = config != null ? config.getCustomRules() : null;
            List<CustomRule> active = new ArrayList<>();
            Set<RuleKey> keys = new LinkedHashSet<>();
            if (rules != null) {
                for (CustomRule rule : new ArrayList<>(rules)) {
                    // Hook 引擎只执行折叠。
                    if (!com.adhook.config.security.RuleActivation.isHookActionable(rule, local)) continue;
                    CustomRule copy = rule.copy();
                    active.add(copy);
                    keys.add(RuleKey.of(packageName, copy));
                }
            }
            if (identity != ConfigClient.hookRuntimeIdentityCached() || generation != sRulesGeneration.get()) return;
            boolean changed = sameHost && !keys.equals(sActiveRuleKeys);
            sActiveRules = Collections.unmodifiableList(active);
            sCandidates = new RuleCandidateIndex(sActiveRules);
            sRulesRuntimeIdentity = identity;
            sActiveRuleKeys = Collections.unmodifiableSet(keys);
            sHasActiveRulesForHost = !active.isEmpty();
            sReadGeneration = generation;
            sOwnership.retainRules(packageName, keys);
            if (changed) scheduleKnownViewsRefresh();
        } catch (Throwable t) {
            AdHookLog.w("[CustomRuleEngine] read rules switch failed: " + t.getMessage());
        }
    }

    private static boolean isMatched(View view, CustomRule rule, NodeAttributes attributes) {
        String type = rule.getType();
        String target = rule.getTargetValue();
        if (type == null || target == null || target.isEmpty()) {
            return false;
        }

        // 若规则声明了 Activity 过滤条件，先核验当前 View 所属的 Activity 类名
        String actFilter = rule.getActivityFilter();
        if (actFilter != null && !actFilter.isEmpty()) {
            if (!matchesActivityFilter(view, actFilter, rule.isExactActivityFilter())) {
                return false;
            }
        }

        switch (type) {
            case CustomRule.TYPE_RES_ID -> {
                int id = view.getId();
                // 0x7f 开头才是本应用资源 id；generateViewId() 生成的是 0x00xxxxxx 等，直接排除，
                // 避免 getResourceEntryName 抛 NotFoundException 形成异常驱动的热路径开销
                if (id == View.NO_ID || id <= 0 || (id >>> 24) != APP_RES_ID_PACKAGE) {
                    return false;
                }

                Resources res = view.getResources();
                if (res == null) return false;
                Map<String, Boolean> tableCache = sResIdMatchCache.computeIfAbsent(res, ignored -> new ConcurrentHashMap<>());
                String cacheKey = rule.isExactResourceId() + ":" + target + "#" + id;
                Boolean cached = tableCache.get(cacheKey);
                if (cached != null) return cached;

                boolean matched = false;
                try {
                    if (rule.isExactResourceId()) {
                        matched = target.equals(res.getResourceName(id));
                    } else {
                        String entryName = res.getResourceEntryName(id);
                        matched = target.equals(entryName) || target.endsWith("/" + entryName);
                        if (!matched) matched = target.equals(res.getResourceName(id));
                    }
                } catch (Throwable ignored) {
                    // A missing resource is negatively cached for this resource table only.
                }
                if (tableCache.size() > RES_ID_CACHE_MAX_SIZE) tableCache.clear();
                tableCache.put(cacheKey, matched);
                return matched;
            }
            case CustomRule.TYPE_CLASS_NAME -> {
                // 安全防误杀：若目标属于常见宽泛通用容器类，拒绝作为纯类名折叠，避免全局误杀同类条目
                if (DANGEROUS_GENERIC_CLASSES.contains(target)) {
                    return false;
                }
                String clsName = view.getClass().getName();
                return clsName.equals(target) || clsName.endsWith("." + target);
            }
            case CustomRule.TYPE_TEXT -> {
                String property = rule.getTextProperty();
                if (!"desc".equals(property) && view instanceof TextView
                        && matchesText(attributes.text, target, rule)) return true;
                return !"text".equals(property) && matchesText(attributes.description, target, rule);
            }
        }
        return false;
    }

    private static boolean matchesText(CharSequence raw, String target, CustomRule rule) {
        if (raw == null || raw.length() > RuleCandidateIndex.MAX_TEXT_LENGTH) return false;
        String text = raw.toString();
        if (rule.isTrimText()) text = text.trim();
        int maxLength = rule.getMaxTextLength();
        if (text.isEmpty() || (maxLength > 0 && text.length() > maxLength)) return false;
        return "equals".equals(rule.getTextMatchMode()) ? text.equals(target) : text.contains(target);
    }

    private static boolean matchesActivityFilter(View view, String activityFilter, boolean exact) {
        Activity act = findActivity(view.getContext());
        if (act == null) return false;
        String actClass = act.getClass().getName();
        String simpleName = act.getClass().getSimpleName();
        String[] filters = activityFilter.split(",");
        for (String raw : filters) {
            String f = raw.trim();
            if (f.isEmpty()) continue;
            if (actClass.equals(f)) return true;
            if (!exact && (simpleName.equals(f) || actClass.endsWith(f) || actClass.contains(f))) return true;
        }
        return false;
    }

    static Activity findActivity(Context context) {
        Context cur = context;
        for (int i = 0; i < 10 && cur != null; i++) {
            if (cur instanceof Activity act) {
                return act;
            }
            if (cur instanceof ContextWrapper cw) {
                cur = cw.getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    private static final class NodeAttributes {
        final int id;
        final Resources resources;
        final boolean readText, readDescription;
        final String text, description, resourceName, resourceEntry;
        NodeAttributes(View view, RuleCandidateIndex index) {
            id = view.getId(); resources = view.getResources();
            readText = index.needsText && view instanceof TextView;
            readDescription = index.needsDescription;
            text = readText ? boundedText(((TextView) view).getText()) : null;
            description = readDescription ? boundedText(view.getContentDescription()) : null;
            String name = null, entry = null;
            if (index.needsResources && resources != null && id > 0 && (id >>> 24) == APP_RES_ID_PACKAGE) {
                try { name = resources.getResourceName(id); } catch (Resources.NotFoundException ignored) { }
                try { entry = resources.getResourceEntryName(id); } catch (Resources.NotFoundException ignored) { }
            }
            resourceName = name; resourceEntry = entry;
        }
        boolean current(View view) {
            return id == view.getId() && resources == view.getResources()
                    && (!readText || java.util.Objects.equals(text, boundedText(((TextView) view).getText())))
                    && (!readDescription || java.util.Objects.equals(description, boundedText(view.getContentDescription())));
        }
        private static String boundedText(CharSequence value) {
            if (value != null && value.length() > RuleCandidateIndex.MAX_TEXT_LENGTH) throw new IllegalArgumentException("Node text budget exhausted");
            return value == null ? null : value.toString();
        }
    }

    private static View resolveRuleTarget(View view, CustomRule rule) {
        View targetView = view;
        int depth = rule.getParentDepth();
        if (depth == CustomRule.PARENT_DEPTH_AUTO_ITEM) {
            View item = ReusableViewHooks.knownItemRoot(view);
            targetView = item != null ? item : ViewCollapser.resolveTargetView(view);
        } else if (depth > 0) {
            for (int i = 0; i < depth; i++) {
                if (targetView != null && targetView.getParent() instanceof View parent) targetView = parent;
                else break;
            }
        }
        return targetView;
    }
}
