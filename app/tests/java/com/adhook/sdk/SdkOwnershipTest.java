package com.adhook.sdk;

import com.adhook.core.FeatureGuard;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static com.adhook.sdk.SdkAccountingTestSupport.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = {
        SdkAccountingTestSupport.Client.class, HookTestShadows.Bridge.class
})
public class SdkOwnershipTest {
    @Before public void setUp() throws Exception { reset(); }

    @Test public void featureScopedInstallAndUnscopedRetryNeverBecomeUnconditional() {
        FeatureGuard guard = new FeatureGuard(HOST);
        assertTrue(guard.run(FEATURE, () -> SdkRuntime.bind("pangle")));
        assertNull("registration scope must be restored", FeatureGuard.currentRegistrationFeature());
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
        assertEquals(FEATURE, SdkRuntime.decision("pangle").featureId());
        SdkRuntime.bind("pangle"); // A later Application/multidex retry has no guard scope.
        feature(FEATURE, false);
        assertNull(SdkRuntime.decision("pangle"));
        assertFalse(FeatureGuard.getAllHitCounts().containsKey(SdkRuntime.SHARED_FEATURE));
        feature(FEATURE, true);
        SdkRuntime.decision("pangle").hit();
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
    }

    @Test public void totalHookSwitchGatesIndependentOwnedAndNoOwnerPaths() {
        SdkRuntime.bind("independent");
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> SdkRuntime.bind("owned")));
        assertNotNull(SdkRuntime.decision("no-owner"));
        Client.apps.get(HOST).setHookEnabled(false);
        assertNull(SdkRuntime.decision("independent"));
        assertNull(SdkRuntime.decision("owned"));
        assertNull(SdkRuntime.decision("no-owner"));
        Client.apps.clear();
        assertNull(SdkRuntime.decision("independent"));
        assertNull(SdkRuntime.decision("owned"));
        assertNull(SdkRuntime.decision("no-owner"));
    }

    @Test public void disabledFeatureStillBindsAndCanActivateWithoutReinstallation() {
        feature(FEATURE, false);
        Client.apps.get(HOST).setHookEnabled(false);
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> SdkRuntime.bind("pangle")));
        assertNull(SdkRuntime.decision("pangle"));
        Client.apps.get(HOST).setHookEnabled(true);
        assertNull(SdkRuntime.decision("pangle"));
        feature(FEATURE, true);
        assertEquals(FEATURE, SdkRuntime.decision("pangle").featureId());
    }

    @Test public void originallyIndependentLayerStaysIndependentAndHasItsOwnCounter() {
        SdkRuntime.bind("gdt");
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> SdkRuntime.bind("gdt")));
        feature(FEATURE, false);
        SdkRuntime.Decision action = SdkRuntime.decision("gdt");
        assertNotNull(action);
        assertEquals(SdkRuntime.SHARED_FEATURE, action.featureId());
        action.hit();
        assertEquals(1, FeatureGuard.getHitCount(SdkRuntime.SHARED_FEATURE));
        assertEquals(0, FeatureGuard.getHitCount(FEATURE));
    }

    @Test public void multipleExplicitOwnersAccountOneEventOnlyAndAllowAnEnabledOwner() {
        FeatureGuard guard = new FeatureGuard(HOST);
        assertTrue(guard.run(FEATURE, () -> SdkRuntime.bind("kwad")));
        assertTrue(guard.run(SECOND, () -> SdkRuntime.bind("kwad")));
        SdkRuntime.decision("kwad").hit();
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        assertEquals(0, FeatureGuard.getHitCount(SECOND));
        feature(FEATURE, false);
        SdkRuntime.decision("kwad").hit();
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        assertEquals(1, FeatureGuard.getHitCount(SECOND));
        feature(SECOND, false);
        assertNull(SdkRuntime.decision("kwad"));
    }

    @Test public void capturedDecisionKeepsTheIdentityOfTheOperationThatActuallyRan() {
        assertTrue(new FeatureGuard(HOST).run(FEATURE, () -> SdkRuntime.bind("google")));
        SdkRuntime.Decision action = SdkRuntime.decision("google");
        feature(FEATURE, false); // The accepted operation already happened; a later preference must not erase it.
        action.hit();
        assertEquals(1, FeatureGuard.getHitCount(FEATURE));
        assertNull(SdkRuntime.decision("google"));
    }
}
