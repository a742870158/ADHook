package com.adhook.sdk;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Pure contract tests; these do not install Xposed hooks or claim device validation. */
public class PanglePublicContractTest {

    @Test public void publicFacadeAndFactoryResolveToOneConcreteImplementation() throws Exception {
        Method getManager = PangleBlocker.findUniqueGetAdManagerMethod(SdkFacade.class);
        assertNotNull(getManager);
        assertEquals(ManagerApi.class, getManager.getReturnType());

        Method create = PangleBlocker.findUniqueCreateAdNativeContractMethod(ManagerApi.class, NativeApi.class);
        assertNotNull(create);
        Method implementation = PangleBlocker.findUniqueImplementationMethod(ManagerImpl.class, create);
        assertNotNull(implementation);
        assertEquals(ManagerImpl.class, implementation.getDeclaringClass());
    }

    @Test public void broadOrIncompleteContractsFailOpen() throws Exception {
        assertNull(PangleBlocker.findUniqueGetAdManagerMethod(BroadSdkFacade.class));
        assertNull(PangleBlocker.findUniqueCreateAdNativeContractMethod(BroadManagerApi.class, NativeApi.class));

        Method load = NativeApi.class.getMethod("loadAd", Listener.class);
        assertNull(PangleBlocker.findUniqueImplementationMethod(DefaultOnlyNative.class, load));
    }

    @Test public void onlyLoadMethodsDeclaredByPublicNativeContractAreBlocked() throws Exception {
        Method load = NativeApi.class.getMethod("loadAd", Listener.class);
        Method regular = NativeApi.class.getMethod("configuration");
        assertTrue(PangleBlocker.isLoadContractMethod(NativeApi.class, load));
        assertFalse(PangleBlocker.isLoadContractMethod(NativeApi.class, regular));
        assertFalse(PangleBlocker.isLoadContractMethod(NativeApi.class,
                OtherApi.class.getMethod("loadSomething", Listener.class)));
    }

    public interface Listener { void onError(int code, String message); }

    public interface NativeApi {
        void loadAd(Listener listener);
        String configuration();
    }

    public interface ManagerApi { NativeApi createAdNative(Object context); }
    public interface BroadManagerApi { Object createAdNative(Object context); }

    public static final class SdkFacade {
        public static ManagerApi getAdManager() { return null; }
    }

    public static final class BroadSdkFacade {
        public static Object getAdManager() { return null; }
    }

    public static final class ManagerImpl implements ManagerApi {
        @Override public NativeApi createAdNative(Object context) { return null; }
    }

    public interface DefaultOnlyNative extends NativeApi {
        @Override default void loadAd(Listener listener) { }
        @Override default String configuration() { return "default"; }
    }

    public interface OtherApi { void loadSomething(Listener listener); }
}
