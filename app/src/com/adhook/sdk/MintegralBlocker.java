package com.adhook.sdk;

import android.os.Bundle;

import com.adhook.core.AdHookLog;

import java.lang.reflect.Method;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * Mintegral (Mobvista / MBridge) 通用广告拦截器。
 * 覆盖开屏 (MBSplashHandler)、激励视频 (MBRewardVideoHandler)、
 * 插屏 (MBInterstitialVideoHandler / MBInterstitialHandler)、
 * 原生 (MBNativeHandler / MBNativeAdvancedHandler) 与横幅 (MBBannerView)。
 */
public final class MintegralBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final int ERROR_CODE_NO_AD = -1;

    private MintegralBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("mintegral");

        try {
            Class<?> mbridgeSdk = HookHelpers.findClassIfExists("com.mbridge.msdk.MBridgeSDK", classLoader);
            Class<?> splashHandler = HookHelpers.findClassIfExists("com.mbridge.msdk.out.MBSplashHandler", classLoader);

            if (mbridgeSdk == null && splashHandler == null) {
                return;
            }

            AdHookLog.i("[MintegralBlocker] Detected Mintegral SDK in classLoader=" + classLoader);
            hookHandlers(classLoader);
            hookAdActivities(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[MintegralBlocker] install failed", t);
        }
    }

    private static void hookHandlers(final ClassLoader classLoader) {
        String[] handlerClasses = new String[]{
                "com.mbridge.msdk.out.MBSplashHandler",
                "com.mbridge.msdk.out.MBRewardVideoHandler",
                "com.mbridge.msdk.interstitialvideo.out.MBInterstitialVideoHandler",
                "com.mbridge.msdk.out.MBInterstitialHandler",
                "com.mbridge.msdk.out.MBNativeHandler",
                "com.mbridge.msdk.out.MBNativeAdvancedHandler",
                "com.mbridge.msdk.out.MBBannerView"
        };

        for (String clsName : handlerClasses) {
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
                                SdkRuntime.Decision accounting = SdkRuntime.decision("mintegral");
                                if (accounting == null) return;
                                param.setResult(Boolean.FALSE);
                                accounting.hit();
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                } else if ("load".equals(name) || "preLoadByToken".equals(name) ||
                        "loadAndShow".equals(name) || "loadAndShowByToken".equals(name) ||
                        "show".equals(name) || "showByToken".equals(name) ||
                        "preload".equals(name) || "loadFromBid".equals(name)) {
                    try {
                        HOOKS.hookObserverMethod(method, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("mintegral");
                                if (accounting == null) return;
                                AdHookLog.d("[MintegralBlocker] Intercepted " + param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName());
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
                "com.mbridge.msdk.activity.MBCommonActivity",
                "com.mbridge.msdk.reward.player.MBRewardVideoActivity",
                "com.mbridge.msdk.interstitial.view.MBInterstitialActivity"
        };

        for (String act : activities) {
            Class<?> clazz = HookHelpers.findClassIfExists(act, classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(clazz.getDeclaredMethod("onCreate", Bundle.class),
                        SdkActivityLifecycle.finishAfterCreate("mintegral", "MintegralBlocker"));
            } catch (Throwable ignored) {
            }
        }
    }
}
