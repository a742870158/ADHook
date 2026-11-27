package com.adhook.plugin;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Pure JSON contract tests for plugin.json. */
public class PluginManifestTest {

    private static final String ID = "com.example.cleaner";
    private static final String TARGET = "com.example.target";

    private JSONObject validManifest() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        JSONObject manifest = PluginTestFixtures.manifestJson(ID, 7, TARGET, keyPair);
        JSONObject files = new JSONObject();
        files.put("classes.dex", "a".repeat(64));
        manifest.put("files", files);
        return manifest;
    }

    @Test public void acceptsAValidManifestAndSerializesIdentically() throws Exception {
        JSONObject json = validManifest();
        PluginManifest manifest = PluginManifest.parse(json);
        assertEquals(PluginConstants.SCHEMA_VERSION, manifest.getSchema());
        assertEquals(ID, manifest.getId());
        assertEquals(7, manifest.getVersionCode());
        assertEquals(PluginConstants.PLUGIN_ABI, manifest.getAbi());
        assertEquals(PluginTestFixtures.ENTRY, manifest.getEntry());
        assertEquals(1, manifest.getTargets().size());
        assertEquals(TARGET, manifest.getTargets().get(0).getPackageName());
        assertTrue(manifest.isSigned());

        PluginManifest reparsed = PluginManifest.parse(manifest.toJson());
        assertEquals(manifest.getId(), reparsed.getId());
        assertEquals(manifest.getName(), reparsed.getName());
        assertEquals(manifest.getVersion(), reparsed.getVersion());
        assertEquals(manifest.getVersionCode(), reparsed.getVersionCode());
        assertEquals(manifest.getMinBaseVersionCode(), reparsed.getMinBaseVersionCode());
        assertEquals(manifest.getPublisherKeyId(), reparsed.getPublisherKeyId());
        assertEquals(manifest.getPublisherPublicKeyBase64(), reparsed.getPublisherPublicKeyBase64());
        assertEquals(manifest.getFiles(), reparsed.getFiles());
        assertEquals(manifest.getPublishedAt(), reparsed.getPublishedAt());
    }

    @Test public void rejectsMalformedPluginIds() throws Exception {
        for (String id : new String[]{"ab", "Abc", "-abc", "a b c", "abc/def", "", "a"
                + "x".repeat(64)}) {
            JSONObject json = validManifest();
            json.put("id", id);
            assertThrows("expected rejection for id=" + id,
                    IllegalArgumentException.class, () -> PluginManifest.parse(json));
        }
    }

    @Test public void rejectsProtectedTargetPackages() throws Exception {
        for (String pkg : new String[]{"android", "com.android.systemui", "com.adhook"}) {
            JSONObject json = validManifest();
            JSONArray targets = new JSONArray();
            JSONObject target = new JSONObject();
            target.put("packageName", pkg);
            target.put("label", "x");
            target.put("primary", "");
            target.put("processes", new JSONArray());
            targets.put(target);
            json.put("targets", targets);
            assertThrows("expected protected package rejection for " + pkg,
                    IllegalArgumentException.class, () -> PluginManifest.parse(json));
        }
    }

    @Test public void rejectsTooManyTargetsAndDuplicateTargets() throws Exception {
        JSONObject tooMany = validManifest();
        JSONArray targets = new JSONArray();
        for (int i = 0; i < PluginConstants.MAX_TARGETS + 1; i++) {
            JSONObject target = new JSONObject();
            target.put("packageName", "com.example.target" + i);
            target.put("label", "x");
            target.put("primary", "");
            target.put("processes", new JSONArray());
            targets.put(target);
        }
        tooMany.put("targets", targets);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(tooMany));

        JSONObject duplicate = validManifest();
        JSONArray duplicated = new JSONArray();
        for (int i = 0; i < 2; i++) {
            JSONObject target = new JSONObject();
            target.put("packageName", TARGET);
            target.put("label", "x");
            target.put("primary", "");
            target.put("processes", new JSONArray());
            duplicated.put(target);
        }
        duplicate.put("targets", duplicated);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(duplicate));
    }

    @Test public void enforcesFeatureLimitsAndIds() throws Exception {
        JSONObject tooMany = validManifest();
        JSONArray features = new JSONArray();
        for (int i = 0; i < PluginConstants.MAX_FEATURES + 1; i++) {
            features.put(feature("feature-" + i));
        }
        tooMany.put("features", features);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(tooMany));

        JSONObject invalidId = validManifest();
        JSONArray invalid = new JSONArray();
        invalid.put(feature("bad feature id"));
        invalidId.put("features", invalid);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(invalidId));

        JSONObject duplicate = validManifest();
        JSONArray duplicated = new JSONArray();
        duplicated.put(feature("feed-ads"));
        duplicated.put(feature("feed-ads"));
        duplicate.put("features", duplicated);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(duplicate));
    }

    @Test public void acceptsValidFeatureAndRejectsTooManyAfterRoundTrip() throws Exception {
        JSONObject json = validManifest();
        JSONArray features = new JSONArray();
        features.put(feature("feed-ads"));
        json.put("features", features);
        PluginManifest manifest = PluginManifest.parse(json);
        assertEquals(1, manifest.getFeatures().size());
        assertEquals(1, manifest.featureMetas().size());
        assertNotNull(manifest.featureMetas().get(0));
    }

    @Test public void validatesEntryClassName() throws Exception {
        for (String entry : new String[]{"com.example.Entry", "Entry", "a.b.C$Inner"}) {
            JSONObject json = validManifest();
            json.put("entry", entry);
            assertEquals(entry, PluginManifest.parse(json).getEntry());
        }
        for (String entry : new String[]{"com..Entry", "1bad.Entry", "com.example.", "com example.Entry",
                ""}) {
            JSONObject json = validManifest();
            json.put("entry", entry);
            assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(json));
        }
    }

    @Test public void requiresClassesDexAndValidFileHashes() throws Exception {
        JSONObject missingDex = validManifest();
        JSONObject files = new JSONObject();
        files.put("assets/x.json", "b".repeat(64));
        missingDex.put("files", files);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(missingDex));

        JSONObject badHash = validManifest();
        JSONObject badFiles = new JSONObject();
        badFiles.put("classes.dex", "not-a-hash");
        badHash.put("files", badFiles);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(badHash));

        JSONObject traversal = validManifest();
        JSONObject traversalFiles = new JSONObject();
        traversalFiles.put("classes.dex", "a".repeat(64));
        traversalFiles.put("assets/../evil", "a".repeat(64));
        traversal.put("files", traversalFiles);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(traversal));
    }

    @Test public void publisherKeyIdAndPublicKeyMustBeConsistent() throws Exception {
        JSONObject partial = validManifest();
        JSONObject publisher = new JSONObject();
        publisher.put("keyId", "0123456789abcdef");
        partial.put("publisher", publisher);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(partial));

        JSONObject badKeyId = validManifest();
        JSONObject badPublisher = new JSONObject();
        badPublisher.put("keyId", "ABCDEF0123456789");
        badPublisher.put("publicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEx");
        badKeyId.put("publisher", badPublisher);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(badKeyId));
    }

    @Test public void unsignedManifestHasNoPublisherButStillParses() throws Exception {
        JSONObject json = PluginTestFixtures.manifestJson(ID, 1, TARGET, null);
        JSONObject files = new JSONObject();
        files.put("classes.dex", "a".repeat(64));
        json.put("files", files);
        PluginManifest manifest = PluginManifest.parse(json);
        assertTrue(manifest.getPublisherKeyId().isEmpty());
        assertTrue(!manifest.isSigned());
    }

    @Test public void rejectsUnknownFieldsAndDuplicateJsonKeys() throws Exception {
        JSONObject unknown = validManifest();
        unknown.put("unexpectedField", true);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(unknown));

        JSONObject unknownPublisher = validManifest();
        unknownPublisher.getJSONObject("publisher").put("extra", 1);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(unknownPublisher));

        JSONObject unknownFeature = validManifest();
        JSONArray features = new JSONArray();
        JSONObject feature = feature("feed-ads");
        feature.put("extra", true);
        features.put(feature);
        unknownFeature.put("features", features);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(unknownFeature));

        JSONObject unknownTarget = validManifest();
        JSONArray targets = new JSONArray();
        JSONObject target = new JSONObject();
        target.put("packageName", TARGET);
        target.put("label", "x");
        target.put("primary", "");
        target.put("processes", new JSONArray());
        target.put("extra", true);
        targets.put(target);
        unknownTarget.put("targets", targets);
        assertThrows(IllegalArgumentException.class, () -> PluginManifest.parse(unknownTarget));
    }

    private static JSONObject feature(String id) throws Exception {
        JSONObject feature = new JSONObject();
        feature.put("id", id);
        feature.put("name", "Feature");
        feature.put("description", "desc");
        feature.put("defaultEnabled", true);
        feature.put("implemented", true);
        return feature;
    }

    @Test public void targetCoverageAndMatchingSemantics() throws Exception {
        KeyPair keyPair = PluginTestFixtures.keyPair();
        JSONObject json = PluginTestFixtures.manifestJson(ID, 1, TARGET, keyPair);
        JSONObject files = new JSONObject();
        files.put("classes.dex", "a".repeat(64));
        json.put("files", files);
        JSONArray targets = new JSONArray();
        JSONObject target = new JSONObject();
        target.put("packageName", TARGET);
        target.put("label", "Target");
        target.put("primary", "com.example.target.primary");
        target.put("processes", new JSONArray().put("com.example.target:remote"));
        targets.put(target);
        json.put("targets", targets);
        PluginTarget parsed = PluginManifest.parse(json).getTargets().get(0);
        assertTrue(parsed.matches("com.example.target.primary"));
        assertTrue(!parsed.matches(TARGET));
        assertEquals(2, parsed.coveredPackages().size());

        JSONObject noPrimary = json;
        target.put("primary", "");
        PluginTarget fallback = PluginManifest.parse(noPrimary).getTargets().get(0);
        assertTrue(fallback.matches(TARGET));
        List<String> covered = new ArrayList<>(fallback.coveredPackages());
        assertEquals(1, covered.size());
    }
}
