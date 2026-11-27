package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 百度移动广告 (Baidu MobAds SDK) 通用拦截器。
 * 覆盖开屏 (SplashAd)、信息流/原生 (BaiduNativeManager / BaiduNative)、
 * 横幅/自适应 (AdView) 以及插屏与激励视频。
 *
 * 回调识别与 AdMob 同源问题：宿主 Listener 几乎都是匿名内部类，运行时类名不含
 * Listener/Callback，必须由 AdCallbackHelper 按声明参数类型 / 运行时接口 / 字段类型识别；
 * 拦截加载后一定要给宿主补发 onNoAd / onNativeFail / onAdFailed，避免信息流与开屏一直等回调。
 */
public final class BaiduMobadsBlocker {

    /** 弱键弱值缓存；实际回调期间取得局部强引用，并保留参数/字段兜底。 */
    private static final WeakListenerCache SPLASH_LISTENERS = new WeakListenerCache();

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final int ERROR_CODE_NO_AD = -1;

    private BaiduMobadsBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("baidu");

        try {
            Class<?> bdAdConfig = HookHelpers.findClassIfExists("com.baidu.mobads.sdk.api.BDAdConfig", classLoader);
            Class<?> splashAd = HookHelpers.findClassIfExists("com.baidu.mobads.sdk.api.SplashAd", classLoader);
            Class<?> legacySplash = HookHelpers.findClassIfExists("com.baidu.mobads.SplashAd", classLoader);
            Class<?> nativeMgr = HookHelpers.findClassIfExists("com.baidu.mobads.sdk.api.BaiduNativeManager", classLoader);

            if (bdAdConfig == null && splashAd == null && legacySplash == null && nativeMgr == null) {
                return;
            }

            AdHookLog.i("[BaiduMobadsBlocker] Detected Baidu MobAds SDK in classLoader=" + classLoader);
            hookSplashAd(classLoader);
            hookNativeManager(classLoader);
            hookAdViews(classLoader);
            hookVideoAndInterstitial(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[BaiduMobadsBlocker] install failed", t);
        }
    }

    /**
     * 拦截开屏广告：缓存 SplashAd 构造时的 Listener，并在 load/loadAndShow/show 时阻断并回调。
     */
    private static void hookSplashAd(final ClassLoader classLoader) {
        String[] splashClassNames = new String[]{
                "com.baidu.mobads.sdk.api.SplashAd",
                "com.baidu.mobads.SplashAd"
        };

        for (String clsName : splashClassNames) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            // 1. 拦截构造函数，按「声明参数类型 / 运行时接口」记录 listener（不再依赖运行时类名）
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                try {
                    HOOKS.hookObserverMethod(constructor, new HookCallback() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            java.util.List<Object> listeners = AdCallbackHelper.collectParamCallbacks(param);
                            for (Object listener : listeners) {
                                if (AdCallbackHelper.hasFailureMethod(listener)) {
                                    SPLASH_LISTENERS.put(param.thisObject, listener);
                                    AdHookLog.d("[BaiduMobadsBlocker] Cached splash listener: "
                                            + listener.getClass().getName());
                                    break;
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            // 2. 拦截 load / loadAndShow / show，阻断后补发失败回调，避免宿主开屏流程死等
            String[] targetMethods = new String[]{
                    "load",
                    "loadAndShow",
                    "show",
                    "loadAd"
            };

            for (final Method method : clazz.getDeclaredMethods()) {
                if (!contains(targetMethods, method.getName())) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("baidu");
                            if (accounting == null) return;
                            AdHookLog.d("[BaiduMobadsBlocker] Intercepted SplashAd." + method.getName());
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();

                            AdCallbackHelper.notifyCachedCallbacks(param, SPLASH_LISTENERS.get(param.thisObject),
                                    ERROR_CODE_NO_AD, classLoader);
                        }
                    });
                    AdHookLog.i("[BaiduMobadsBlocker] Hooked SplashAd." + method.getName());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 拦截原生与信息流广告管理类 (BaiduNativeManager / BaiduNative)。
     */
    private static void hookNativeManager(final ClassLoader classLoader) {
        String[] mgrClassNames = new String[]{
                "com.baidu.mobads.sdk.api.BaiduNativeManager",
                "com.baidu.mobads.sdk.api.BaiduNative",
                "com.baidu.mobads.BaiduNative"
        };

        for (String clsName : mgrClassNames) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (final Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.startsWith("load") && !name.startsWith("fetch")) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("baidu");
                            if (accounting == null) return;
                            AdHookLog.d("[BaiduMobadsBlocker] Intercepted " + clazz.getSimpleName() + "." + method.getName());
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();
                            // 优先入参识别；无可识别入参时补字段扫描（审查发现的遗漏路径）
                            if (!AdCallbackHelper.notifyParamCallbacks(param, ERROR_CODE_NO_AD, classLoader)) {
                                AdCallbackHelper.notifyFieldCallbacks(param.thisObject, ERROR_CODE_NO_AD, classLoader);
                            }
                        }
                    });
                    AdHookLog.i("[BaiduMobadsBlocker] Hooked " + clazz.getSimpleName() + "." + name);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 拦截横幅/卡片广告视图 (AdView / ExpressAdView)。
     */
    private static void hookAdViews(final ClassLoader classLoader) {
        String[] viewClassNames = new String[]{
                "com.baidu.mobads.sdk.api.AdView",
                "com.baidu.mobads.AdView"
        };

        for (String clsName : viewClassNames) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (final Method method : clazz.getDeclaredMethods()) {
                if (!"callRequest".equals(method.getName()) && !"show".equals(method.getName())) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("baidu");
                            if (accounting == null) return;
                            AdHookLog.d("[BaiduMobadsBlocker] Intercepted " + clazz.getSimpleName() + "." + method.getName());
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();
                            if (!AdCallbackHelper.notifyParamCallbacks(param, ERROR_CODE_NO_AD, classLoader)) {
                                AdCallbackHelper.notifyFieldCallbacks(param.thisObject, ERROR_CODE_NO_AD, classLoader);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 拦截激励视频与插屏广告 (RewardVideoAd / InterstitialAd)。
     */
    private static void hookVideoAndInterstitial(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.baidu.mobads.sdk.api.RewardVideoAd",
                "com.baidu.mobads.sdk.api.FullScreenVideoAd",
                "com.baidu.mobads.sdk.api.InterstitialAd",
                "com.baidu.mobads.InterstitialAd"
        };

        for (String clsName : adClasses) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (final Method method : clazz.getDeclaredMethods()) {
                if (!"load".equals(method.getName()) && !"loadAd".equals(method.getName())
                        && !"show".equals(method.getName())) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("baidu");
                            if (accounting == null) return;
                            AdHookLog.d("[BaiduMobadsBlocker] Intercepted " + clazz.getSimpleName() + "." + method.getName());
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();
                            if (!AdCallbackHelper.notifyParamCallbacks(param, ERROR_CODE_NO_AD, classLoader)) {
                                AdCallbackHelper.notifyFieldCallbacks(param.thisObject, ERROR_CODE_NO_AD, classLoader);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) {
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
