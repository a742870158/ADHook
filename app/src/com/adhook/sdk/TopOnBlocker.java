package com.adhook.sdk;

import android.os.Bundle;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * TopOn / AnyThink 通用聚合广告拦截器。
 * 覆盖开屏 (ATSplashAd)、激励视频 (ATRewardVideoAd)、插屏 (ATInterstitial)、
 * 横幅 (ATBannerView) 与原生广告 (ATNative)。
 */
public final class TopOnBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final WeakListenerCache SPLASH_LISTENERS = new WeakListenerCache();

    private static final int ERROR_CODE_NO_AD = 4001;

    private TopOnBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("topon");

        try {
            Class<?> atSdk = HookHelpers.findClassIfExists("com.anythink.core.api.ATSDK", classLoader);
            Class<?> atSplash = HookHelpers.findClassIfExists("com.anythink.splashad.api.ATSplashAd", classLoader);

            if (atSdk == null && atSplash == null) {
                return;
            }

            AdHookLog.i("[TopOnBlocker] Detected TopOn/AnyThink SDK in classLoader=" + classLoader);
            hookSplashAd(classLoader);
            hookOtherAds(classLoader);
            hookAdActivities(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[TopOnBlocker] install failed", t);
        }
    }

    private static void hookSplashAd(final ClassLoader classLoader) {
        Class<?> clazz = HookHelpers.findClassIfExists("com.anythink.splashad.api.ATSplashAd", classLoader);
        if (clazz == null) {
            return;
        }

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

        for (Method method : clazz.getDeclaredMethods()) {
            String name = method.getName();
            if ("isAdReady".equals(name) && method.getReturnType() == boolean.class) {
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("topon");
                            if (accounting == null) return;
                            param.setResult(Boolean.FALSE);
                            accounting.hit();
                        }
                    });
                } catch (Throwable ignored) {
                }
            } else if ("loadAd".equals(name) || "show".equals(name)) {
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("topon");
                            if (accounting == null) return;
                            AdHookLog.d("[TopOnBlocker] Intercepted ATSplashAd." + param.method.getName());
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

    private static void hookOtherAds(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.anythink.rewardvideo.api.ATRewardVideoAd",
                "com.anythink.interstitial.api.ATInterstitial",
                "com.anythink.banner.api.ATBannerView",
                "com.anythink.nativead.api.ATNative"
        };

        for (String clsName : adClasses) {
            Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                if ("isAdReady".equals(name) && method.getReturnType() == boolean.class) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("topon");
                                if (accounting == null) return;
                                param.setResult(Boolean.FALSE);
                                accounting.hit();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                } else if ("load".equals(name) || "loadAd".equals(name) || "show".equals(name) || "makeAdRequest".equals(name)) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("topon");
                                if (accounting == null) return;
                                AdHookLog.d("[TopOnBlocker] Intercepted " + param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName());
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
                "com.anythink.china.activity.TransparentActivity",
                "com.anythink.myoffer.ui.MyOfferAdActivity"
        };

        for (String act : activities) {
            Class<?> clazz = HookHelpers.findClassIfExists(act, classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(clazz.getDeclaredMethod("onCreate", Bundle.class),
                        SdkActivityLifecycle.finishAfterCreate("topon", "TopOnBlocker"));
            } catch (Throwable ignored) {
            }
        }
    }
}
