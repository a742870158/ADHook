package com.adhook.sdk;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SdkHookRegistryTest {
    @Test public void exceptionDoesNotMarkMethodInstalled() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        Method method = LateSdk.class.getDeclaredMethod("loadAd");
        AtomicInteger attempts = new AtomicInteger();
        try {
            registry.install(method, () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("backend not ready");
            });
            fail("the failed installation should be reported");
        } catch (IllegalStateException expected) {
            assertEquals("backend not ready", expected.getMessage());
        }
        assertTrue(registry.install(method, () -> { attempts.incrementAndGet(); return true; }));
        assertFalse(registry.install(method, () -> { attempts.incrementAndGet(); return true; }));
        assertEquals(2, attempts.get());
    }

    @Test public void missingUnhookHandleIsNotSuccess() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        Method method = LateSdk.class.getDeclaredMethod("loadAd");
        assertFalse(registry.install(method, () -> false));
        assertTrue(registry.install(method, () -> true));
        assertFalse(registry.install(method, () -> true));
    }

    @Test public void partialSuccessDoesNotPreventOtherMethodsFromRetrying() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        Method first = LateSdk.class.getDeclaredMethod("loadAd");
        Method later = LateSdk.class.getDeclaredMethod("loadAd", int.class);
        assertTrue(registry.install(first, () -> true));
        assertFalse(registry.install(later, () -> false));
        assertFalse(registry.install(first, () -> { fail("must not rehook successful method"); return true; }));
        assertTrue(registry.install(later, () -> true));
        assertFalse(registry.install(later, () -> true));
    }

    @Test public void equalReflectiveMembersAndConstructorsAreDeduplicated() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        assertTrue(registry.install(LateSdk.class.getDeclaredMethod("loadAd"), () -> true));
        assertFalse(registry.install(LateSdk.class.getDeclaredMethod("loadAd"), () -> true));
        assertTrue(registry.install(LateSdk.class.getDeclaredConstructor(), () -> true));
        assertFalse(registry.install(LateSdk.class.getDeclaredConstructor(), () -> true));
    }

    @Test public void bridgeReturnTypesRemainDistinct() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        int installed = 0;
        for (Method method : StringSupplier.class.getDeclaredMethods()) {
            if ("get".equals(method.getName()) && registry.install(method, () -> true)) {
                installed++;
            }
        }
        assertEquals("bridge Object get() and String get() are different JVM methods", 2, installed);
    }

    @Test public void reentrantAttemptCannotInstallTheSameMethodTwice() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        Method method = LateSdk.class.getDeclaredMethod("loadAd");
        assertTrue(registry.install(method, () -> {
            assertFalse(registry.install(method, () -> { fail("reentrant hook"); return true; }));
            return true;
        }));
    }

    @Test public void concurrentAttemptsInstallOnlyOnce() throws Exception {
        SdkHookRegistry registry = new SdkHookRegistry();
        Method method = LateSdk.class.getDeclaredMethod("loadAd");
        AtomicInteger installs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> task = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    return registry.install(method, () -> { installs.incrementAndGet(); return true; });
                } catch (Throwable t) {
                    throw new AssertionError(t);
                }
            };
            Future<Boolean> first = pool.submit(task);
            Future<Boolean> second = pool.submit(task);
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, installs.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test public void sameLoaderCanDiscoverLaterClassesAndInstallOnlyOnce() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        LateLoader loader = new LateLoader();
        AtomicInteger installs = new AtomicInteger();
        assertFalse(discoverAndInstall(registry, loader, installs));
        loader.available = true;
        assertTrue(discoverAndInstall(registry, loader, installs));
        assertFalse(discoverAndInstall(registry, loader, installs));
        assertEquals(1, installs.get());
    }

    @Test public void identicallyNamedMethodsInDifferentLoadersDoNotCollide() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        LateLoader first = new LateLoader();
        LateLoader second = new LateLoader();
        first.available = second.available = true;
        AtomicInteger installs = new AtomicInteger();
        assertTrue(discoverAndInstall(registry, first, installs));
        assertTrue(discoverAndInstall(registry, second, installs));
        assertEquals(2, installs.get());
    }

    @Test public void abstractMethodsAreNotReportedAsSuccessfulHooks() throws Throwable {
        SdkHookRegistry registry = new SdkHookRegistry();
        assertFalse(registry.install(Supplier.class.getDeclaredMethod("get"),
                () -> { fail("abstract method must not reach Xposed"); return true; }));
    }

    private static boolean discoverAndInstall(SdkHookRegistry registry, ClassLoader loader,
                                              AtomicInteger installs) throws Throwable {
        Class<?> type;
        try {
            type = Class.forName(LateSdk.class.getName(), false, loader);
        } catch (ClassNotFoundException expected) {
            return false;
        }
        return registry.install(type.getDeclaredMethod("loadAd"),
                () -> { installs.incrementAndGet(); return true; });
    }

    public static class LateSdk {
        public void loadAd() { }
        public void loadAd(int count) { }
    }

    public interface Supplier<T> { T get(); }
    public static class StringSupplier implements Supplier<String> {
        @Override public String get() { return "value"; }
    }

    /** A real ClassLoader that initially cannot resolve a class, then exposes it without being replaced. */
    private static final class LateLoader extends ClassLoader {
        boolean available;
        private final byte[] bytes;

        LateLoader() throws Exception {
            super(SdkHookRegistryTest.class.getClassLoader());
            String resource = "/" + LateSdk.class.getName().replace('.', '/') + ".class";
            try (InputStream input = LateSdk.class.getResourceAsStream(resource)) {
                assertNotNull(input);
                bytes = input.readAllBytes();
            }
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(LateSdk.class.getName())) {
                return super.loadClass(name, resolve);
            }
            if (!available) {
                throw new ClassNotFoundException(name);
            }
            synchronized (this) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
