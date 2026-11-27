package com.adhook.runtime;

import com.adhook.core.FeatureGuard;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.*;

public class HookBridgeNativeAdapterTest {
    private NativeApi101TestFramework nativeApi;
    private Method echo;
    private final Target target = new Target("host:");
    static final IllegalStateException ORIGINAL_ERROR = new IllegalStateException("original host failure");

    public static class Target {
        final String prefix;
        public Target(String prefix) { this.prefix = prefix; }
        public String echo(String value) { return prefix + value; }
        public String explode() { throw ORIGINAL_ERROR; }
    }
    @Before public void setUp() throws Exception {
        HookBridge.resetForTests();
        nativeApi = new NativeApi101TestFramework();
        nativeApi.initialize();
        echo = Target.class.getDeclaredMethod("echo", String.class);
    }
    @After public void tearDown() { nativeApi.reset(); }

    @Test public void nativeBuilderReceivesPriorityAndPassthroughMode() {
        HookBridge.hookMethod(echo, new HookCallback(73) { });
        assertEquals(1, nativeApi.hooks.size());
        assertEquals(echo, nativeApi.hooks.get(0).getExecutable());
        assertEquals(73, nativeApi.hooks.get(0).priority);
        assertSame(XposedInterface.ExceptionMode.PASSTHROUGH, nativeApi.hooks.get(0).mode);
    }
    @Test public void beforeArgsAndReceiverReachNativeProceedAfterCanRewriteResult() throws Throwable {
        Target replacement = new Target("replacement:");
        HookBridge.hookMethod(echo, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                assertEquals(echo, param.method);
                assertSame(target, param.thisObject);
                param.thisObject = replacement;
                param.args[0] = "edited";
                param.setObjectExtra("paired", Boolean.TRUE);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                assertEquals(Boolean.TRUE, param.getObjectExtra("paired"));
                assertEquals("replacement:edited", param.getResult());
                param.setResult(param.getResult() + ":after");
            }
        });
        assertEquals("replacement:edited:after", nativeApi.call(echo, target, "original"));
        assertEquals(1, nativeApi.originalCalls);
    }
    @Test public void priorityOrderIsNestedBeforeThenReverseAfter() throws Throwable {
        List<String> order = new ArrayList<>();
        HookBridge.hookMethod(echo, callback(10, "low", order));
        HookBridge.hookMethod(echo, callback(100, "high", order));
        assertEquals("host:x", nativeApi.call(echo, target, "x"));
        assertEquals(Arrays.asList("high-before", "low-before", "low-after", "high-after"), order);
    }
    private static HookCallback callback(int priority, String name, List<String> order) {
        return new HookCallback(priority) {
            @Override protected void beforeHookedMethod(MethodHookParam param) { order.add(name + "-before"); }
            @Override protected void afterHookedMethod(MethodHookParam param) { order.add(name + "-after"); }
        };
    }
    @Test public void earlyNullResultSkipsOriginalAndLowerCallbacksButRunsPairedAfter() throws Throwable {
        AtomicInteger lower = new AtomicInteger(), after = new AtomicInteger();
        HookBridge.hookMethod(echo, new HookCallback(1) {
            @Override protected void beforeHookedMethod(MethodHookParam param) { lower.incrementAndGet(); }
        });
        HookBridge.hookMethod(echo, new HookCallback(100) {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(null); }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                assertTrue(param.isReturnEarly()); after.incrementAndGet();
            }
        });
        assertNull(nativeApi.call(echo, target, "x"));
        assertEquals(0, nativeApi.originalCalls);
        assertEquals(0, lower.get());
        assertEquals(1, after.get());
    }
    @Test public void beforeThrowableMayBeRecoveredByAfterWithoutRunningOriginal() throws Throwable {
        HookBridge.hookMethod(echo, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.setThrowable(ORIGINAL_ERROR); }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                assertSame(ORIGINAL_ERROR, param.getThrowable()); param.setResult("recovered");
            }
        });
        assertEquals("recovered", nativeApi.call(echo, target, "x"));
        assertEquals(0, nativeApi.originalCalls);
    }
    @Test public void originalExceptionPreservedWhenAfterThrowsAndMutatesOutcome() throws Throwable {
        Method explode = Target.class.getDeclaredMethod("explode");
        AtomicReference<HookCallback.MethodHookParam> observed = new AtomicReference<>();
        HookBridge.hookMethod(explode, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                observed.set(param);
                assertSame(ORIGINAL_ERROR, param.getThrowable());
                param.setResult("unsafe recovery");
                param.setObjectExtra("mutated", true);
                param.thisObject = null;
                throw new IllegalArgumentException("broken callback");
            }
        });
        Throwable actual = assertThrows(Throwable.class, () -> nativeApi.call(explode, target));
        assertSame(ORIGINAL_ERROR, actual);
        assertSame(target, observed.get().thisObject);
        assertSame(ORIGINAL_ERROR, observed.get().getThrowable());
        assertNull(observed.get().getObjectExtra("mutated"));
        assertEquals(1, nativeApi.errors.size());
    }
    @Test public void beforeErrorRestoresArgumentsReceiverExtrasAndFallsBackToOriginal() throws Throwable {
        AtomicReference<HookCallback.MethodHookParam> observed = new AtomicReference<>();
        HookBridge.hookMethod(echo, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = "unsafe"; param.thisObject = null;
                param.setResult("unsafe"); param.setObjectExtra("leak", true);
                throw new AssertionError("callback failed");
            }
            @Override protected void afterHookedMethod(MethodHookParam param) { observed.set(param); }
        });
        assertEquals("host:original", nativeApi.call(echo, target, "original"));
        assertSame(target, observed.get().thisObject);
        assertEquals("original", observed.get().args[0]);
        assertNull(observed.get().getObjectExtra("leak"));
        assertEquals(1, nativeApi.errors.size());
    }
    @Test public void afterErrorRestoresSuccessfulOriginalResult() throws Throwable {
        HookBridge.hookMethod(echo, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                param.setThrowable(ORIGINAL_ERROR); throw new AssertionError("after failed");
            }
        });
        assertEquals("host:x", nativeApi.call(echo, target, "x"));
    }
    @Test public void originalInvokerUsesOriginAndBypassesInstalledReplacement() throws Throwable {
        HookBridge.hookMethod(echo, HookReplacement.returnConstant("blocked"));
        assertEquals("blocked", nativeApi.call(echo, target, "x"));
        assertEquals("host:x", HookBridge.invokeOriginalMethod(echo, target, new Object[]{"x"}));
        assertSame(XposedInterface.Invoker.Type.ORIGIN, nativeApi.invokedType);
        assertEquals(1, nativeApi.originalCalls);
    }
    @Test public void constructorHooksUseNativeExecutableAndModifiedArguments() throws Throwable {
        Constructor<Target> constructor = Target.class.getConstructor(String.class);
        HookHelpers.findAndHookConstructor(Target.class, String.class, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                assertEquals(constructor, param.method); param.args[0] = "constructor:";
            }
        });
        Target constructed = (Target) nativeApi.call(constructor, null, "wrong:");
        assertEquals("constructor:", constructed.prefix);
        assertEquals(1, nativeApi.installCount(constructor));
    }
    @Test public void failedNativeInstallDoesNotPoisonRetryOrReportSuccess() {
        HookCallback callback = new HookCallback() { };
        long failures = HookBridge.registrationFailureCount();
        nativeApi.failNextInstall = true;
        assertThrows(IllegalStateException.class, () -> HookBridge.hookMethod(echo, callback));
        assertEquals(failures + 1, HookBridge.registrationFailureCount());
        assertEquals(0, nativeApi.hooks.size());
        HookCallback.Unhook installed = HookBridge.hookMethod(echo, callback);
        assertSame(installed, HookBridge.hookMethod(echo, callback));
        assertEquals(1, nativeApi.hooks.size());
    }
    @Test public void nativeUnhookIsIdempotentAndFailureRemainsRetryable() throws Throwable {
        HookCallback.Unhook handle = HookBridge.hookMethod(echo, HookReplacement.returnConstant("blocked"));
        nativeApi.failNextUnhook = true;
        assertThrows(IllegalStateException.class, handle::unhook);
        assertEquals("blocked", nativeApi.call(echo, target, "x"));
        handle.unhook(); handle.unhook();
        assertEquals(1, nativeApi.nativeUnhooks);
        assertEquals("host:x", nativeApi.call(echo, target, "x"));
    }
    @Test public void registrationWithoutNativeInitializationFailsClosed() {
        nativeApi.reset();
        assertThrows(IllegalStateException.class, () -> HookBridge.hookMethod(echo, new HookCallback() { }));
        assertEquals(0, nativeApi.hooks.size());
    }
    @Test public void scopedRetryKeepsIndependentFeaturesOnSameMember() throws Throwable {
        HostContext host = new HostContext("test.native.host", "test.native.host", getClass().getClassLoader(), null, true);
        List<String> callbacks = new ArrayList<>();
        registerFeature(host, "feature-a", callbacks);
        registerFeature(host, "feature-b", callbacks);
        registerFeature(host, "feature-a", callbacks); // Fresh anonymous callback, same explicit owner and source.
        assertEquals(2, nativeApi.installCount(echo));
        assertEquals("host:x", nativeApi.call(echo, target, "x"));
        assertEquals(Arrays.asList("feature-a", "feature-b"), callbacks);
    }
    private void registerFeature(HostContext host, String feature, List<String> callbacks) throws Throwable {
        Method withOwner = FeatureGuard.class.getDeclaredMethod("withRegistrationOwner", String.class, String.class, FeatureGuard.Action.class);
        withOwner.setAccessible(true);
        try {
            withOwner.invoke(null, host.packageName, feature,
                    (FeatureGuard.Action) () -> HookBridge.withRegistrationScope(host, () -> registerSharedSite(feature, callbacks)));
        } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void registerSharedSite(String feature, List<String> callbacks) {
        HookBridge.hookMethod(echo, new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { callbacks.add(feature); }
        });
    }
    @Test public void optionalMissingMethodDoesNotSignalNativeFailure() {
        long before = HookBridge.registrationFailureCount();
        assertThrows(NoSuchMethodError.class, () -> HookHelpers.findAndHookMethod(Target.class, "absent", new HookCallback() { }));
        assertEquals(before, HookBridge.registrationFailureCount());
        assertEquals(0, nativeApi.hooks.size());
    }
}
