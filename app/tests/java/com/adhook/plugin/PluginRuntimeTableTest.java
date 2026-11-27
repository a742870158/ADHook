package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Runtime snapshot build/parse contract and size budget. */
public class PluginRuntimeTableTest {

    private static final String TARGET = "com.example.target";

    private InstalledPlugin pluginWithPrimary() {
        PluginTarget target = new PluginTarget(TARGET, "Target",
                "com.example.target.primary", List.of("com.example.target:remote"));
        return new InstalledPlugin("com.example.aa", "Plugin A", "1.0.0", 1,
                PluginConstants.PLUGIN_ABI, 0, PluginTestFixtures.ENTRY, "", "",
                List.of(target), new ArrayList<>(), "", true, 1_700_000_000_000L,
                "", "", "a".repeat(64), "b".repeat(64), "{}");
    }

    @Test public void buildsAndParsesRoutesForEveryCoveredPackage() {
        InstalledPlugin plugin = pluginWithPrimary();
        String json = PluginRuntimeTable.build(List.of(plugin), 123L);
        assertNotNull(json);
        PluginRuntimeTable table = PluginRuntimeTable.parse(json);
        assertNotNull(table);
        assertEquals(123L, table.generation());
        assertEquals(0L, table.updatedAt());
        assertEquals(2, table.packageCount());

        List<PluginRuntimeTable.Route> routes = table.routes(TARGET);
        assertEquals(1, routes.size());
        PluginRuntimeTable.Route route = routes.get(0);
        assertEquals("com.example.aa", route.pluginId);
        assertEquals(1, route.versionCode);
        assertEquals(PluginConstants.PLUGIN_ABI, route.abi);
        assertEquals("com.example.target.primary", route.primary);
        assertEquals(List.of("com.example.target:remote"), route.processes);
        assertEquals(PluginRuntimeTable.dexFileName(plugin, "b".repeat(64)), route.dexName);
        assertEquals("b".repeat(64), route.dexSha256);
        assertEquals(PluginTestFixtures.ENTRY, route.entry);
        assertEquals(1, table.routes("com.example.target.primary").size());
        assertTrue(table.routes("com.example.unknown").isEmpty());
    }

    @Test public void disabledPluginsAreExcluded() {
        InstalledPlugin enabled = PluginTestFixtures.installed("com.example.aa", 1, TARGET);
        InstalledPlugin disabled = PluginTestFixtures.installed("com.example.bb", 1,
                "com.example.disabled", "", false);
        String json = PluginRuntimeTable.build(List.of(enabled, disabled), 5L);
        assertNotNull(json);
        PluginRuntimeTable table = PluginRuntimeTable.parse(json);
        assertEquals(1, table.packageCount());
        assertEquals(1, table.routes(TARGET).size());
        assertTrue(table.routes("com.example.disabled").isEmpty());
    }

    @Test public void invalidDexHashPluginsAreSkipped() {
        InstalledPlugin broken = new InstalledPlugin("com.example.aa", "Plugin", "1.0.0", 1,
                PluginConstants.PLUGIN_ABI, 0, PluginTestFixtures.ENTRY, "", "",
                List.of(new PluginTarget(TARGET, "", "", List.of())), List.of(), "", true, 0L,
                "", "", "a".repeat(64), "", "{}");
        String json = PluginRuntimeTable.build(List.of(broken), 1L);
        assertNotNull(json);
        assertEquals(0, PluginRuntimeTable.parse(json).packageCount());
    }

    @Test public void dexFileNameIsDeterministic() {
        InstalledPlugin plugin = pluginWithPrimary();
        String dexSha = "b".repeat(64);
        String identity = PluginTestFixtures.sha256(
                plugin.getId() + plugin.getVersionCode());
        String expected = "p_" + identity.substring(0, 16) + "_" + dexSha.substring(0, 8) + ".dex";
        assertEquals(expected, PluginRuntimeTable.dexFileName(plugin, dexSha));
        assertTrue(PluginConstants.isValidDexFileName(expected));
        assertEquals("", PluginRuntimeTable.dexFileName(plugin, "not-hex"));
        assertEquals("", PluginRuntimeTable.dexFileName(null, dexSha));
    }

    @Test public void rejectsMalformedSnapshotsWholesale() throws Exception {
        JSONObject root = baseTableJson();
        assertNotNull(PluginRuntimeTable.parse(root.toString()));

        assertNull(PluginRuntimeTable.parse("not json"));
        assertNull(PluginRuntimeTable.parse(null));
        assertNull(PluginRuntimeTable.parse(
                "{\"pad\":\"" + "a".repeat(PluginConstants.MAX_REMOTE_TABLE_BYTES + 16) + "\"}"));

        assertNull(PluginRuntimeTable.parse(mutate(root, copy -> copy.put("schema", 2))));
        assertNull(PluginRuntimeTable.parse(mutate(root, copy -> copy.put("abi", 3))));
        assertNull(PluginRuntimeTable.parse(mutate(root, copy -> copy.put("generation", -1))));
        assertNull(PluginRuntimeTable.parse(mutate(root, copy -> copy.put("updated_at", -5))));
        assertNull(PluginRuntimeTable.parse(
                mutate(root, copy -> copy.put("packages", new JSONArray()))));
        assertNull(PluginRuntimeTable.parse(mutate(root, copy -> {
            JSONObject badPackages = new JSONObject();
            badPackages.put("not a package", new JSONArray());
            copy.put("packages", badPackages);
            return copy;
        })));
    }

    @Test public void rejectsMalformedRoutes() throws Exception {
        JSONObject root = baseTableJson();
        assertNull(PluginRuntimeTable.parse(
                mutateRoute(root, route -> route.put("dex", "evil.dex"))));
        assertNull(PluginRuntimeTable.parse(
                mutateRoute(root, route -> route.put("entry", "1bad"))));
        assertNull(PluginRuntimeTable.parse(
                mutateRoute(root, route -> route.put("sha256", "not-hex"))));
        assertNull(PluginRuntimeTable.parse(
                mutateRoute(root, route -> route.put("abi", 3))));
        assertNull(PluginRuntimeTable.parse(
                mutateRoute(root, route -> route.put("versionCode", 0))));
        assertNull(PluginRuntimeTable.parse(
                mutateRoute(root, route -> route.put("primary", "bad package"))));
        assertNull(PluginRuntimeTable.parse(mutateRoute(root, route -> route.put("processes",
                new JSONArray().put("bad package")))));
        assertNull(PluginRuntimeTable.parse(mutateRoute(root, route -> {
            JSONArray features = new JSONArray();
            features.put(new JSONObject().put("id", "bad feature id").put("name", "x"));
            route.put("features", features);
            return route;
        })));
        assertNull(PluginRuntimeTable.parse(mutateRoute(root,
                route -> route.put("dex", "p_0000000000000000_00000000.dex"))));
    }

    @Test public void snapshotOverBudgetIsNotPublished() {
        List<InstalledPlugin> plugins = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            plugins.add(PluginTestFixtures.installedWithFeatures("com.example.big" + i, 1,
                    "com.example.bigtarget" + i, PluginConstants.MAX_FEATURES, 400));
        }
        String json = PluginRuntimeTable.build(plugins, 1L);
        assertNull(json);
    }

    @Test public void emptyPluginListStillProducesAnEmptyButValidSnapshot() {
        String json = PluginRuntimeTable.build(List.of(), 42L);
        assertNotNull(json);
        PluginRuntimeTable table = PluginRuntimeTable.parse(json);
        assertNotNull(table);
        assertEquals(42L, table.generation());
        assertEquals(0, table.packageCount());
        assertFalse(json.getBytes(StandardCharsets.UTF_8).length > PluginConstants.MAX_REMOTE_TABLE_BYTES);
    }

    private JSONObject baseTableJson() throws Exception {
        InstalledPlugin plugin = pluginWithPrimary();
        return new JSONObject(PluginRuntimeTable.build(List.of(plugin), 123L));
    }

    private interface Mutation {
        JSONObject apply(JSONObject json) throws Exception;
    }

    private String mutate(JSONObject root, Mutation mutation) throws Exception {
        JSONObject copy = new JSONObject(root.toString());
        JSONObject mutated = mutation.apply(copy);
        return mutated != null ? mutated.toString() : null;
    }

    private interface RouteMutation {
        JSONObject apply(JSONObject route) throws Exception;
    }

    private String mutateRoute(JSONObject root, RouteMutation mutation) throws Exception {
        JSONObject copy = new JSONObject(root.toString());
        JSONArray routes = copy.getJSONObject("packages").getJSONArray(TARGET);
        routes.put(0, mutation.apply(routes.getJSONObject(0)));
        return copy.toString();
    }
}
