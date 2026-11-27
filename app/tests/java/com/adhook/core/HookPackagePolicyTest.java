package com.adhook.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class HookPackagePolicyTest {
    @Test public void nonFirstForeignUidLibraryIsNotAnApplicationInjection() {
        assertFalse(HookPackagePolicy.isHostApplication(false, 10123, 10456));
        assertFalse(HookPackagePolicy.isHostApplication(false, 1000, 10456));
    }

    @Test public void firstApplicationAndIsolatedProcessRemainEligible() {
        assertTrue(HookPackagePolicy.isHostApplication(true, 10123, 10123));
        assertTrue(HookPackagePolicy.isHostApplication(true, 10123, 99001));
    }

    @Test public void sharedUidApplicationAndUnknownLegacyIdentityAreNotGuessedAsLibraries() {
        assertTrue(HookPackagePolicy.isHostApplication(false, 10123, 10123));
        assertTrue(HookPackagePolicy.isHostApplication(false, null, 10123));
        assertTrue(HookPackagePolicy.isHostApplication(false, -1, 10123));
    }
}
