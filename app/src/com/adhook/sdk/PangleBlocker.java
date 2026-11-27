package com.adhook.sdk;

import com.adhook.core.AdHookLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookHelpers;

/**
 * 字节跳动·穿山甲 (Pangle / CSJ / TTAdSdk) 通用广告拦截器。
 *
 * <p>只从公开 SDK 门面 {@code TTAdSdk -> TTAdManager -> TTAdNative} 建立拦截链：
 * manager/native 的真实实现类均从公开接口返回的运行时实例取得，再按接口中的唯一完整签名
 * 解析对应实现方法。缺类、签名歧义或实现不完整时一律 fail-open，不猜测内部实现类名。</p>
 *
 * <p>幂等与去重：只有成功安装的 Method 才进入 {@link SdkHookRegistry}；缺类和失败方法
 * 在后续安装或请求时仍可重试。</p>
 */
public final class PangleBlocker {

    private static final String TT_AD_SDK = "com.bytedance.sdk.openadsdk.TTAdSdk";
    private static final String TT_AD_NATIVE = "com.bytedance.sdk.openadsdk.TTAdNative";
    private static final SdkHookRegistry HOOKS = new SdkHookRegistry();
    private static final int ERROR_CODE_NO_AD = -1;

    private PangleBlocker() {
    }

    public static void install(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        SdkRuntime.bind("pangle");

        try {
            Class<?> ttAdSdk = HookHelpers.findClassIfExists(TT_AD_SDK, classLoader);
            Class<?> nativeContract = HookHelpers.findClassIfExists(TT_AD_NATIVE, classLoader);
            if (ttAdSdk == null || nativeContract == null || !nativeContract.isInterface()) {
                // SDK 尚未完整加载：不记为安装，允许同一 ClassLoader 后续补装。
                return;
            }

            Method getManager = findUniqueGetAdManagerMethod(ttAdSdk);
            if (getManager == null) {
                AdHookLog.w("[PangleBlocker] Public getAdManager contract is missing or ambiguous; fail-open");
                return;
            }
            hookAdManager(getManager, nativeContract, classLoader);
        } catch (Throwable t) {
            AdHookLog.e("[PangleBlocker] install failed", t);
        }
    }

    private static void hookAdManager(final Method getManager, final Class<?> nativeContract,
                                      final ClassLoader classLoader) {
        try {
            HOOKS.hookObserverMethod(getManager, new HookCallback() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable()) return;
                    Object manager = param.getResult();
                    if (manager != null) {
                        hookManagerInstance(manager, getManager.getReturnType(), nativeContract, classLoader);
                    }
                }
            });
            AdHookLog.i("[PangleBlocker] Hooked public TTAdSdk.getAdManager()");
        } catch (Throwable t) {
            AdHookLog.e("[PangleBlocker] Hook getAdManager failed", t);
        }
    }

    private static void hookManagerInstance(Object manager, Class<?> managerContract,
                                            final Class<?> nativeContract,
                                            final ClassLoader classLoader) {
        Method createContract = findUniqueCreateAdNativeContractMethod(managerContract, nativeContract);
        Method createImplementation = findUniqueImplementationMethod(manager.getClass(), createContract);
        if (createContract == null || createImplementation == null) {
            AdHookLog.w("[PangleBlocker] Public createAdNative implementation is missing or ambiguous; fail-open");
            return;
        }

        try {
            HOOKS.hookObserverMethod(createImplementation, new HookCallback() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable()) return;
                    final Object realNative = param.getResult();
                    if (realNative == null || !nativeContract.isInstance(realNative)) return;

                    // 实现方法来自公开接口返回的真实实例；不存在任何内部实现类名候选表。
                    hookNativeImplementation(realNative.getClass(), nativeContract, classLoader);
                    if (SdkRuntime.decision("pangle") == null) return;

                    ClassLoader proxyLoader = nativeContract.getClassLoader();
                    if (proxyLoader == null) proxyLoader = classLoader;
                    Object proxy = Proxy.newProxyInstance(proxyLoader, new Class<?>[]{nativeContract},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    SdkRuntime.Decision accounting = isLoadContractMethod(nativeContract, method)
                                            ? SdkRuntime.decision("pangle") : null;
                                    if (accounting != null) {
                                        if (args != null) {
                                            for (Object arg : args) {
                                                AdCallbackHelper.notifyFailureIfCallback(
                                                        arg, ERROR_CODE_NO_AD, classLoader);
                                            }
                                        }
                                        accounting.hit();
                                        return AdCallbackHelper.defaultValue(method.getReturnType());
                                    }
                                    try {
                                        return method.invoke(realNative, args);
                                    } catch (java.lang.reflect.InvocationTargetException wrapped) {
                                        Throwable cause = wrapped.getCause();
                                        throw cause != null ? cause : wrapped;
                                    }
                                }
                            });
                    param.setResult(proxy);
                    AdHookLog.d("[PangleBlocker] Replaced TTAdNative runtime instance with public-contract proxy");
                }
            });
            AdHookLog.i("[PangleBlocker] Hooked public createAdNative implementation on "
                    + manager.getClass().getName());
        } catch (Throwable t) {
            AdHookLog.w("[PangleBlocker] createAdNative hook failed; fail-open: " + t.getMessage());
        }
    }

    /**
     * 通过 TTAdNative 公开接口逐个解析真实实现方法。一个接口签名必须只对应一个非抽象、
     * 非 bridge/synthetic 的实现；否则不安装该方法。
     */
    private static void hookNativeImplementation(Class<?> implementation, Class<?> nativeContract,
                                                 ClassLoader classLoader) {
        for (Method contractMethod : nativeContract.getMethods()) {
            if (!isLoadContractMethod(nativeContract, contractMethod)) continue;
            final Method implementationMethod = findUniqueImplementationMethod(implementation, contractMethod);
            if (implementationMethod == null) {
                AdHookLog.w("[PangleBlocker] No unique implementation for public "
                        + contractMethod.getName() + "; fail-open");
                continue;
            }
            try {
                HOOKS.hookObserverMethod(implementationMethod, new HookCallback() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        SdkRuntime.Decision accounting = SdkRuntime.decision("pangle");
                        if (accounting == null) return;
                        AdCallbackHelper.setNullLikeResult(param);
                        accounting.hit();
                        AdCallbackHelper.notifyParamCallbacks(param, ERROR_CODE_NO_AD, classLoader);
                    }
                });
                AdHookLog.i("[PangleBlocker] Hooked TTAdNative contract method "
                        + implementationMethod.getName());
            } catch (Throwable t) {
                AdHookLog.w("[PangleBlocker] Native method hook failed; retry remains possible: "
                        + t.getMessage());
            }
        }
    }

    static Method findUniqueGetAdManagerMethod(Class<?> ttAdSdk) {
        if (ttAdSdk == null) return null;
        Method found = null;
        try {
            for (Method method : ttAdSdk.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()
                        || !Modifier.isStatic(method.getModifiers())
                        || !"getAdManager".equals(method.getName())
                        || method.getParameterCount() != 0
                        || method.getReturnType() == void.class
                        || method.getReturnType() == Object.class) continue;
                if (found != null) return null;
                found = method;
            }
            if (found != null) found.setAccessible(true);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Method findUniqueCreateAdNativeContractMethod(Class<?> managerContract,
                                                          Class<?> nativeContract) {
        if (managerContract == null || nativeContract == null || !nativeContract.isInterface()) return null;
        Method found = null;
        try {
            for (Method method : managerContract.getMethods()) {
                if (method.isBridge() || method.isSynthetic()
                        || Modifier.isStatic(method.getModifiers())
                        || !"createAdNative".equals(method.getName())
                        || !nativeContract.isAssignableFrom(method.getReturnType())) continue;
                if (found != null) return null;
                found = method;
            }
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Method findUniqueImplementationMethod(Class<?> implementation, Method contractMethod) {
        if (implementation == null || contractMethod == null) return null;
        Method found = null;
        try {
            for (Method method : implementation.getMethods()) {
                if (method.isBridge() || method.isSynthetic()
                        || Modifier.isAbstract(method.getModifiers())
                        || method.getDeclaringClass().isInterface()
                        || !method.getName().equals(contractMethod.getName())
                        || !sameParameters(method.getParameterTypes(), contractMethod.getParameterTypes())
                        || !contractMethod.getReturnType().isAssignableFrom(method.getReturnType())) continue;
                if (found != null) return null;
                found = method;
            }
            if (found != null) found.setAccessible(true);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isLoadContractMethod(Class<?> nativeContract, Method candidate) {
        if (nativeContract == null || candidate == null || !candidate.getName().startsWith("load")) return false;
        try {
            Method contract = nativeContract.getMethod(candidate.getName(), candidate.getParameterTypes());
            return !Modifier.isStatic(contract.getModifiers())
                    && contract.getReturnType().isAssignableFrom(candidate.getReturnType());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sameParameters(Class<?>[] first, Class<?>[] second) {
        if (first.length != second.length) return false;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) return false;
        }
        return true;
    }
}
