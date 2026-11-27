package com.adhook.core;

import com.adhook.runtime.HookCallback;
import com.adhook.runtime.HostContext;
import com.adhook.runtime.HookBridge;
import com.adhook.runtime.NativeApi101TestFramework;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ObserverHookRegistryTest {
    private NativeApi101TestFramework framework;
    private ObserverHookRegistry registry;
    private Method target;
    private final List<String> hits = new ArrayList<>();
    public static class Target { public String echo(String text) { return text; } }
    @Before public void setUp() throws Exception {
        framework = new NativeApi101TestFramework(); framework.reset(); framework.initialize();
        registry = new ObserverHookRegistry(); target = Target.class.getMethod("echo", String.class); hits.clear();
    }
    @After public void tearDown() { framework.reset(); }

    private HookCallback.Unhook register(String pkg, String feature) throws Throwable {
        HookCallback.Unhook[] handle = new HookCallback.Unhook[1];
        FeatureGuard.withRegistrationOwner(pkg, feature, () -> {
            handle[0] = registry.hook(target, new HookCallback(71) {
                @Override protected void beforeHookedMethod(MethodHookParam param) { hits.add(pkg + ":" + feature); }
            });
        });
        return handle[0];
    }

    @Test public void sameMemberAndFactoryKeepDifferentOwnersAndFeaturesIndependent() throws Throwable {
        register("com.owner.a", "same"); register("com.owner.b", "same"); register("com.owner.a", "other");
        register("com.owner.a", "same");
        assertEquals(3, framework.installCount(target));
        assertEquals("host", framework.call(target, new Target(), "host"));
        assertEquals(List.of("com.owner.a:same", "com.owner.b:same", "com.owner.a:other"), hits);
    }

    @Test public void failedRegistrationDoesNotPoisonSuccessOrNextRetry() throws Throwable {
        framework.failOnce(target);
        assertThrows(IllegalStateException.class, () -> register("com.owner.a", "same"));
        Field field = ObserverHookRegistry.class.getDeclaredField("callbacks"); field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(registry)).isEmpty());
        HookCallback.Unhook success = register("com.owner.a", "same");
        assertSame(success, register("com.owner.a", "same"));
        assertEquals(1, framework.installCount(target));
    }

    @Test public void unhookThenRegistrationRepairsNativeHandleWithoutDuplicate() throws Throwable {
        HookCallback.Unhook first = register("com.owner.a", "same");
        first.unhook(); assertEquals(0, framework.installCount(target));
        HookCallback.Unhook next = register("com.owner.a", "same");
        assertNotSame(first, next); assertSame(next, register("com.owner.a", "same"));
        assertEquals(1, framework.installCount(target));
    }

    @Test public void selfReentryIsIncompleteNotSuccessAndFailureAllowsNextAttempt() throws Throwable {
        framework.reset();
        java.util.concurrent.atomic.AtomicBoolean reenter = new java.util.concurrent.atomic.AtomicBoolean(true);
        io.github.libxposed.api.XposedInterface wrapped = (io.github.libxposed.api.XposedInterface) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.github.libxposed.api.XposedInterface.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hook") && reenter.compareAndSet(true, false)) register("com.owner.a", "same");
                    try { return method.invoke(framework.api, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        HookBridge.initialize(wrapped);
        assertThrows(IllegalStateException.class, () -> register("com.owner.a", "same"));
        assertEquals(0, framework.installCount(target));
        Field field = ObserverHookRegistry.class.getDeclaredField("inFlight"); field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(registry)).isEmpty());
        register("com.owner.a", "same"); register("com.owner.a", "same");
        assertEquals(1, framework.installCount(target));
    }

    @Test public void scopedSourceFilteringPreservesDistinctOutsideCallSites() throws Throwable {
        HostContext host = new HostContext("com.owner.a", "com.owner.a", getClass().getClassLoader(), null, true);
        FeatureGuard.withRegistrationOwner(host.packageName, "same", () -> HookBridge.withRegistrationScope(host, () -> {
            firstSite(); secondSite(); firstSite(); secondSite();
        }));
        assertEquals(2, framework.installCount(target));
    }
    private HookCallback.Unhook registerVariant(HostContext scope, String label, int priority) throws Throwable {
        HookCallback.Unhook[] handle = new HookCallback.Unhook[1];
        HookBridge.RegistrationAction action = () -> {
            handle[0] = registry.hook(target, new HookCallback(priority) {
                @Override protected void beforeHookedMethod(MethodHookParam param) { hits.add(label); }
            });
        };
        FeatureGuard.withRegistrationOwner("com.owner.a", "same", () -> {
            if (scope == null) action.run(); else HookBridge.withRegistrationScope(scope, action);
        });
        return handle[0];
    }

    @Test public void sharedParentMemberInDifferentInjectedLoadersKeepsBothClosuresIndependent() throws Throwable {
        ClassLoader firstLoader = new ClassLoader(getClass().getClassLoader()) { };
        ClassLoader secondLoader = new ClassLoader(getClass().getClassLoader()) { };
        HostContext first = new HostContext("com.owner.a", "com.owner.a", firstLoader, null, true);
        HostContext second = new HostContext("com.owner.a", "com.owner.a", secondLoader, null, true);
        // Both injected loaders share the exact parent-declared executable, like Activity.onResume.
        HookCallback.Unhook a = registerVariant(first, "loader-A", 71);
        HookCallback.Unhook b = registerVariant(second, "loader-B", 71);
        assertSame(a, registerVariant(first, "ignored-retry-A", 71));
        assertSame(b, registerVariant(second, "ignored-retry-B", 71));
        assertEquals(2, framework.installCount(target));
        framework.call(target, new Target(), "host");
        assertEquals(List.of("loader-A", "loader-B"), hits);
        assertNull(HookBridge.currentRegistrationScope());
    }

    @Test public void processIdentityRemainsIndependentWithinTheSameLoader() throws Throwable {
        ClassLoader loader = getClass().getClassLoader();
        HostContext main = new HostContext("com.owner.a", "com.owner.a", loader, null, true);
        HostContext worker = new HostContext("com.owner.a", "com.owner.a:worker", loader, null, true);
        registerVariant(main, "main", 71); registerVariant(worker, "worker", 71);
        assertEquals(2, framework.installCount(target));
        framework.call(target, new Target(), "host");
        assertEquals(List.of("main", "worker"), hits);
    }

    @Test public void sameOwnerSourceAndOriginWithDifferentPriorityDoesNotReuseOldCallback() throws Throwable {
        registerVariant(null, "low", 20); registerVariant(null, "high", 80);
        registerVariant(null, "ignored-high-retry", 80);
        assertEquals(2, framework.installCount(target));
        framework.call(target, new Target(), "host");
        assertEquals(List.of("high", "low"), hits);
    }

    @Test public void unhookUsesNewClosureAndCannotResurrectOldCapturedState() throws Throwable {
        HookCallback.Unhook old = registerVariant(null, "old-state", 71);
        framework.call(target, new Target(), "host"); assertEquals(List.of("old-state"), hits);
        old.unhook(); assertFalse(old.isActive()); hits.clear();
        HookCallback.Unhook fresh = registerVariant(null, "new-state", 71);
        assertTrue(fresh.isActive()); assertNotSame(old, fresh);
        assertSame(fresh, registerVariant(null, "ignored-retry", 71));
        framework.call(target, new Target(), "host"); assertEquals(List.of("new-state"), hits);
        assertEquals(1, framework.installCount(target));
    }

    private HookCallback sameOrigin() { return new HookCallback() { }; }
    private void firstSite() { registry.hook(target, sameOrigin()); }
    private void secondSite() { registry.hook(target, sameOrigin()); }
}
