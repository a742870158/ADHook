package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 快手联盟 (KWAD / Kuaishou Ad SDK) 通用广告拦截器。
 * 针对 KsAdSDK.getLoadManager() 动态代理与具体类方法进行拦截，避免抽象方法异常。
 *
 * 幂等与去重（重要）：getLoadManager 的 after 回调与广告请求次数同频，
 * 必须按成功 Method 去重，否则 HookBridge 对同一方法只追加回调、不去重，
 * Hook 数量会随请求次数线性增长。缺类和失败方法不能预先标记为完成，下次必须可补装。
 */
public final class KwadBlocker {

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final int ERROR_CODE_NO_AD = -1;

    private KwadBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("kwad");

        try {
            Class<?> ksAdSdk = HookHelpers.findClassIfExists("com.kwad.sdk.api.KsAdSDK", classLoader);
            if (ksAdSdk == null) {
                // 未检测到 SDK：不记为已安装，允许后续重试
                return;
            }

            AdHookLog.i("[KwadBlocker] Detected KsAdSDK in classLoader=" + classLoader);
            hookGetLoadManager(ksAdSdk, classLoader);
            hookLoadManagerConcrete(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[KwadBlocker] install failed", t);
        }
    }

    private static void hookGetLoadManager(Class<?> ksAdSdk, final ClassLoader classLoader) {
        for (final Method method : ksAdSdk.getDeclaredMethods()) {
            if (!"getLoadManager".equals(method.getName()) || method.getParameterTypes().length != 0) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(method, new HookCallback() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Object realManager = param.getResult();
                        if (realManager == null) {
                            return;
                        }

                        // 按方法补装具体实现，已成功的目标不会重复挂载。
                        hookManagerMethods(realManager.getClass());
                        if (SdkRuntime.decision("kwad") == null) return;

                        Class<?> iface = HookHelpers.findClassIfExists("com.kwad.sdk.api.KsLoadManager", classLoader);
                        if (iface != null && iface.isInterface()) {
                            Object proxy = Proxy.newProxyInstance(
                                    classLoader,
                                    new Class<?>[]{iface},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                            String name = method.getName();
                                            SdkRuntime.Decision accounting = name.startsWith("load")
                                                    ? SdkRuntime.decision("kwad") : null;
                                            if (accounting != null) {
                                                AdHookLog.d("[KwadBlocker] Intercepted proxy KsLoadManager." + name);
                                                if (args != null) {
                                                    for (Object arg : args) {
                                                        AdCallbackHelper.notifyFailureIfCallback(
                                                                arg, ERROR_CODE_NO_AD, classLoader);
                                                    }
                                                }
                                                // 返回类型安全：void 返回 null，基本类型返回零值
                                                accounting.hit();
                                                return AdCallbackHelper.defaultValue(method.getReturnType());
                                            }
                                            try {
                                                return method.invoke(realManager, args);
                                            } catch (java.lang.reflect.InvocationTargetException ite) {
                                                // 还原 SDK 原始异常，避免代理把 InvocationTargetException 泄露给宿主
                                                Throwable cause = ite.getCause();
                                                throw cause != null ? cause : ite;
                                            }
                                        }
                                    }
                            );
                            param.setResult(proxy);
                            AdHookLog.d("[KwadBlocker] Replaced KsLoadManager with proxy blocker!");
                        }
                    }
                });
                AdHookLog.i("[KwadBlocker] Hooked KsAdSDK.getLoadManager()");
            } catch (Throwable t) {
                AdHookLog.e("[KwadBlocker] Hook getLoadManager failed", t);
            }
        }
    }

    private static void hookLoadManagerConcrete(ClassLoader classLoader) {
        Class<?> loadManagerCls = HookHelpers.findClassIfExists("com.kwad.sdk.api.KsLoadManager", classLoader);
        if (loadManagerCls != null && !loadManagerCls.isInterface()) {
            hookManagerMethods(loadManagerCls);
        }
    }

    private static void hookManagerMethods(Class<?> clazz) {
        if (clazz == null) {
            return;
        }

        String[] loadMethods = new String[]{
                "loadSplashScreenAd",
                "loadFeedAd",
                "loadNativeAd",
                "loadInterstitialAd",
                "loadRewardVideoAd"
        };

        for (final Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) || !contains(loadMethods, method.getName())) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(method, new HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SdkRuntime.Decision accounting = SdkRuntime.decision("kwad");
                        if (accounting == null) return;
                        AdHookLog.d("[KwadBlocker] Intercepted concrete " + method.getName());
                        AdCallbackHelper.setNullLikeResult(param);
                        accounting.hit();
                        AdCallbackHelper.notifyParamCallbacks(param, ERROR_CODE_NO_AD, null);
                    }
                });
                AdHookLog.i("[KwadBlocker] Hooked concrete " + clazz.getSimpleName() + "." + method.getName());
            } catch (Throwable ignored) {
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
