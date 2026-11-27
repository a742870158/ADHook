package com.adhook.plugin;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Per-source market index cache regressions. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class PluginMarketCacheTest {

    private PluginMarketCache cache;
    private File pluginsDirectory;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginFiles.deleteRecursively(pluginsDirectory);
        cache = new PluginMarketCache(pluginsDirectory);
    }

    @Test public void writesReadsAndClearsPerSource() {
        String index = "{\"schema\":1,\"name\":\"m\",\"entries\":[]}";
        assertTrue(cache.write(7L, index));
        assertEquals(index, cache.read(7L));
        assertNull(cache.read(8L));
        assertTrue(cache.clear(7L));
        assertNull(cache.read(7L));
        assertTrue(cache.clear(7L));
    }

    @Test public void rejectsInvalidSourceIdsAndOversizedPayloads() {
        assertFalse(cache.write(0L, "{}"));
        assertFalse(cache.write(-1L, "{}"));
        assertFalse(cache.write(1L, null));
        assertFalse(cache.write(1L, "{\"pad\":\"" + "a".repeat(PluginConstants.MAX_INDEX_BYTES + 1)
                + "\"}"));
        assertNull(cache.read(0L));
    }

    @Test public void corruptCacheIsReportedAsAbsentAndLeftOnDisk() throws Exception {
        File file = cache.file(3L);
        assertNotNull(file);
        PluginFiles.ensureDirectory(file.getParentFile());
        PluginFiles.writeAtomically(file, "not json".getBytes(StandardCharsets.UTF_8));
        assertNull(cache.read(3L));
        assertTrue(file.isFile());
    }

    @Test public void atomicWriteReplacesPreviousIndex() {
        assertTrue(cache.write(1L, "{\"a\":1}"));
        assertTrue(cache.write(1L, "{\"a\":2}"));
        assertEquals("{\"a\":2}", cache.read(1L));
    }
}
