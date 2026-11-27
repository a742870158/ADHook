package com.adhook.sdk;

import android.view.View;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 小米 Mimo 移动广告 SDK 通用拦截器。
 * 覆盖插屏广告 (InterstitialAd)、激励视频广告 (RewardAd) 以及相关开屏跳过组件。
 */
public final class MimoBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final int ERROR_CODE_NO_AD = -1;

    private MimoBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("mimo");

        try {
            Class<?> interstitialAd = HookHelpers.findClassIfExists(
                    "com.miui.zeus.mimo.sdk.ad.interstitial.InterstitialAd", classLoader);
            Class<?> rewardAd = HookHelpers.findClassIfExists(
                    "com.miui.zeus.mimo.sdk.ad.reward.RewardAd", classLoader);
            Class<?> mimoSdk = HookHelpers.findClassIfExists(
                    "com.miui.zeus.mimo.sdk.MimoSdk", classLoader);

            if (interstitialAd == null && rewardAd == null && mimoSdk == null) {
                return;
            }

            AdHookLog.i("[MimoBlocker] Detected Xiaomi Mimo SDK in classLoader=" + classLoader);
            hookAds(classLoader);
            hookViews(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[MimoBlocker] install failed", t);
        }
    }

    private static void hookAds(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.miui.zeus.mimo.sdk.ad.interstitial.InterstitialAd",
                "com.miui.zeus.mimo.sdk.ad.reward.RewardAd"
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
                                SdkRuntime.Decision accounting = SdkRuntime.decision("mimo");
                                if (accounting == null) return;
                                param.setResult(Boolean.FALSE);
                                accounting.hit();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                } else if ("load".equals(name) || "show".equals(name) || "request".equals(name)) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("mimo");
                                if (accounting == null) return;
                                AdHookLog.d("[MimoBlocker] Intercepted " + param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName());
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

    private static void hookViews(ClassLoader classLoader) {
        String[] views = new String[]{
                "com.miui.zeus.mimo.sdk.ad.interstitial.view.InterstitialSkipCountDownView",
                "com.miui.zeus.mimo.sdk.ad.reward.view.RewardSkipCountDownView"
        };

        for (String viewName : views) {
            Class<?> clazz = HookHelpers.findClassIfExists(viewName, classLoader);
            if (clazz == null) {
                continue;
            }
            for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                try {
                    HOOKS.hookObserverMethod(constructor, new HookCallback() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.thisObject instanceof View) {
                                SdkRuntime.collapse("mimo", (View) param.thisObject);
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
