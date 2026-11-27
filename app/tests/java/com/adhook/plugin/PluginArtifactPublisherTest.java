package com.adhook.plugin;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Publisher planning/ordering regressions. The Xposed binding itself is not instantiated here;
 * the publisher's service contract is exercised through the internal {@code RemoteService} seam.
 */
public class PluginArtifactPublisherTest {

    private static final String TARGET = "com.example.target";

    @Test public void generationIsStrictlyIncreasingAndWallClockBound() {
        long now = System.currentTimeMillis();
        long first = PluginArtifactPublisher.nextGeneration(0L);
        long second = PluginArtifactPublisher.nextGeneration(first);
        assertTrue(first >= now);
        assertTrue(second > first);
        assertEquals(first + 1L, second);
        assertEquals(Long.MAX_VALUE, PluginArtifactPublisher.nextGeneration(Long.MAX_VALUE));
    }

    @Test public void retryBudgetIsFourAttemptsWithOneThreeTenSecondDelays() {
        assertEquals(1_000L, PluginArtifactPublisher.retryDelayAfterAttempt(1));
        assertEquals(3_000L, PluginArtifactPublisher.retryDelayAfterAttempt(2));
        assertEquals(10_000L, PluginArtifactPublisher.retryDelayAfterAttempt(3));
        assertEquals(-1L, PluginArtifactPublisher.retryDelayAfterAttempt(4));
        assertEquals(-1L, PluginArtifactPublisher.retryDelayAfterAttempt(0));
    }

    @Test public void obsoleteRemoteFilesOnlyMatchOwnDexNamingScheme() {
        String current = "p_aaaaaaaaaaaaaaaa_bbbbbbbb.dex";
        Set<String> currentNames = new LinkedHashSet<>(List.of(current));
        String[] remote = {
                current,
                "p_cccccccccccccccc_dddddddd.dex",
                "unrelated.txt",
                "p_bad.dex",
                null
        };
        Set<String> obsolete = PluginArtifactPublisher.obsoleteDexNames(currentNames, remote);
        assertEquals(Set.of("p_cccccccccccccccc_dddddddd.dex"), obsolete);
        assertTrue(PluginArtifactPublisher.obsoleteDexNames(currentNames, null).isEmpty());
    }

    @Test public void stateTransitionOnlyAcceptsTheDesiredGeneration() {
        PluginArtifactPublisher.State previous = new PluginArtifactPublisher.State(5L, 0L, "PENDING", 0,
                false);
        PluginArtifactPublisher.State failed = PluginArtifactPublisher.applyResult(previous, 5L,
                "COMMIT_FAILED", 2, true);
        assertEquals(0L, failed.publishedGeneration);
        assertEquals("COMMIT_FAILED", failed.error);
        assertEquals(2, failed.attempts);
        assertTrue(failed.serviceAvailable);

        PluginArtifactPublisher.State success = PluginArtifactPublisher.applyResult(previous, 5L,
                null, 1, true);
        assertEquals(5L, success.publishedGeneration);
        assertNull(success.error);
        assertTrue(success.isConfirmed());

        assertEquals(previous, PluginArtifactPublisher.applyResult(previous, 4L, null, 1, true));
    }

    @Test public void payloadContainsDeterministicDexNamesAndRoutes() {
        InstalledPlugin plugin = PluginTestFixtures.installed("com.example.aa", 1, TARGET);
        byte[] dex = PluginTestFixtures.sampleDex("com.example.aa:1");
        String expectedSha = PluginTestFixtures.sha256(dex);
        String expectedName = PluginRuntimeTable.dexFileName(plugin, expectedSha);

        PluginArtifactPublisher.PublishPayload payload = PluginArtifactPublisher.preparePayloadWith(
                List.of(plugin), 99L, ignored -> dex);
        assertNull(payload.error);
        assertNotNull(payload.table);
        assertEquals(1, payload.dexFiles.size());
        assertArrayEquals(dex, payload.dexFiles.get(expectedName));

        PluginRuntimeTable table = PluginRuntimeTable.parse(payload.table);
        assertNotNull(table);
        assertEquals(99L, table.generation());
        PluginRuntimeTable.Route route = table.routes(TARGET).get(0);
        assertEquals(expectedName, route.dexName);
        assertEquals(expectedSha, route.dexSha256);
    }

    @Test public void payloadFailsClosedOnMissingOrChangedDex() {
        InstalledPlugin plugin = PluginTestFixtures.installed("com.example.aa", 1, TARGET);
        assertEquals("MISSING_DEX", PluginArtifactPublisher.preparePayloadWith(List.of(plugin), 1L,
                ignored -> null).error);
        assertEquals("MISSING_DEX", PluginArtifactPublisher.preparePayloadWith(List.of(plugin), 1L,
                ignored -> new byte[0]).error);
        IOException failure = new IOException("gone");
        assertEquals("MISSING_DEX", PluginArtifactPublisher.preparePayloadWith(List.of(plugin), 1L,
                ignored -> {
                    throw failure;
                }).error);

        InstalledPlugin changed = PluginTestFixtures.installed("com.example.aa", 1, TARGET,
                "a".repeat(64), true);
        assertEquals("DEX_HASH_MISMATCH", PluginArtifactPublisher.preparePayloadWith(
                List.of(changed), 1L, ignored -> PluginTestFixtures.sampleDex("com.example.aa:1")).error);
    }

    @Test public void payloadSkipsDisabledPluginsAndFailsWhenTableIsTooLarge() {
        InstalledPlugin disabled = PluginTestFixtures.installed("com.example.aa", 1, TARGET,
                "", false);
        PluginArtifactPublisher.PublishPayload empty = PluginArtifactPublisher.preparePayloadWith(
                List.of(disabled), 1L, ignored -> PluginTestFixtures.sampleDex("x"));
        assertNull(empty.error);
        assertTrue(empty.dexFiles.isEmpty());
        assertNotNull(PluginRuntimeTable.parse(empty.table));

        List<InstalledPlugin> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(PluginTestFixtures.installedWithFeatures("com.example.big" + i, 1,
                    "com.example.bigtarget" + i, PluginConstants.MAX_FEATURES, 400));
        }
        PluginArtifactPublisher.PublishPayload tooLarge = PluginArtifactPublisher.preparePayloadWith(
                many, 1L, plugin -> PluginTestFixtures.sampleDex(plugin.getId()));
        assertEquals("TABLE_TOO_LARGE", tooLarge.error);
    }

    // ------------------------------------------------------------- service seam

    private static final class FakeRemote implements PluginArtifactPublisher.RemoteService {
        final List<String> operations = new ArrayList<>();
        final List<String> written = new ArrayList<>();
        final Set<String> deleted = new LinkedHashSet<>();
        int apiVersion = 101;
        long frameworkProperties = -1L;
        boolean failWrite;
        boolean omitWrittenFromList;
        boolean failCommit;
        String[] extraRemoteFiles = new String[0];

        @Override public int apiVersion() { return apiVersion; }

        @Override public long frameworkProperties() { return frameworkProperties; }

        @Override public String[] listRemoteFiles() {
            List<String> files = new ArrayList<>(written);
            files.addAll(Arrays.asList(extraRemoteFiles));
            if (omitWrittenFromList) files.removeAll(written);
            return files.toArray(new String[0]);
        }

        @Override public boolean deleteRemoteFile(String name) {
            operations.add("delete:" + name);
            deleted.add(name);
            return true;
        }

        @Override public boolean writeRemoteFile(String name, byte[] bytes) {
            operations.add("write:" + name);
            if (failWrite) return false;
            written.add(name);
            return true;
        }

        @Override public boolean commitPreferences(String table, long generation) {
            operations.add("commit:" + generation);
            return !failCommit;
        }
    }

    private static Map<String, byte[]> dexFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("p_aaaaaaaaaaaaaaaa_bbbbbbbb.dex", new byte[]{1, 2, 3});
        return files;
    }

    @Test public void successfulPublishWritesVerifiesCommitsThenCleansOldFiles() {
        FakeRemote remote = new FakeRemote();
        remote.extraRemoteFiles = new String[]{"p_cccccccccccccccc_dddddddd.dex"};
        String error = PluginArtifactPublisher.publishToService(remote, dexFiles(), "{}", 42L);
        assertNull(error);
        int commitIndex = remote.operations.indexOf("commit:42");
        int writeIndex = remote.operations.indexOf("write:p_aaaaaaaaaaaaaaaa_bbbbbbbb.dex");
        int deleteIndex = remote.operations.indexOf("delete:p_cccccccccccccccc_dddddddd.dex");
        assertTrue(writeIndex >= 0);
        assertTrue(commitIndex > writeIndex);
        assertTrue(deleteIndex > commitIndex);
    }

    @Test public void publishFailsBeforeCommitOnEveryRejection() {
        FakeRemote unsupported = new FakeRemote();
        unsupported.apiVersion = 100;
        assertEquals("REMOTE_UNSUPPORTED",
                PluginArtifactPublisher.publishToService(unsupported, dexFiles(), "{}", 1L));
        assertTrue(unsupported.operations.isEmpty());

        FakeRemote noRemoteCapability = new FakeRemote();
        noRemoteCapability.frameworkProperties = 1L; // PROP_CAP_SYSTEM only
        // Capability flags are advisory: a bound API101 service is attempted; actual remote
        // operations (or UnsupportedOperationException) decide availability.
        assertNull(PluginArtifactPublisher.publishToService(
                noRemoteCapability, dexFiles(), "{}", 1L));
        assertFalse(noRemoteCapability.operations.isEmpty());

        FakeRemote writeFailure = new FakeRemote();
        writeFailure.failWrite = true;
        assertEquals("REMOTE_WRITE_FAILED",
                PluginArtifactPublisher.publishToService(writeFailure, dexFiles(), "{}", 1L));
        assertFalse(writeFailure.operations.contains("commit:1"));

        FakeRemote unverified = new FakeRemote();
        unverified.omitWrittenFromList = true;
        assertEquals("REMOTE_WRITE_UNVERIFIED",
                PluginArtifactPublisher.publishToService(unverified, dexFiles(), "{}", 1L));
        assertFalse(unverified.operations.contains("commit:1"));

        FakeRemote commitFailure = new FakeRemote();
        commitFailure.failCommit = true;
        commitFailure.extraRemoteFiles = new String[]{"p_cccccccccccccccc_dddddddd.dex"};
        assertEquals("COMMIT_FAILED",
                PluginArtifactPublisher.publishToService(commitFailure, dexFiles(), "{}", 1L));
        assertTrue(commitFailure.deleted.isEmpty());
    }

    @Test public void emptySnapshotStillCommits() {
        FakeRemote remote = new FakeRemote();
        assertNull(PluginArtifactPublisher.publishToService(remote, Map.of(), "{\"packages\":{}}", 7L));
        assertTrue(remote.operations.contains("commit:7"));
    }

    @Test public void payloadTableRoundTripsThroughJsonText() throws Exception {
        InstalledPlugin plugin = PluginTestFixtures.installed("com.example.aa", 1, TARGET);
        PluginArtifactPublisher.PublishPayload payload = PluginArtifactPublisher.preparePayloadWith(
                List.of(plugin), 3L, ignored -> PluginTestFixtures.sampleDex("com.example.aa:1"));
        assertNull(payload.error);
        JSONObject root = new JSONObject(payload.table);
        assertEquals(3L, root.getLong("generation"));
        assertEquals(1, root.getJSONObject("packages").length());
        assertTrue(new String(payload.table.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8).contains("\"dex\""));
    }
}
