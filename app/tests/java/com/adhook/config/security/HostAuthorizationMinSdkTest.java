package com.adhook.config.security;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Default host authorization still resolves real package/UID/signing metadata on Android 35. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = HostAuthorizationMinSdkTest.CertificateMetadata.class)
public class HostAuthorizationMinSdkTest {
    private static final String PKG = "com.min.sdk.authfixture";
    private static final int UID = 22345;
    @Implements(SigningInfo.class)
    public static class CertificateMetadata {
        static Signature[] current;
        @Implementation protected Signature[] getApkContentsSigners() { return current; }
    }
    private Context context;
    private PackageInfo info;
    private HostAuthorizationStore store;
    private static Signature certificate(int value) { return new Signature(new byte[]{(byte) value, 2, 3, 4}); }
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        info = new PackageInfo(); info.packageName = PKG; info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = PKG; info.applicationInfo.uid = UID;
        info.signatures = new Signature[]{certificate(1)}; info.signingInfo = null;
        CertificateMetadata.current = null;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        store = new HostAuthorizationStore(context);
    }

    @Test public void installedOwnedPackageIsAuthorizedByDefault() {
        assertTrue(store.isAuthorized(PKG));
        assertTrue(store.isAuthorized(PKG, UID));
        assertFalse(store.isAuthorized(PKG, UID + 1));
        assertFalse(store.isAuthorized("com.min.sdk.missing", UID));
    }

    @Test public void modernCurrentSignersStillResolveTheInstalledPackage() {
        info.signingInfo = ReflectionHelpers.callConstructor(SigningInfo.class);
        CertificateMetadata.current = new Signature[]{certificate(5)};
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        assertTrue(store.isAuthorized(PKG, UID));
        assertFalse(store.isAuthorized(PKG, UID + 1));
    }

    @Test public void missingSignatureMetadataRemainsFailClosed() {
        info.signatures = null;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        assertFalse(store.isAuthorized(PKG));
        assertFalse(store.isAuthorized(PKG, UID));
    }
}
