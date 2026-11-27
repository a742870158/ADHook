package com.adhook.core;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.adhook.config.ConfigClient;
import com.adhook.config.model.GlobalConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * 规则命中时的系统 Toast 提示器（可选功能，默认关闭）。
 * 约束：
 * 1. 按「包名」2 秒冷却节流，避免信息流/高频回调刷屏与系统限流；
 * 2. Hook 侧仅宿主主进程弹出，过滤后台子进程；
 * 3. 主线程不执行 PackageManager IPC，应用名解析在后台线程完成并缓存，主线程仅调用 Toast.show()；
 * 4. 任何异常均内部吞掉，绝不影响宿主或规则执行。
 */
public final class RuleHitToastNotifier {

    public static final long COOLDOWN_MS = 2_000L;
    private static final int MAX_CACHE_ENTRIES = 256;

    private static final Map<String, Long> LAST_SHOWN_AT = new ConcurrentHashMap<>();
    private static final Map<String, String> APP_LABEL_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService LABEL_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "adhook-toast-label");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static volatile LongSupplier sClock = SystemClock::uptimeMillis;
    private static volatile BiConsumer<Context, String> sToastShower = RuleHitToastNotifier::showToastOnMain;
    private static volatile boolean sAsyncLabelResolution = true;

    private RuleHitToastNotifier() {}

    /** Hook 侧命中通知：仅在全局开关开启、主进程且宿主 Context 就绪时触发。 */
    public static void notifyHookHit() {
        try {
            GlobalConfig global = ConfigClient.getGlobalConfigCached();
            if (global == null || !global.isRuleToastEnabled()) return;
            String pkg = FeatureGuard.currentPackage();
            if (!isMainProcess(pkg, FeatureGuard.currentProcessName())) return;
            Context context = ConfigClient.getContext();
            if (context == null) return;
            notifyHit(context, pkg);
        } catch (Throwable ignored) {
            // 提示逻辑绝不干扰宿主业务。
        }
    }

    static void notifyHit(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return;
        if (!tryAcquireSlot(packageName)) return;
        final Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        String cachedLabel = APP_LABEL_CACHE.get(packageName);
        if (cachedLabel != null) {
            dispatchToast(appContext, formatMessage(cachedLabel));
            return;
        }
        if (sAsyncLabelResolution && isMainThread()) {
            try {
                LABEL_EXECUTOR.execute(() -> {
                    String resolved = resolveAndCacheLabel(appContext, packageName);
                    dispatchToast(appContext, formatMessage(resolved));
                });
            } catch (Throwable ignored) {
                dispatchToast(appContext, formatMessage(packageName));
            }
        } else {
            String resolved = resolveAndCacheLabel(appContext, packageName);
            dispatchToast(appContext, formatMessage(resolved));
        }
    }

    static boolean isMainProcess(String packageName, String processName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return processName == null || processName.isEmpty() || packageName.equals(processName);
    }

    static String formatMessage(String appName) {
        return appName + "触发了Hook规则";
    }

    static boolean tryAcquireSlot(String packageName) {
        String key = packageName;
        long now = sClock.getAsLong();
        if (LAST_SHOWN_AT.size() >= MAX_CACHE_ENTRIES && !LAST_SHOWN_AT.containsKey(key)) {
            LAST_SHOWN_AT.clear();
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        LAST_SHOWN_AT.compute(key, (k, last) -> {
            if (last == null || now < last || (now - last) >= COOLDOWN_MS) {
                acquired.set(true);
                return now;
            }
            return last;
        });
        return acquired.get();
    }

    private static String resolveAndCacheLabel(Context context, String packageName) {
        String cached = APP_LABEL_CACHE.get(packageName);
        if (cached != null) return cached;
        String label = packageName;
        try {
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                if (info != null) {
                    CharSequence raw = info.loadLabel(pm);
                    if (raw != null) {
                        String trimmed = raw.toString().trim();
                        if (!trimmed.isEmpty()) label = trimmed;
                    }
                }
            }
        } catch (Throwable ignored) {
            label = packageName;
        }
        if (APP_LABEL_CACHE.size() >= MAX_CACHE_ENTRIES && !APP_LABEL_CACHE.containsKey(packageName)) {
            APP_LABEL_CACHE.clear();
        }
        APP_LABEL_CACHE.put(packageName, label);
        return label;
    }

    private static void dispatchToast(Context context, String message) {
        try {
            sToastShower.accept(context, message);
        } catch (Throwable ignored) {
        }
    }

    private static void showToastOnMain(Context context, String message) {
        Runnable task = () -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        };
        if (isMainThread()) {
            task.run();
        } else {
            Looper main = Looper.getMainLooper();
            if (main != null) {
                new Handler(main).post(task);
            }
        }
    }

    private static boolean isMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && Looper.myLooper() == main;
    }

    static void setTestHooks(LongSupplier clock, BiConsumer<Context, String> toastShower, boolean asyncLabelResolution) {
        sClock = clock != null ? clock : SystemClock::uptimeMillis;
        sToastShower = toastShower != null ? toastShower : RuleHitToastNotifier::showToastOnMain;
        sAsyncLabelResolution = asyncLabelResolution;
    }

    static void resetForTest() {
        LAST_SHOWN_AT.clear();
        APP_LABEL_CACHE.clear();
        sClock = SystemClock::uptimeMillis;
        sToastShower = RuleHitToastNotifier::showToastOnMain;
        sAsyncLabelResolution = true;
    }
}
