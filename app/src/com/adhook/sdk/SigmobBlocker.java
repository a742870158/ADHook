package com.adhook.sdk;

import android.os.Bundle;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * Sigmob (WindAds / 新风聚合广告) 通用广告拦截器。
 * 覆盖开屏 (WindSplashAD)、激励视频 (WindRewardVideoAd)、插屏 (WindNewInterstitialAd)、
 * 原生 (WindNativeUnifiedAd / WindNativeAd) 与 横幅 (WindBannerAd)。
 *
 * 采用源头阻断 load/show 并通过 AdCallbackHelper 安全补发 onSplashAdLoadFail/onError，
 * 避免宿主因为没有广告回包而长时间假死等待。
 */
public final class SigmobBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final WeakListenerCache SPLASH_LISTENERS = new WeakListenerCache();

    private static final int ERROR_CODE_NO_AD = 600000;

    private SigmobBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("sigmob");

        try {
            Class<?> windAds = HookHelpers.findClassIfExists("com.sigmob.windad.WindAds", classLoader);
            Class<?> windSplash = HookHelpers.findClassIfExists("com.sigmob.windad.Splash.WindSplashAD", classLoader);

            if (windAds == null && windSplash == null) {
                return;
            }

            AdHookLog.i("[SigmobBlocker] Detected Sigmob SDK in classLoader=" + classLoader);
            hookSplashAd(classLoader);
            hookOtherAds(classLoader);
            hookAdActivities(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[SigmobBlocker] install failed", t);
        }
    }

    private static void hookSplashAd(final ClassLoader classLoader) {
        Class<?> clazz = HookHelpers.findClassIfExists("com.sigmob.windad.Splash.WindSplashAD", classLoader);
        if (clazz == null) {
            return;
        }

        // 1. 缓存构造函数中的 Listener
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                HOOKS.hookObserverMethod(constructor, new HookCallback() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        java.util.List<Object> listeners = AdCallbackHelper.collectParamCallbacks(param);
                        for (Object listener : listeners) {
                            if (AdCallbackHelper.hasFailureMethod(listener)) {
                                SPLASH_LISTENERS.put(param.thisObject, listener);
                                break;
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 2. 拦截 isReady -> false
        for (Method method : clazz.getDeclaredMethods()) {
            if ("isReady".equals(method.getName()) && method.getReturnType() == boolean.class) {
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("sigmob");
                            if (accounting == null) return;
                            param.setResult(Boolean.FALSE);
                            accounting.hit();
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        // 3. 拦截 loadAd / loadAndShow / show
        String[] actionMethods = new String[]{"loadAd", "loadAndShow", "show"};
        for (String methodName : actionMethods) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (methodName.equals(method.getName())) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("sigmob");
                                if (accounting == null) return;
                                AdHookLog.d("[SigmobBlocker] Intercepted WindSplashAD." + param.method.getName());
                                AdCallbackHelper.setNullLikeResult(param);
                                accounting.hit();
                                AdCallbackHelper.notifyCachedCallbacks(param, SPLASH_LISTENERS.get(param.thisObject),
                                        ERROR_CODE_NO_AD, classLoader);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void hookOtherAds(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.sigmob.windad.rewardVideo.WindRewardVideoAd",
                "com.sigmob.windad.newInterstitial.WindNewInterstitialAd",
                "com.sigmob.windad.interstitial.WindInterstitialAd",
                "com.sigmob.windad.natives.WindNativeUnifiedAd",
                "com.sigmob.windad.WindNativeAd",
                "com.sigmob.windad.WindBannerAd"
        };

        for (String clsName : adClasses) {
            Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                if ("isReady".equals(name) && method.getReturnType() == boolean.class) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("sigmob");
                                if (accounting == null) return;
                                param.setResult(Boolean.FALSE);
                                accounting.hit();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                } else if ("loadAd".equals(name) || "show".equals(name) || "load".equals(name)) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("sigmob");
                                if (accounting == null) return;
                                AdHookLog.d("[SigmobBlocker] Intercepted " + param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName());
                                AdCallbackHelper.notifyParamCallbacks(param, ERROR_CODE_NO_AD, classLoader);
                                AdCallbackHelper.notifyFieldCallbacks(param.thisObject, ERROR_CODE_NO_AD, classLoader);
                                AdCallbackHelper.setNullLikeResult(param);
                                accounting.hit();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void hookAdActivities(ClassLoader classLoader) {
        String[] activities = new String[]{
                "com.sigmob.sdk.base.common.AdActivity",
                "com.sigmob.sdk.base.common.TransparentAdActivity",
                "com.sigmob.sdk.base.common.LandscapeAdActivity",
                "com.sigmob.sdk.base.common.LandscapeTransparentAdActivity",
                "com.sigmob.sdk.base.common.PortraitAdActivity",
                "com.sigmob.sdk.base.common.PortraitTransparentAdActivity",
                "com.sigmob.sdk.base.BaseAdActivity"
        };

        for (String act : activities) {
            Class<?> clazz = HookHelpers.findClassIfExists(act, classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(clazz.getDeclaredMethod("onCreate", Bundle.class),
                        SdkActivityLifecycle.finishAfterCreate("sigmob", "SigmobBlocker"));
            } catch (Throwable ignored) {
            }
        }
    }
}
