package com.adhook.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/** The only hook backend. API jars are compile-time contracts, never executable stubs. */
public final class HookBridge {
    private static volatile XposedInterface framework;
    private static final Map<Member, Map<HookCallback, HookCallback.Unhook>> HOOKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> FAILURES = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<HostContext> REGISTRATION_HOST = new ThreadLocal<>();
    private static final Map<RegistrationKey, HookCallback.Unhook> SUCCESSFUL_SITES = new ConcurrentHashMap<>();
    private HookBridge() { }

    /** Read-only transient metadata; registries must never retain this scope or its loader strongly. */
    public static HostContext currentRegistrationScope() { return REGISTRATION_HOST.get(); }

    @FunctionalInterface public interface RegistrationAction { void run() throws Throwable; }
    public static void withRegistrationScope(HostContext host, RegistrationAction action) throws Throwable {
        HostContext previous = REGISTRATION_HOST.get();
        REGISTRATION_HOST.set(host);
        try { action.run(); }
        finally { if (previous == null) REGISTRATION_HOST.remove(); else REGISTRATION_HOST.set(previous); }
    }

    private record RegistrationKey(String packageName, String processName, ClassLoader loader,
                                   String ownerPackage, String feature,
                                   Member member, String site, Class<?> callbackType, int priority) { }
    private static RegistrationKey registrationKey(Member member, HookCallback callback) {
        HostContext host = REGISTRATION_HOST.get();
        if (host == null) return null; // Dynamic registrations keep explicit callback-identity semantics.
        String site = callback.registrationType().getName();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String type = frame.getClassName();
            if (type.equals(HookBridge.class.getName()) || type.equals(HookHelpers.class.getName())
                    || type.startsWith("java.") || type.startsWith("jdk.")
                    || type.equals(com.adhook.core.HookRegistration.class.getName())
                    || type.equals(com.adhook.core.ObserverHookRegistry.class.getName())
                    || type.startsWith(com.adhook.core.ObserverHookRegistry.class.getName() + "$")
                    || type.equals(com.adhook.sdk.SdkHookRegistry.class.getName())
                    || type.startsWith(com.adhook.sdk.SdkHookRegistry.class.getName() + "$")) continue;
            site = type + '#' + frame.getMethodName() + ':' + frame.getLineNumber();
            break;
        }
        String ownerPackage = com.adhook.core.FeatureGuard.currentPackage();
        if (ownerPackage == null) return null; // Unknown ownership is never guessed into a shared identity.
        return new RegistrationKey(host.packageName, host.processName, host.classLoader,
                ownerPackage, com.adhook.core.FeatureGuard.currentRegistrationFeature(),
                member, site, callback.registrationType(), callback.priority);
    }

    public static synchronized void initialize(XposedInterface api) {
        if (api == null || api.getApiVersion() < 101) throw new IllegalArgumentException("API101 framework required");
        if (framework != null && framework != api) throw new IllegalStateException("Hook framework already initialized");
        framework = api;
    }

    public static boolean isInitialized() { return framework != null; }
    public static long registrationFailureCount() { return FAILURES.get(); }
    static void registrationFailed() { FAILURES.set(FAILURES.get() + 1L); }

    private static XposedInterface requireFramework() {
        XposedInterface api = framework;
        if (api == null) throw new IllegalStateException("Native API101 hook backend is not initialized");
        return api;
    }

    public static synchronized HookCallback.Unhook hookMethod(Member member, HookCallback callback) {
        try {
            if (!(member instanceof Executable)) throw new IllegalArgumentException("Only methods and constructors can be hooked");
            if (callback == null) throw new IllegalArgumentException("Callback is required");
            Executable executable = (Executable) member;
            if (Modifier.isAbstract(executable.getModifiers())) throw new IllegalArgumentException("Abstract executable cannot be hooked");
            RegistrationKey siteKey = registrationKey(member, callback);
            HookCallback.Unhook siteExisting = siteKey == null ? null : SUCCESSFUL_SITES.get(siteKey);
            if (siteExisting != null && siteExisting.isActive()) return siteExisting;
            Map<HookCallback, HookCallback.Unhook> callbacks = HOOKS.get(member);
            HookCallback.Unhook existing = callbacks == null ? null : callbacks.get(callback);
            if (existing != null && existing.isActive()) return existing;
            XposedInterface.HookHandle handle = requireFramework().hook(executable)
                    .setPriority(callback.priority)
                    // We isolate callback errors ourselves. Original exceptions must not be swallowed.
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> intercept(callback, chain));
            if (handle == null) throw new IllegalStateException("Framework returned no native hook handle");
            HookCallback.Unhook unhook = callback.new Unhook(member, handle);
            HOOKS.computeIfAbsent(member, ignored -> new IdentityHashMap<>()).put(callback, unhook);
            if (siteKey != null) SUCCESSFUL_SITES.put(siteKey, unhook);
            return unhook;
        } catch (RuntimeException | Error failure) {
            registrationFailed();
            throw failure;
        }
    }

    private static Object intercept(HookCallback callback, XposedInterface.Chain chain) throws Throwable {
        HookCallback.MethodHookParam param = new HookCallback.MethodHookParam();
        param.method = chain.getExecutable();
        param.thisObject = chain.getThisObject();
        param.args = chain.getArgs().toArray();
        HookCallback.MethodHookParam.State before = param.save();
        try { callback.beforeHookedMethod(param); }
        catch (Throwable failure) { param.restore(before); log(failure); }
        if (!param.isReturnEarly()) {
            try {
                Object result = param.thisObject == chain.getThisObject()
                        ? chain.proceed(param.args) : chain.proceedWith(param.thisObject, param.args);
                param.outcome(result, null, false);
            } catch (Throwable originalFailure) { param.outcome(null, originalFailure, false); }
        }
        HookCallback.MethodHookParam.State after = param.save();
        try { callback.afterHookedMethod(param); }
        catch (Throwable failure) { param.restore(after); log(failure); }
        return param.getResultOrThrowable();
    }

    public static Set<HookCallback.Unhook> hookAllMethods(Class<?> type, String name, HookCallback callback) {
        Set<HookCallback.Unhook> handles = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) handles.add(hookMethod(method, callback));
        }
        return handles;
    }

    public static Set<HookCallback.Unhook> hookAllConstructors(Class<?> type, HookCallback callback) {
        Set<HookCallback.Unhook> handles = new LinkedHashSet<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) handles.add(hookMethod(constructor, callback));
        return handles;
    }

    public static void unhookMethod(Member member, HookCallback callback) {
        HookCallback.Unhook unhook;
        synchronized (HookBridge.class) {
            Map<HookCallback, HookCallback.Unhook> callbacks = HOOKS.get(member);
            unhook = callbacks == null ? null : callbacks.get(callback);
        }
        if (unhook != null) unhook.unhook();
    }

    static synchronized void forget(HookCallback.Unhook unhook) {
        SUCCESSFUL_SITES.values().removeIf(value -> value == unhook);
        Map<HookCallback, HookCallback.Unhook> callbacks = HOOKS.get(unhook.getHookedMethod());
        if (callbacks == null) return;
        callbacks.remove(unhook.getCallback(), unhook);
        if (callbacks.isEmpty()) HOOKS.remove(unhook.getHookedMethod());
    }

    public static Object invokeOriginalMethod(Member member, Object receiver, Object[] args)
            throws InvocationTargetException, IllegalAccessException {
        XposedInterface api = requireFramework();
        if (member instanceof Method) {
            return api.getInvoker((Method) member).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(receiver, args);
        }
        if (member instanceof Constructor<?>) {
            return api.getInvoker((Constructor<?>) member).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(receiver, args);
        }
        throw new IllegalArgumentException("Only methods and constructors have an original implementation");
    }

    public static void log(String message) {
        XposedInterface api = framework;
        if (api != null) {
            try { api.log(6, "ADHook", message); } catch (Throwable ignored) { }
        }
    }
    public static void log(Throwable failure) {
        XposedInterface api = framework;
        if (api != null) {
            try { api.log(6, "ADHook", "Hook callback failed; host outcome preserved", failure); }
            catch (Throwable ignored) { }
        }
    }

    static void resetForTests() {
        ArrayList<HookCallback.Unhook> handles = new ArrayList<>();
        synchronized (HookBridge.class) {
            for (Map<HookCallback, HookCallback.Unhook> callbacks : HOOKS.values()) handles.addAll(callbacks.values());
        }
        for (HookCallback.Unhook handle : handles) handle.unhook();
        synchronized (HookBridge.class) { framework = null; SUCCESSFUL_SITES.clear(); FAILURES.remove(); REGISTRATION_HOST.remove(); }
    }
}
