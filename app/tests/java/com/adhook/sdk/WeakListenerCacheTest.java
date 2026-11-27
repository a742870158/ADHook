package com.adhook.sdk;

import org.junit.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

public class WeakListenerCacheTest {
    @Test public void allFourBlockersUseWeakValuesRatherThanStrongListeners() throws Exception {
        Class<?>[] blockers = {GdtBlocker.class, BaiduMobadsBlocker.class, SigmobBlocker.class, TopOnBlocker.class};
        for (Class<?> blocker : blockers) {
            WeakListenerCache cache = cacheOf(blocker);
            Object ad = new Object();
            Object listener = new Object();
            cache.put(ad, listener);
            Object stored = entries(cache).get(ad);
            assertTrue(blocker.getSimpleName(), stored instanceof WeakReference<?>);
            assertSame(listener, ((WeakReference<?>) stored).get());
            assertSame(listener, cache.get(ad));
            cache.put(ad, null);
        }
    }

    @Test public void staticCachesDoNotRootListenerActivityAdCycles() throws Exception {
        for (Class<?> blocker : new Class<?>[]{GdtBlocker.class, BaiduMobadsBlocker.class,
                SigmobBlocker.class, TopOnBlocker.class}) {
            WeakListenerCache cache = cacheOf(blocker);
            WeakReference<?>[] cycle = putDetachedCycle(cache);
            for (int attempt = 0; attempt < 30 && cycle[0].get() != null; attempt++) {
                System.gc();
                System.runFinalization();
            }
            assertNull(blocker.getSimpleName() + " must not root listener", cycle[0].get());
            assertNull(blocker.getSimpleName() + " must not root Activity owner", cycle[1].get());
            assertNull(blocker.getSimpleName() + " must not root ad", cycle[2].get());
            Reference.reachabilityFence(cache);
        }
    }

    @Test public void expiredWeakEntryIsRemovedAndLiveSdkFieldStillSuppliesFailureCallback() throws Exception {
        WeakListenerCache cache = new WeakListenerCache();
        ActivityOwner owner = new ActivityOwner();
        AdHolder ad = new AdHolder();
        owner.ad = ad;
        NoAdListener listener = new NoAdListener(owner);
        ad.listener = listener;
        cache.put(ad, listener);
        // Deterministically simulate a cleared index without depending on a GC race.
        entries(cache).get(ad).clear();
        assertNull(cache.get(ad));
        assertFalse(entries(cache).containsKey(ad));
        assertTrue(AdCallbackHelper.notifyFieldCallbacks(ad, -1, getClass().getClassLoader()));
        assertEquals(1, listener.calls);
        assertEquals(-1, listener.errorCode);
        assertEquals(AdCallbackHelper.REASON, listener.reason);
    }

    @Test public void aLiveListenerCanBeLookedUpForRepeatedFailureRequests() {
        WeakListenerCache cache = new WeakListenerCache();
        ActivityOwner owner = new ActivityOwner();
        AdHolder ad = new AdHolder();
        owner.ad = ad;
        ad.listener = new NoAdListener(owner);
        cache.put(ad, ad.listener);
        for (int i = 0; i < 2; i++) {
            Object listener = cache.get(ad);
            assertNotNull(listener);
            assertTrue(AdCallbackHelper.notifyFailure(listener, 4001, getClass().getClassLoader()));
        }
        assertEquals(2, ad.listener.calls);
        assertEquals(4001, ad.listener.errorCode);
        Reference.reachabilityFence(ad);
    }

    @Test public void listenerReplacementAndNullInputsAreSafe() {
        WeakListenerCache cache = new WeakListenerCache();
        Object ad = new Object();
        Object first = new Object();
        Object next = new Object();
        cache.put(null, first);
        assertNull(cache.get(null));
        cache.put(ad, first);
        cache.put(ad, next);
        assertSame(next, cache.get(ad));
        cache.put(ad, null);
        assertNull(cache.get(ad));
    }

    private static WeakListenerCache cacheOf(Class<?> blocker) throws Exception {
        Field field = blocker.getDeclaredField(blocker == GdtBlocker.class ? "LISTENERS" : "SPLASH_LISTENERS");
        field.setAccessible(true);
        return (WeakListenerCache) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, WeakReference<Object>> entries(WeakListenerCache cache) throws Exception {
        Field field = WeakListenerCache.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return (Map<Object, WeakReference<Object>>) field.get(cache);
    }

    private static WeakReference<?>[] putDetachedCycle(WeakListenerCache cache) {
        ActivityOwner owner = new ActivityOwner();
        AdHolder ad = new AdHolder();
        owner.ad = ad;
        NoAdListener listener = new NoAdListener(owner);
        ad.listener = listener;
        cache.put(ad, listener);
        return new WeakReference<?>[]{new WeakReference<>(listener), new WeakReference<>(owner), new WeakReference<>(ad)};
    }

    private static final class ActivityOwner {
        AdHolder ad;
    }

    private static final class AdHolder {
        NoAdListener listener;
    }

    private static final class NoAdListener {
        final ActivityOwner owner;
        int calls;
        int errorCode;
        String reason;

        NoAdListener(ActivityOwner owner) { this.owner = owner; }

        public void onNoAD(int code, String message) {
            calls++;
            errorCode = code;
            reason = message;
        }
    }
}
