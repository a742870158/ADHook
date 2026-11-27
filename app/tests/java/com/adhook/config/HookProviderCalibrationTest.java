package com.adhook.config;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class HookProviderCalibrationTest {
    @Test public void healthyCalibrationHasOneRequestPerThirtySecondsAndRotatesPackages() {
        HookProviderCalibration budget = new HookProviderCalibration();
        assertEquals("com.a.app", budget.take(0L, Arrays.asList("com.b.app", "com.a.app")));
        assertNull(budget.take(1L, Arrays.asList("com.b.app", "com.a.app")));
        budget.completed(0L, true);
        assertNull(budget.take(29999L, Arrays.asList("com.b.app", "com.a.app")));
        assertEquals("com.b.app", budget.take(30000L, Arrays.asList("com.b.app", "com.a.app")));
        budget.completed(30000L, true);
        assertEquals("com.a.app", budget.take(60000L, Arrays.asList("com.b.app", "com.a.app")));
    }

    @Test public void deniedOrUnavailableProviderBacksOffAndNeverResetsOnAnExtraNotification() {
        HookProviderCalibration budget = new HookProviderCalibration();
        assertEquals("com.a.app", budget.take(0L, Collections.singleton("com.a.app")));
        budget.completed(0L, false);
        assertNull(budget.take(59999L, Collections.singleton("com.a.app")));
        assertEquals("com.a.app", budget.take(60000L, Collections.singleton("com.a.app")));
        budget.completed(60000L, false);
        assertNull(budget.take(179999L, Collections.singleton("com.a.app")));
        assertEquals("com.a.app", budget.take(180000L, Collections.singleton("com.a.app")));
        budget.completed(180000L, false);
        assertEquals("com.a.app", budget.take(420000L, Collections.singleton("com.a.app")));
        budget.completed(420000L, false);
        assertNull(budget.take(719999L, Collections.singleton("com.a.app")));
        assertEquals("com.a.app", budget.take(720000L, Collections.singleton("com.a.app")));
        budget.completed(720000L, true);
        assertEquals("com.a.app", budget.take(750000L, Collections.singleton("com.a.app")));
    }
}
