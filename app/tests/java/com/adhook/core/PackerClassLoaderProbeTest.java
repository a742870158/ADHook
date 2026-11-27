package com.adhook.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

import com.adhook.runtime.NativeApi101TestFramework;
import com.adhook.sdk.SdkHookRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.BaseDexClassLoader;

import static org.junit.Assert.*;

/** Executes production discovery callbacks through the actual native API101 recording backend. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PackerClassLoaderProbeTest {
    private NativeApi101TestFramework framework;

    @Before public void setUp() throws Exception {
        clearProbe();
        framework = new NativeApi101TestFramework();
        framework.initialize();
    }

    @After public void tearDown() throws Exception {
        framework.reset();
        clearProbe();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void clearProbe() throws Exception {
        ((Map<?, ?>) field(PackerClassLoaderProbe.class, "NOTIFICATIONS").get(null)).clear();
        ((Collection<?>) field(PackerClassLoaderProbe.class, "PENDING_TASKS").get(null)).clear();
        Object registry = field(PackerClassLoaderProbe.class, "HOOKS").get(null);
        ((Map<?, ?>) field(SdkHookRegistry.class, "hooks").get(registry)).clear();
        field(PackerClassLoaderProbe.class, "installingObservers").setBoolean(null, false);
    }

    private static int pending() throws Exception {
        return ((Collection<?>) field(PackerClassLoaderProbe.class, "PENDING_TASKS").get(null)).size();
    }

    @Test public void failedObserverAndConstructorRetryWithoutDuplicatingSuccessfulMembers() throws Throwable {
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        Method applicationCreate = Application.class.getDeclaredMethod("onCreate");
        Method activityCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        Constructor<?> constructor = BaseDexClassLoader.class.getDeclaredConstructors()[0];
        framework.failOnce(attach);
        framework.failOnce(constructor);
        LateLoader loader = new LateLoader();
        AtomicInteger calls = new AtomicInteger();
        PackerClassLoaderProbe.Callback callback = ignored -> calls.incrementAndGet();
        PackerClassLoaderProbe.watch(loader, Marker.class.getName(), callback);
        assertEquals(0, framework.installCount(attach));
        assertEquals(0, framework.installCount(constructor));
        assertEquals(1, framework.installCount(applicationCreate));
        assertEquals(1, framework.installCount(activityCreate));
        assertEquals(1, pending());
        // A working lifecycle observer repairs only its failed siblings while the marker is absent.
        framework.call(applicationCreate, new ReadyApplication(loader));
        assertEquals(1, framework.installCount(attach));
        assertEquals(1, framework.installCount(constructor));
        assertEquals(1, framework.installCount(applicationCreate));
        assertEquals(1, framework.installCount(activityCreate));
        assertEquals(0, calls.get());
        loader.available = true;
        framework.call(applicationCreate, new ReadyApplication(loader));
        assertEquals(1, calls.get());
        assertEquals(0, pending());
        PackerClassLoaderProbe.watch(loader, Marker.class.getName(), callback);
        assertEquals(1, calls.get());
    }

    @Test public void throwingCallbackIsRetriedByTheInstalledLifecycleAndOnlyThenDeduplicated() throws Throwable {
        LateLoader loader = new LateLoader();
        loader.available = true;
        AtomicInteger attempts = new AtomicInteger();
        PackerClassLoaderProbe.Callback callback = ignored -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("feature backend not ready");
        };
        PackerClassLoaderProbe.watch(loader, Marker.class.getName(), callback);
        assertEquals(1, attempts.get());
        assertEquals(1, pending());
        framework.call(Application.class.getDeclaredMethod("onCreate"), new ReadyApplication(loader));
        assertEquals(2, attempts.get());
        assertEquals(0, pending());
        PackerClassLoaderProbe.watch(loader, Marker.class.getName(), callback);
        framework.call(Application.class.getDeclaredMethod("onCreate"), new ReadyApplication(loader));
        assertEquals(2, attempts.get());
    }

    @Test public void independentCallbacksForTheSameMarkerAreBothDelivered() {
        ClassLoader loader = Marker.class.getClassLoader();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        PackerClassLoaderProbe.watch(loader, Marker.class.getName(), ignored -> first.incrementAndGet());
        PackerClassLoaderProbe.watch(loader, Marker.class.getName(), ignored -> second.incrementAndGet());
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test public void concurrentWatchCannotRunAnInFlightCallbackTwiceOrLeaveItsCompletedTaskPending() throws Throwable {
        ClassLoader loader = Marker.class.getClassLoader();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        PackerClassLoaderProbe.Callback callback = ignored -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5L, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        };
        try {
            java.util.concurrent.Future<?> first = worker.submit(() -> PackerClassLoaderProbe.watch(loader, Marker.class.getName(), callback));
            assertTrue(entered.await(5L, TimeUnit.SECONDS));
            PackerClassLoaderProbe.watch(loader, Marker.class.getName(), callback);
            release.countDown();
            first.get(5L, TimeUnit.SECONDS);
            framework.call(Application.class.getDeclaredMethod("onCreate"), new ReadyApplication(loader));
            assertEquals(1, calls.get());
            assertEquals(0, pending());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5L, TimeUnit.SECONDS));
        }
    }

    public static class Marker { }
    private static final class LateLoader extends ClassLoader {
        volatile boolean available;
        LateLoader() { super(Marker.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Marker.class.getName()) && !available) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }
    private static final class ReadyApplication extends Application {
        private final ClassLoader loader;
        ReadyApplication(ClassLoader loader) { this.loader = loader; }
        @Override public ClassLoader getClassLoader() { return loader; }
        @Override public void onCreate() { }
    }
}
