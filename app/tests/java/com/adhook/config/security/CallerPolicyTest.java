package com.adhook.config.security;

import org.junit.Test;
import java.util.Collections;
import java.util.Set;
import static org.junit.Assert.*;

public class CallerPolicyTest {
    private static final int SELF = 10000;
    private static final int HOST = 20000;

    @Test public void selfCanReadAndWriteGlobalAndAnyValidPackage() {
        CallerPolicy self = new CallerPolicy(SELF, SELF, Set.of(), Set.of());
        assertTrue(self.canReadGlobal()); assertTrue(self.canWriteGlobal());
        assertTrue(self.canAccessPackage("com.any.target"));
        assertFalse(self.canAccessPackage("../bad"));
    }

    @Test public void unknownUidAndUninstalledUidAreRejected() {
        CallerPolicy unknown = new CallerPolicy(-1, SELF, Set.of("com.host"), Set.of("com.host"));
        assertFalse(unknown.canReadGlobal()); assertFalse(unknown.canAccessPackage("com.host"));
        assertFalse(new CallerPolicy(HOST, SELF, Collections.emptySet(), Set.of("com.host")).canReadGlobal());
    }

    @Test public void merelyHavingConfigurationNeverAuthorizesHost() {
        // The policy has no AppConfig input: installation or stored rule/config presence is not a grant.
        CallerPolicy host = new CallerPolicy(HOST, SELF, Set.of("com.host"), Set.of());
        assertFalse(host.canReadGlobal()); assertFalse(host.canWriteGlobal());
        assertFalse(host.canAccessPackage("com.host"));
    }

    @Test public void authorizedHostReadsGlobalButCannotWriteGlobalOrOtherPackages() {
        CallerPolicy host = new CallerPolicy(HOST, SELF, Set.of("com.host"), Set.of("com.host", "com.other"));
        assertTrue(host.canReadGlobal()); assertFalse(host.canWriteGlobal());
        assertTrue(host.canAccessPackage("com.host")); assertFalse(host.canAccessPackage("com.other"));
    }

    @Test public void oneAuthorizedSharedUidSiblingDoesNotAuthorizeAnotherPackage() {
        CallerPolicy host = new CallerPolicy(HOST, SELF, Set.of("com.host", "com.sibling"), Set.of("com.host"));
        assertTrue(host.canAccessPackage("com.host")); assertFalse(host.canAccessPackage("com.sibling"));
    }

    @Test public void validatesPackageSegmentsAndFeatureIds() {
        for (String bad : new String[]{"com..host", ".com.host", "com.host.", "com.1host", "com.host/evil", "android"})
            assertFalse(bad, ConfigInputPolicy.isPackageName(bad));
        assertTrue(ConfigInputPolicy.isPackageName("com.host_2"));
        assertTrue(ConfigInputPolicy.isFeatureId("sdk.pangle-splash_2"));
        assertFalse(ConfigInputPolicy.isFeatureId("x\nOK"));
        assertFalse(ConfigInputPolicy.isFeatureId("x".repeat(129)));
    }

    @Test public void supportsBoundedCanonicalManagedIdsWithoutLooseningLocalIds() {
        String managed = "adhook:v2:676b64:736f75726365:636f6d2e686f7374:" + "61".repeat(180);
        assertTrue(ConfigInputPolicy.isRuleId(managed));
        assertFalse(ConfigInputPolicy.isRuleId("a".repeat(129)));
        assertFalse(ConfigInputPolicy.isRuleId(managed + ":extra"));
        assertFalse(ConfigInputPolicy.isRuleId("adhook:v2:zz:aa:aa:aa"));
        assertFalse(ConfigInputPolicy.isRuleId("adhook:v2:676b64::aa:aa"));
        assertFalse(ConfigInputPolicy.isRuleId(managed + "a"));
        assertFalse(ConfigInputPolicy.isRuleId(managed + "aa".repeat(2048)));
    }
}
