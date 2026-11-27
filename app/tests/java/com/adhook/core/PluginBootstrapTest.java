package com.adhook.core;

import com.adhook.config.model.FeatureMeta;
import com.adhook.plugin.InstalledPlugin;
import com.adhook.plugin.PluginConstants;
import com.adhook.plugin.PluginFeature;
import com.adhook.plugin.PluginRuntimeTable;
import com.adhook.plugin.PluginTarget;
import com.adhook.runtime.HostContext;

import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure routing/eligibility contracts for host-side plugin loading (no framework Binder). */
public class PluginBootstrapTest {

    private static final String DEX_SHA = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @After public void reset() {
        PluginBootstrap.resetForTests();
    }

    private static InstalledPlugin plugin(String packageName, List<String> processes) {
        PluginTarget target = new PluginTarget(packageName, "测试宿主", packageName, processes);
        return new InstalledPlugin("com.adhook.plugin.alpha", "测试插件", "1.0.0", 12, PluginConstants.PLUGIN_ABI, 1,
                "com.example.PluginEntry", "作者", "描述", List.of(target),
                List.of(new PluginFeature("alpha_feature", "特性", "说明", true, true)),
                "local", true, 1L, "0011223344556677", "fingerprint",
                "b".repeat(64), DEX_SHA, "{}");
    }

    private static PluginRuntimeTable table(InstalledPlugin plugin) {
        String json = PluginRuntimeTable.build(List.of(plugin), 1L);
        assertNotNull(json);
        PluginRuntimeTable parsed = PluginRuntimeTable.parse(json);
        assertNotNull(parsed);
        return parsed;
    }

    @Test public void selectsInstalledRouteForTheTargetPackage() {
        PluginRuntimeTable table = table(plugin("com.example.host", List.of()));
        PluginRuntimeTable.Route route = PluginBootstrap.selectRoute(table, "com.example.host",
                "com.example.host", 1);
        assertNotNull(route);
        assertEquals("com.adhook.plugin.alpha", route.pluginId);
        assertNull(PluginBootstrap.selectRoute(table, "com.example.other", "com.example.other", 1));
        assertNull(PluginBootstrap.selectRoute(null, "com.example.host", "com.example.host", 1));
    }

    @Test public void processAllowListFiltersOtherProcesses() {
        PluginRuntimeTable table = table(plugin("com.example.host", List.of("com.example.host:main")));
        assertNotNull(PluginBootstrap.selectRoute(table, "com.example.host", "com.example.host:main", 1));
        assertNull(PluginBootstrap.selectRoute(table, "com.example.host", "com.example.host:push", 1));
        assertTrue(PluginBootstrap.processAllowed(
                table.routes("com.example.host").get(0), "com.example.host", "com.example.host:main"));
        assertFalse(PluginBootstrap.processAllowed(
                table.routes("com.example.host").get(0), "com.example.host", "com.example.host:push"));
    }

    @Test public void abiAndBaseVersionAreEnforcedBeforeLoading() {
        PluginRuntimeTable.Route current = new PluginRuntimeTable.Route("com.adhook.plugin.alpha", "测试",
                12, "com.example.PluginEntry", "p_0011223344556677_a1b2c3d4.dex", DEX_SHA, PluginConstants.PLUGIN_ABI, 100,
                "com.example.host", List.of(), List.of());
        assertFalse(PluginBootstrap.eligible(current, 99));
        assertEquals("BASE_TOO_OLD", PluginBootstrap.unavailableReason());
        assertTrue(PluginBootstrap.eligible(current, 100));

        PluginRuntimeTable.Route wrongAbi = new PluginRuntimeTable.Route("com.adhook.plugin.alpha", "测试",
                12, "com.example.PluginEntry", "p_0011223344556677_a1b2c3d4.dex", DEX_SHA, 99, 1,
                "com.example.host", List.of(), List.of());
        assertFalse(PluginBootstrap.eligible(wrongAbi, Integer.MAX_VALUE));
        assertEquals("PLUGIN_ABI_MISMATCH", PluginBootstrap.unavailableReason());
    }

    @Test public void manifestFeaturesRemainTheRegistrationSource() {
        PluginRuntimeTable table = table(plugin("com.example.host", List.of()));
        List<FeatureMeta> features = PluginBootstrap.featureMetas(table.routes("com.example.host").get(0));
        assertEquals(1, features.size());
        assertEquals("alpha_feature", features.get(0).getId());
        assertEquals("特性", features.get(0).getName());
        assertTrue(features.get(0).isDefaultEnabled());
        assertEquals("com.example.host", PluginBootstrap.primaryOf(
                table.routes("com.example.host").get(0), "fallback"));
    }

    @Test public void missingRemoteModuleNeverLoadsAndNeverThrows() {
        PluginRuntimeTable table = table(plugin("com.example.host", List.of()));
        PluginRuntimeTable.Route route = table.routes("com.example.host").get(0);
        HostContext host = new HostContext("com.example.host", "com.example.host", null, null, true);
        assertEquals(PluginBootstrap.LoadResult.FAILED_BEFORE_ENTRY,
                PluginBootstrap.load(route, host, "com.example.host"));
        assertEquals(PluginBootstrap.LoadResult.UNELIGIBLE,
                PluginBootstrap.load(null, host, "com.example.host"));
    }

    @Test public void sha256HelperMatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PluginBootstrap.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PluginBootstrap.sha256Hex(new byte[0]));
    }
}
