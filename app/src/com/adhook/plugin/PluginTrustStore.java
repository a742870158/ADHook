package com.adhook.plugin;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publisher pinning store ({@code files/plugins/trust.json}).
 *
 * <p>Trust is bound to the plugin id *and* the full public-key fingerprint. A same-id package
 * signed by a different key is reported as {@link TrustCheck.State#KEY_CHANGED} and can never be
 * re-pinned automatically. This data is private app state and is never exported in backups.</p>
 */
public final class PluginTrustStore {

    private static final String FILE_NAME = "trust.json";
    private static final int SCHEMA_VERSION = 1;
    private static volatile PluginTrustStore instance;

    private final File file;
    private final Map<String, PinnedPublisher> publishers = new LinkedHashMap<>();
    private boolean healthy = true;
    private String loadWarning;

    /** Result of comparing an incoming manifest with the pinned publisher. */
    public static final class TrustCheck {
        public enum State { TRUSTED, FIRST_USE, KEY_CHANGED }

        public final State state;
        public final String pluginId;
        public final String keyId;
        public final String fingerprint;
        public final String pinnedFingerprint;

        public TrustCheck(State state, String pluginId, String keyId, String fingerprint,
                          String pinnedFingerprint) {
            this.state = state;
            this.pluginId = pluginId != null ? pluginId : "";
            this.keyId = keyId != null ? keyId : "";
            this.fingerprint = fingerprint != null ? fingerprint : "";
            this.pinnedFingerprint = pinnedFingerprint != null ? pinnedFingerprint : "";
        }

        public boolean isTrusted() { return state == State.TRUSTED; }
        public boolean isFirstUse() { return state == State.FIRST_USE; }
        public boolean isKeyChanged() { return state == State.KEY_CHANGED; }
    }

    private static final class PinnedPublisher {
        final String keyId;
        final String fingerprint;
        final long pinnedAt;

        PinnedPublisher(String keyId, String fingerprint, long pinnedAt) {
            this.keyId = keyId;
            this.fingerprint = fingerprint;
            this.pinnedAt = pinnedAt;
        }
    }

    public PluginTrustStore(Context context) {
        this(new File(context.getFilesDir(), "plugins"));
    }

    public PluginTrustStore(File pluginsDirectory) {
        this.file = new File(pluginsDirectory, FILE_NAME);
        load();
    }

    public static PluginTrustStore get(Context context) {
        File pluginsDirectory = new File(context.getFilesDir(), "plugins");
        PluginTrustStore current = instance;
        if (current != null && current.file.getParentFile() != null
                && current.file.getParentFile().equals(pluginsDirectory)) {
            return current;
        }
        synchronized (PluginTrustStore.class) {
            current = instance;
            if (current == null || current.file.getParentFile() == null
                    || !current.file.getParentFile().equals(pluginsDirectory)) {
                current = new PluginTrustStore(pluginsDirectory);
                instance = current;
            }
            return current;
        }
    }

    /** Detached view of the pinned publisher (null when absent or the store is unhealthy). */
    public synchronized TrustCheck check(PluginManifest manifest) {
        if (manifest == null) {
            return new TrustCheck(TrustCheck.State.FIRST_USE, "", "", "", "");
        }
        String keyId = manifest.getPublisherKeyId();
        byte[] spki = manifest.publisherPublicKeyDer();
        String fingerprint = PluginSignature.fingerprint(spki);
        PinnedPublisher pinned = publishers.get(manifest.getId());
        if (pinned == null) {
            return new TrustCheck(TrustCheck.State.FIRST_USE, manifest.getId(), keyId, fingerprint, "");
        }
        boolean matches = !keyId.isEmpty() && !fingerprint.isEmpty()
                && pinned.keyId.equals(keyId) && pinned.fingerprint.equals(fingerprint);
        return new TrustCheck(matches ? TrustCheck.State.TRUSTED : TrustCheck.State.KEY_CHANGED,
                manifest.getId(), keyId, fingerprint, pinned.fingerprint);
    }

    /**
     * Pins a first-use publisher. {@code userConfirmed} is mandatory for FIRST_USE; an
     * already-trusted publisher is a no-op success; a changed key is never accepted.
     */
    public synchronized boolean pin(PluginManifest manifest, boolean userConfirmed) {
        if (!healthy || manifest == null || !manifest.isSigned()) return false;
        byte[] spki = manifest.publisherPublicKeyDer();
        if (spki.length == 0) return false;
        String keyId = manifest.getPublisherKeyId();
        if (!keyId.equals(PluginSignature.keyId(spki))) return false;
        TrustCheck check = check(manifest);
        if (check.state == TrustCheck.State.KEY_CHANGED) return false;
        if (check.state == TrustCheck.State.TRUSTED) return true;
        if (!userConfirmed) return false;
        publishers.put(manifest.getId(), new PinnedPublisher(keyId, check.fingerprint,
                System.currentTimeMillis()));
        if (save()) return true;
        publishers.remove(manifest.getId());
        return false;
    }

    /** Removes the pin for one plugin id. Returns false when the store is unhealthy or cannot save. */
    public synchronized boolean revoke(String pluginId) {
        if (!healthy || pluginId == null) return false;
        PinnedPublisher removed = publishers.remove(pluginId);
        if (removed == null) return true;
        if (save()) return true;
        publishers.put(pluginId, removed);
        return false;
    }

    /**
     * Clears every pin. Explicit test/user recovery operation; also resets an unhealthy store so
     * a user can start over without deleting files by hand.
     */
    public synchronized boolean clearAll() {
        Map<String, PinnedPublisher> previous = new LinkedHashMap<>(publishers);
        publishers.clear();
        boolean wasHealthy = healthy;
        healthy = true;
        if (save()) return true;
        publishers.putAll(previous);
        healthy = wasHealthy;
        return false;
    }

    public synchronized boolean isHealthy() { return healthy; }

    public synchronized String getLoadWarning() { return loadWarning; }

    public synchronized String pinnedKeyId(String pluginId) {
        PinnedPublisher pinned = publishers.get(pluginId);
        return pinned != null ? pinned.keyId : "";
    }

    public synchronized int count() { return publishers.size(); }

    private void load() {
        if (!file.exists()) return;
        try {
            String text = PluginFiles.readText(file, PluginFiles.MAX_STORE_BYTES);
            JSONObject root = new JSONObject(text);
            if (!(root.opt("schema") instanceof Number)
                    || ((Number) root.opt("schema")).intValue() != SCHEMA_VERSION) {
                throw new IllegalArgumentException("schema");
            }
            Object rawPublishers = root.opt("publishers");
            if (!(rawPublishers instanceof JSONObject)) throw new IllegalArgumentException("publishers");
            JSONObject publisherObject = (JSONObject) rawPublishers;
            Iterator<String> keys = publisherObject.keys();
            while (keys.hasNext()) {
                String pluginId = keys.next();
                if (!PluginConstants.isValidPluginId(pluginId)) throw new IllegalArgumentException("id");
                Object raw = publisherObject.opt(pluginId);
                if (!(raw instanceof JSONObject)) throw new IllegalArgumentException("entry");
                JSONObject entry = (JSONObject) raw;
                String keyId = entry.optString("keyId", "");
                String fingerprint = entry.optString("fingerprint", "");
                if (!PluginConstants.isHexKeyId(keyId) || !PluginConstants.isHexSha256(fingerprint)) {
                    throw new IllegalArgumentException("entry");
                }
                long pinnedAt = entry.optLong("pinnedAt", 0L);
                publishers.put(pluginId, new PinnedPublisher(keyId, fingerprint, pinnedAt));
            }
        } catch (Exception malformed) {
            healthy = false;
            publishers.clear();
            boolean preserved = preserveCorruptCopy();
            loadWarning = preserved
                    ? "发布者信任记录损坏，已保留损坏副本并暂停信任写入"
                    : "发布者信任记录损坏，损坏副本保留失败；已暂停信任写入";
        }
    }

    private boolean preserveCorruptCopy() {
        try {
            File copy = PluginFiles.uniqueSibling(file, ".corrupt");
            java.nio.file.Files.copy(file.toPath(), copy.toPath());
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private boolean save() {
        String text;
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA_VERSION);
            JSONObject object = new JSONObject();
            for (Map.Entry<String, PinnedPublisher> entry : publishers.entrySet()) {
                JSONObject value = new JSONObject();
                value.put("keyId", entry.getValue().keyId);
                value.put("fingerprint", entry.getValue().fingerprint);
                value.put("pinnedAt", entry.getValue().pinnedAt);
                object.put(entry.getKey(), value);
            }
            root.put("publishers", object);
            text = root.toString();
        } catch (org.json.JSONException failure) {
            return false;
        }
        if (!PluginFiles.writeAtomically(file, text)) return false;
        try {
            return text.equals(PluginFiles.readText(file, PluginFiles.MAX_STORE_BYTES));
        } catch (Exception failure) {
            return false;
        }
    }

    /** Detached copy for diagnostics; never exported. */
    public synchronized Map<String, String> pinnedFingerprints() {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, PinnedPublisher> entry : publishers.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().fingerprint);
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Test-only hook kept package-private so production callers keep using the singleton. */
    static void resetForTests() {
        instance = null;
    }
}
