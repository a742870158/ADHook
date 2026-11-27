package com.adhook.core;

import com.adhook.config.ConfigClient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Set;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookHelpers;

/**
 * Registration facade for business hooks only. Lifecycle, class-loader probes and other observers
 * use the raw API101 backend so preferences never stop discovery or paired cleanup.
 */
public final class HookRegistration {
    private HookRegistration() { }

    public static HookCallback.Unhook hookMethod(Member method, HookCallback callback) {
        return HookBridge.hookMethod(method, guarded(callback));
    }

    public static Set<HookCallback.Unhook> hookAllMethods(Class<?> type, String name, HookCallback callback) {
        return HookBridge.hookAllMethods(type, name, guarded(callback));
    }

    public static Set<HookCallback.Unhook> hookAllConstructors(Class<?> type, HookCallback callback) {
        return HookBridge.hookAllConstructors(type, guarded(callback));
    }

    public static HookCallback.Unhook findAndHookMethod(Class<?> type, String name, Object... parameters) {
        return HookHelpers.findAndHookMethod(type, name, guardedParameters(parameters));
    }

    public static HookCallback.Unhook findAndHookMethod(String type, ClassLoader loader, String name,
                                                       Object... parameters) {
        return HookHelpers.findAndHookMethod(type, loader, name, guardedParameters(parameters));
    }

    public static HookCallback.Unhook findAndHookConstructor(Class<?> type, Object... parameters) {
        return HookHelpers.findAndHookConstructor(type, guardedParameters(parameters));
    }

    public static HookCallback.Unhook findAndHookConstructor(String type, ClassLoader loader,
                                                            Object... parameters) {
        return HookHelpers.findAndHookConstructor(type, loader, guardedParameters(parameters));
    }

    private static Object[] guardedParameters(Object[] parameters) {
        if (parameters == null || parameters.length == 0
                || !(parameters[parameters.length - 1] instanceof HookCallback)) {
            throw new IllegalArgumentException("No callback defined");
        }
        Object[] copy = parameters.clone();
        copy[copy.length - 1] = guarded((HookCallback) copy[copy.length - 1]);
        return copy;
    }

    static HookCallback guarded(HookCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback must not be null");
        String pkg = FeatureGuard.currentPackage();
        String feature = FeatureGuard.currentRegistrationFeature();
        // Resolve reflection at registration, never during a host callback.
        Method before = CallbackMethods.BEFORE;
        Method after = CallbackMethods.AFTER;
        return new HookCallback(callback.priority) {
            @Override public Class<?> registrationType() { return callback.registrationType(); }

            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                invoke(before, callback, param, pkg, feature);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                invoke(after, callback, param, pkg, feature);
            }
        };
    }

    private static boolean enabled(String pkg, String feature) {
        return feature == null ? ConfigClient.isHookEnabledCached(pkg) : FeatureGuard.isEnabled(pkg, feature);
    }

    private static void invoke(Method method, HookCallback callback, HookCallback.MethodHookParam param,
                               String pkg, String feature) throws Throwable {
        FeatureGuard.withRegistrationOwner(pkg, feature, () -> {
            if (!enabled(pkg, feature)) return;
            try {
                method.invoke(callback, param);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    private static final class CallbackMethods {
        static final Method BEFORE = resolve("beforeHookedMethod");
        static final Method AFTER = resolve("afterHookedMethod");

        private static Method resolve(String name) {
            try {
                Method method = HookCallback.class.getDeclaredMethod(name, HookCallback.MethodHookParam.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }
}
