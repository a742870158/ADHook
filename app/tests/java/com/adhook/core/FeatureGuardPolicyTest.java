package com.adhook.core;

import com.adhook.config.model.AppConfig;
import com.adhook.config.model.FeatureMeta;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class FeatureGuardPolicyTest {
    @Before @After public void reset() throws Exception {
        for (String fieldName : new String[]{"sCurrentPackage", "sPrimaryPackage", "sProcessName"}) {
            Field field = FeatureGuard.class.getDeclaredField(fieldName); field.setAccessible(true); field.set(null, null);
        }
        for (String fieldName : new String[]{"ALIASES", "PREFIX_ALIASES", "DEFAULTS", "CATALOG_PRIMARY"}) {
            Field field = FeatureGuard.class.getDeclaredField(fieldName); field.setAccessible(true); ((Map<?, ?>) field.get(null)).clear();
        }
    }

    @Test public void failingScopedOwnerRestoresTheOuterPackageAndFeature() throws Throwable {
        FeatureGuard.configureHost("com.first.app", "com.first.app", "com.shared.process", List.of());
        FeatureGuard.registerCatalog("com.second.variant", "com.second.app", List.of());
        IllegalStateException expected = new IllegalStateException("original");
        FeatureGuard.withRegistrationOwner("com.first.app", "first-feature", () -> {
            try {
                FeatureGuard.withRegistrationOwner("com.second.variant", "second-feature", () -> {
                    assertEquals("com.second.variant", FeatureGuard.currentPackage());
                    assertEquals("com.second.variant", FeatureGuard.runtimePackageFor("com.second.app"));
                    throw expected;
                });
                fail("Expected failure");
            } catch (IllegalStateException failure) {
                assertSame(expected, failure);
            }
            assertEquals("com.first.app", FeatureGuard.currentPackage());
            assertEquals("first-feature", FeatureGuard.currentRegistrationFeature());
        });
        assertEquals("com.first.app", FeatureGuard.currentPackage());
        assertNull(FeatureGuard.currentRegistrationFeature());
    }

    @Test public void actualInjectedVariantCannotBeOverwrittenByPrimaryGuard() {
        FeatureGuard.configureHost("com.variant.app", "com.primary.app", "com.variant.app:worker", List.of(
                new FeatureMeta("feature", "Feature", "", false)));
        new FeatureGuard("com.primary.app");
        assertEquals("com.variant.app", FeatureGuard.currentPackage());
        assertEquals("com.variant.app:worker", FeatureGuard.currentProcessName());
        assertEquals("com.variant.app", FeatureGuard.runtimePackageFor("com.primary.app"));
        assertEquals("com.other.app", FeatureGuard.runtimePackageFor("com.other.app"));
        assertFalse(FeatureGuard.configuredEnabled(new AppConfig("com.variant.app"), "com.primary.app", "feature", true));
    }

    @Test public void metadataAndLegacyAliasesCanBeReadInUiWithoutSettingRuntimeIdentity() {
        FeatureGuard.registerAlias("com.primary.app", "feature", "old-feature");
        FeatureGuard.registerCatalog("com.variant.app", "com.primary.app", List.of(new FeatureMeta("feature", "", "")));
        AppConfig config = new AppConfig("com.variant.app");
        config.setFeatureEnabled("old-feature", false);
        assertFalse(FeatureGuard.configuredEnabled(config, "com.variant.app", "feature", true));
        assertNull(FeatureGuard.currentPackage());
    }

    @Test public void explicitCanonicalPreferenceOverridesOldFalseEvenWhenQueriedByAlias() {
        String pkg = "com.test.host";
        FeatureGuard.registerAlias(pkg, "feature", "old-feature", "old-other");
        AppConfig config = new AppConfig(pkg);
        config.setFeatureEnabled("old-feature", false);
        assertFalse(FeatureGuard.configuredEnabled(config, pkg, "feature", true));
        config.setFeatureEnabled("feature", true);
        assertTrue(FeatureGuard.configuredEnabled(config, pkg, "old-feature", false));
        assertTrue(FeatureGuard.configuredEnabled(config, pkg, "feature", false));
        config.setFeatureEnabled("feature", false);
        config.setFeatureEnabled("old-feature", true);
        assertFalse(FeatureGuard.configuredEnabled(config, pkg, "old-feature", true));
    }

    @Test public void conflictingOldPreferencesFailClosedWithoutRewritingAnything() {
        String pkg = "com.test.host";
        FeatureGuard.registerAlias(pkg, "feature", "old-one", "old-two");
        AppConfig config = new AppConfig(pkg);
        config.setFeatureEnabled("old-one", true);
        config.setFeatureEnabled("old-two", false);
        assertFalse(FeatureGuard.configuredEnabled(config, pkg, "feature", true));
        assertEquals(2, config.getNativeFeatures().size());
        assertFalse(config.getNativeFeatures().containsKey("feature"));
    }

    @Test public void longestPrefixWinsAndDeclaredIdsAreNeverCapturedByBroadPrefixes() {
        String pkg = "com.test.host";
        FeatureGuard.registerCatalog(pkg, pkg, List.of(new FeatureMeta("feed-player", "", "")));
        FeatureGuard.registerAlias(pkg, "feed", "internal-");
        FeatureGuard.registerAlias(pkg, "player", "internal-player-");
        FeatureGuard.registerAlias(pkg, "wrong-feature", "feed-");
        assertEquals("player", FeatureGuard.canonicalFeatureId(pkg, "internal-player-banner"));
        assertEquals("feed", FeatureGuard.canonicalFeatureId(pkg, "internal-banner"));
        assertEquals("feed-player", FeatureGuard.canonicalFeatureId(pkg, "feed-player"));
        assertEquals("unmapped", FeatureGuard.canonicalFeatureId(pkg, "unmapped"));
    }

    @Test public void methodResultsUseExactSafeBoxedDefaults() {
        assertEquals(Boolean.FALSE, FeatureGuard.blockResult(boolean.class, null));
        assertEquals(0L, FeatureGuard.blockResult(long.class, null));
        assertEquals(0L, FeatureGuard.blockResult(long.class, 0));
        assertEquals(0f, FeatureGuard.blockResult(float.class, null));
        assertEquals('\0', FeatureGuard.blockResult(char.class, null));
        assertNull(FeatureGuard.blockResult(void.class, 12));
        assertNull(FeatureGuard.blockResult(String.class, null));
        assertEquals("safe", FeatureGuard.blockResult(CharSequence.class, "safe"));
        assertNotEquals(12, FeatureGuard.blockResult(boolean.class, 12));
        assertNotEquals(12, FeatureGuard.blockResult(long.class, 12));
        assertNotEquals("invalid", FeatureGuard.blockResult(List.class, "invalid"));
    }
}
