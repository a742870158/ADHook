package com.adhook.core;

import com.adhook.runtime.HookBridge;
import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HostContext;

import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Successful raw observer identities only; never applies a first installer's business gate. */
public final class ObserverHookRegistry {
    private record Owner(String hostPackage, String processName, String packageName, String feature,
                         String origin, String site, int priority) { }
    // No scope, loader, member, callback or handle is strongly retained by this registry.
    private final Map<ClassLoader, Map<Member, Map<Owner, WeakReference<HookCallback.Unhook>>>> callbacks = new WeakHashMap<>();
    private final Map<Member, java.util.Set<Owner>> inFlight = new WeakHashMap<>();

    public synchronized HookCallback.Unhook hook(Member member, HookCallback callback) {
        if (member == null || callback == null) throw new IllegalArgumentException("Observer member/callback required");
        HostContext scope = HookBridge.currentRegistrationScope();
        // Dynamic registrations without a scope can identify only the declaring loader. A framework
        // member cannot reveal an arbitrary captured loader: such callers must supply a native scope.
        ClassLoader loader = scope != null ? scope.classLoader : member.getDeclaringClass().getClassLoader();
        Owner owner = new Owner(scope == null ? null : scope.packageName, scope == null ? null : scope.processName,
                FeatureGuard.currentPackage(), FeatureGuard.currentRegistrationFeature(),
                callback.registrationType().getName(), callerSite(), callback.priority);
        java.util.Set<Owner> pending = inFlight.computeIfAbsent(member, ignored -> new java.util.HashSet<>());
        if (!pending.isEmpty()) throw new IllegalStateException("Observer member registration re-entered before completion");
        pending.add(owner);
        Map<Member, Map<Owner, WeakReference<HookCallback.Unhook>>> members = callbacks.get(loader);
        Map<Owner, WeakReference<HookCallback.Unhook>> entries = members == null ? null : members.get(member);
        WeakReference<HookCallback.Unhook> reference = entries == null ? null : entries.get(owner);
        HookCallback.Unhook previous = reference == null ? null : reference.get();
        try {
            // Retry active registrations with their original identity; after unhook use the new closure.
            HookCallback selected = previous != null && previous.isActive() ? previous.getCallback() : callback;
            HookCallback.Unhook handle = HookBridge.hookMethod(member, selected);
            callbacks.computeIfAbsent(loader, ignored -> new WeakHashMap<>())
                    .computeIfAbsent(member, ignored -> new HashMap<>()).put(owner, new WeakReference<>(handle));
            return handle;
        } catch (RuntimeException | Error failure) {
            if (entries != null) {
                entries.remove(owner);
                if (entries.isEmpty()) members.remove(member);
                if (members.isEmpty()) callbacks.remove(loader);
            }
            throw failure;
        } finally {
            pending.remove(owner);
            if (pending.isEmpty()) inFlight.remove(member);
        }
    }

    private static String callerSite() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String type = frame.getClassName();
            if (type.equals(ObserverHookRegistry.class.getName()) || type.startsWith("java.") || type.startsWith("jdk.")) continue;
            return type + '#' + frame.getMethodName() + ':' + frame.getLineNumber();
        }
        throw new IllegalStateException("Observer registration source missing");
    }
}
