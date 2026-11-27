package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 腾讯·优量汇 (GDT / Tencent Ad SDK) 通用广告拦截器。
 *
 * 修复要点：
 * 1. NativeExpressAD / NativeUnifiedAD / UnifiedInterstitialAD / SplashAD 的 listener 是构造参数，
 *    loadAD/show 本身没有 listener 入参，因此这里在构造器阶段缓存 listener，
 *    拦截后从缓存补发 onNoAD(-1, ...)，避免宿主信息流/插屏一直等回调；
 * 2. 网络层伪造空响应只覆盖明确的广告拉取/上报域名，gdt.qq.com 的初始化/配置请求与
 *    init/config/settings 等路径一律放行，避免拖慢 SDK 初始化。
 */
public final class GdtBlocker {

    /** 非拥有型 listener 索引，弱值切断 listener -> Activity -> 广告实例回环。 */
    private static final WeakListenerCache LISTENERS = new WeakListenerCache();

    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();

    private static final int ERROR_CODE_NO_AD = -1;

    private GdtBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("gdt");

        try {
            Class<?> gdtAdSdk = HookHelpers.findClassIfExists("com.qq.e.comm.managers.GDTAdSdk", classLoader);
            Class<?> splashAd = HookHelpers.findClassIfExists("com.qq.e.ads.splash.SplashAD", classLoader);
            if (gdtAdSdk == null && splashAd == null) {
                return;
            }

            AdHookLog.i("[GdtBlocker] Detected Tencent GDT SDK in classLoader=" + classLoader);
            hookNetworkLayer(classLoader);
            hookSplash(classLoader);
            hookNativeAndInterstitial(classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[GdtBlocker] install failed", t);
        }
    }

    private static void hookSplash(final ClassLoader classLoader) {
        Class<?> splashAd = HookHelpers.findClassIfExists("com.qq.e.ads.splash.SplashAD", classLoader);
        if (splashAd == null) {
            return;
        }

        // SplashAD 的 listener 在构造函数里，先缓存，拦截拉取时补发 onNoAD
        for (Constructor<?> constructor : splashAd.getDeclaredConstructors()) {
            try {
                HOOKS.hookObserverMethod(constructor, new HookCallback() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        cacheListener(param.thisObject, AdCallbackHelper.collectParamCallbacks(param));
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        String[] fetchMethods = new String[]{
                "fetchAndShowIn",
                "fetchFullScreenAndShowIn",
                "fetchAdOnly",
                "showAd"
        };

        for (final Method method : splashAd.getDeclaredMethods()) {
            if (!contains(fetchMethods, method.getName())) {
                continue;
            }
            try {
                HOOKS.hookObserverMethod(method, new HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SdkRuntime.Decision accounting = SdkRuntime.decision("gdt");
                        if (accounting == null) return;
                        AdHookLog.d("[GdtBlocker] Intercepted SplashAD." + method.getName());
                        AdCallbackHelper.setNullLikeResult(param);
                        accounting.hit();
                        AdCallbackHelper.notifyCachedCallbacks(param, LISTENERS.get(param.thisObject),
                                ERROR_CODE_NO_AD, classLoader);
                    }
                });
                AdHookLog.i("[GdtBlocker] Hooked SplashAD." + method.getName());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookNativeAndInterstitial(final ClassLoader classLoader) {
        String[] adClasses = new String[]{
                "com.qq.e.ads.nativ.NativeExpressAD",
                "com.qq.e.ads.nativ.NativeUnifiedAD",
                "com.qq.e.ads.interstitial2.UnifiedInterstitialAD"
        };

        for (String clsName : adClasses) {
            final Class<?> clazz = HookHelpers.findClassIfExists(clsName, classLoader);
            if (clazz == null) {
                continue;
            }

            // listener 在构造函数里，登记到弱索引；loadAD/show 拦截后合并字段/参数补发 onNoAD。
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                try {
                    HOOKS.hookObserverMethod(constructor, new HookCallback() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            cacheListener(param.thisObject, AdCallbackHelper.collectParamCallbacks(param));
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            for (final Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                if (!"loadAD".equals(name) && !"loadAd".equals(name)
                        && !"show".equals(name) && !"showAsPopup".equals(name)) {
                    continue;
                }
                try {
                    HOOKS.hookObserverMethod(method, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("gdt");
                            if (accounting == null) return;
                            AdHookLog.d("[GdtBlocker] Intercepted " + clazz.getSimpleName() + "." + method.getName());
                            AdCallbackHelper.setNullLikeResult(param);
                            accounting.hit();
                            AdCallbackHelper.notifyCachedCallbacks(param, LISTENERS.get(param.thisObject),
                                    ERROR_CODE_NO_AD, classLoader);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void cacheListener(Object adInstance, java.util.List<Object> candidates) {
        if (adInstance == null || candidates == null) {
            return;
        }
        for (Object candidate : candidates) {
            if (AdCallbackHelper.hasFailureMethod(candidate)) {
                LISTENERS.put(adInstance, candidate);
                AdHookLog.d("[GdtBlocker] Cached listener " + candidate.getClass().getName()
                        + " for " + adInstance.getClass().getSimpleName());
                return;
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

    /** 明确的广告拉取 / 上报域名；gdt.qq.com 为 SDK 初始化与配置域名，不在此列（放行避免拖慢启动）。 */
    private static final String[] AD_HOST_KEYS = new String[]{
            "gdtimg.com", "adsmind",
            "e.qq.com", "gtimg.cn", "l.qq.com",
            "pangle.io", "pangle.cn", "openadsdk",
            "pglstatp-toutiao.com", "pangolin-sdk-toutiao.com",
            "toutiao.com", "toutiaoimg.com", "snssdk.com", "ctobsnssdk.com"
    };

    /** 初始化 / 配置 / 更新类路径一律放行，不伪造响应。 */
    private static final String[] INIT_PATH_KEYS = new String[]{
            "/init", "init/", "config", "setting", "update", "/sdk", "appid", "common"
    };

    private static boolean isAdUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        // gdt.qq.com 承担 SDK 初始化与远程配置，保持真实响应，避免空 JSON 触发异常/重试
        if (lower.contains("gdt.qq.com")) {
            return false;
        }
        for (String initKey : INIT_PATH_KEYS) {
            if (lower.contains(initKey)) {
                return false;
            }
        }
        for (String key : AD_HOST_KEYS) {
            if (lower.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static Object fakeResponse(final Class<?> respCls) {
        try {
            final String payload = "{}";
            final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            return Proxy.newProxyInstance(
                    respCls.getClassLoader(),
                    new Class<?>[]{respCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String mName = method.getName();
                            if ("getStatusCode".equals(mName)) {
                                return 200;
                            } else if ("getStringContent".equals(mName)) {
                                return payload;
                            } else if ("getBytesContent".equals(mName)) {
                                return bytes;
                            } else if ("getStreamContent".equals(mName)) {
                                return new ByteArrayInputStream(bytes);
                            } else if ("close".equals(mName)) {
                                return null;
                            } else if ("toString".equals(mName)) {
                                return "GdtFakeResponse(200,{})";
                            }
                            Class<?> rType = method.getReturnType();
                            if (rType == Integer.TYPE || rType == Integer.class) {
                                return 0;
                            } else if (rType == Boolean.TYPE || rType == Boolean.class) {
                                return false;
                            } else if (rType == Long.TYPE || rType == Long.class) {
                                return 0L;
                            }
                            return null;
                        }
                    }
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object fakeFuture(final Class<?> futureCls, final Object response) {
        try {
            return Proxy.newProxyInstance(
                    futureCls.getClassLoader(),
                    new Class<?>[]{futureCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String mName = method.getName();
                            if ("get".equals(mName)) {
                                return response;
                            } else if ("cancel".equals(mName) || "isCancelled".equals(mName)) {
                                return false;
                            } else if ("isDone".equals(mName)) {
                                return true;
                            }
                            Class<?> rType = method.getReturnType();
                            if (rType == Integer.TYPE || rType == Integer.class) {
                                return 0;
                            } else if (rType == Boolean.TYPE || rType == Boolean.class) {
                                return false;
                            } else if (rType == Long.TYPE || rType == Long.class) {
                                return 0L;
                            }
                            return null;
                        }
                    }
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookNetworkLayer(final ClassLoader classLoader) {
        try {
            final Class<?> clientCls = HookHelpers.findClassIfExists("com.qq.e.comm.net.NetworkClientImpl", classLoader);
            final Class<?> reqCls = HookHelpers.findClassIfExists("com.qq.e.comm.net.rr.Request", classLoader);
            final Class<?> respCls = HookHelpers.findClassIfExists("com.qq.e.comm.net.rr.Response", classLoader);
            final Class<?> cbCls = HookHelpers.findClassIfExists("com.qq.e.comm.net.NetworkCallBack", classLoader);
            final Class<?> futureCls = Future.class;

            if (clientCls == null || reqCls == null || respCls == null) {
                return;
            }

            final Object sample = fakeResponse(respCls);
            if (sample == null) {
                return;
            }

            final Method getUrlMethod;
            Method tempGetUrl = null;
            try {
                tempGetUrl = reqCls.getMethod("getUrlWithParas");
            } catch (Throwable ignored) {
                try {
                    tempGetUrl = reqCls.getMethod("getUrl");
                } catch (Throwable ignored2) {
                }
            }
            getUrlMethod = tempGetUrl;

            final Method onResponseMethod;
            Method tempOnResponse = null;
            if (cbCls != null) {
                try {
                    tempOnResponse = cbCls.getMethod("onResponse", reqCls, respCls);
                } catch (Throwable ignored) {
                }
            }
            onResponseMethod = tempOnResponse;

            for (final Method method : clientCls.getDeclaredMethods()) {
                if (!"submit".equals(method.getName())) {
                    continue;
                }
                final boolean returnsFuture = method.getReturnType() != Void.TYPE;
                HOOKS.hookObserverMethod(method, new HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SdkRuntime.Decision accounting = SdkRuntime.decision("gdt");
                        if (accounting == null) return;
                        if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                            return;
                        }
                        Object req = param.args[0];
                        String url = null;
                        if (getUrlMethod != null) {
                            try {
                                Object uObj = getUrlMethod.invoke(req);
                                if (uObj != null) {
                                    url = uObj.toString();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        if (url == null || !isAdUrl(url)) {
                            return;
                        }

                        Object resp = fakeResponse(respCls);
                        if (resp == null) {
                            return;
                        }

                        if (cbCls != null && onResponseMethod != null) {
                            for (int i = 1; i < param.args.length; i++) {
                                Object a = param.args[i];
                                if (a != null && cbCls.isAssignableFrom(a.getClass())) {
                                    try {
                                        onResponseMethod.invoke(a, req, resp);
                                    } catch (Throwable ignored) {
                                    }
                                    break;
                                }
                            }
                        }

                        AdHookLog.d("[GdtBlocker] Mocked empty response for submit: " + url);
                        if (returnsFuture) {
                            param.setResult(fakeFuture(futureCls, resp));
                        } else {
                            param.setResult(null);
                        }
                        accounting.hit();
                    }
                });
            }

            // Hook NetworkClientImpl$TaskCallable.call
            Class<?> taskCallableCls = HookHelpers.findClassIfExists("com.qq.e.comm.net.NetworkClientImpl$TaskCallable", classLoader);
            if (taskCallableCls != null) {
                for (Method m : taskCallableCls.getDeclaredMethods()) {
                    if (!"call".equals(m.getName()) || m.getParameterTypes().length != 0) {
                        continue;
                    }
                    final Field[] declaredFields = taskCallableCls.getDeclaredFields();
                    for (Field f : declaredFields) {
                        f.setAccessible(true);
                    }
                    HOOKS.hookObserverMethod(m, new HookCallback() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SdkRuntime.Decision accounting = SdkRuntime.decision("gdt");
                            if (accounting == null) return;
                            String url = null;
                            for (Field f : declaredFields) {
                                try {
                                    Object req = f.get(param.thisObject);
                                    if (req != null && reqCls.isAssignableFrom(req.getClass())) {
                                        if (getUrlMethod != null) {
                                            Object uObj = getUrlMethod.invoke(req);
                                            if (uObj != null) {
                                                url = uObj.toString();
                                            }
                                        }
                                        if (url != null) {
                                            break;
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            if (url != null && isAdUrl(url)) {
                                Object resp = fakeResponse(respCls);
                                if (resp != null) {
                                    AdHookLog.d("[GdtBlocker] Mocked empty response in TaskCallable: " + url);
                                    param.setResult(resp);
                                    accounting.hit();
                                }
                            }
                        }
                    });
                    break;
                }
            }

            // Hook PlainRequest and S2SSRequest initResponse
            String[] reqImpls = new String[]{
                    "com.qq.e.comm.net.rr.PlainRequest",
                    "com.qq.e.comm.net.rr.S2SSRequest"
            };
            for (String impl : reqImpls) {
                Class<?> implCls = HookHelpers.findClassIfExists(impl, classLoader);
                if (implCls != null) {
                    try {
                        Method initResp = implCls.getDeclaredMethod("initResponse", java.net.HttpURLConnection.class);
                        HOOKS.hookObserverMethod(initResp, new HookCallback() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                SdkRuntime.Decision accounting = SdkRuntime.decision("gdt");
                                if (accounting == null) return;
                                Object req = param.thisObject;
                                String url = null;
                                if (req != null && getUrlMethod != null) {
                                    try {
                                        Object uObj = getUrlMethod.invoke(req);
                                        if (uObj != null) {
                                            url = uObj.toString();
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                if (url != null && isAdUrl(url)) {
                                    Object resp = fakeResponse(respCls);
                                    if (resp != null) {
                                        AdHookLog.d("[GdtBlocker] Mocked empty response in initResponse: " + url);
                                        param.setResult(resp);
                                        accounting.hit();
                                    }
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            }

            AdHookLog.i("[GdtBlocker] Hooked NetworkClientImpl network layer successfully");
        } catch (Throwable t) {
            AdHookLog.e("[GdtBlocker] hookNetworkLayer failed", t);
        }
    }
}
