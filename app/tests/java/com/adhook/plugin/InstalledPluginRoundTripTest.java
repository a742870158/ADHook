package com.adhook.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Persisted plugin records must survive the packaging tool's trailing-newline manifest JSON. */
public class InstalledPluginRoundTripTest {

    private static InstalledPlugin record(String manifestJson) {
        return new InstalledPlugin("com.adhook.plugin.alpha", "测试插件", "1.0.0", 1,
                PluginConstants.PLUGIN_ABI, 0,
                "com.example.Entry", "作者", "描述",
                List.of(new PluginTarget("com.example.host", "宿主", "com.example.host", List.of())),
                List.of(), "local", true, 1L, "", "",
                "a".repeat(64), "b".repeat(64), manifestJson);
    }

    @Test public void trailingNewlineManifestSurvivesStoreRoundTrip() throws Exception {
        String manifest = "{\"schema\":1,\"id\":\"com.adhook.plugin.alpha\",\"name\":\"测试\"}\n";
        JSONObject stored = record(manifest).toJson();
        InstalledPlugin parsed = InstalledPlugin.fromJson(new JSONObject(stored.toString()));
        assertNotNull("stored manifest JSON with trailing newline must remain readable", parsed);
        assertEquals("com.adhook.plugin.alpha", parsed.getId());
        assertEquals(manifest, parsed.getManifestJson());
    }

    @Test public void malformedManifestJsonStillRejectsTheRecord() {
        InstalledPlugin parsed = InstalledPlugin.fromJson(record("not json").toJson());
        assertNull(parsed);
    }
}
