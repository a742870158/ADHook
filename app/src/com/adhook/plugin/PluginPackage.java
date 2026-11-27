package com.adhook.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory, fully validated plugin package. Only used during installation; the store keeps
 * the manifest text and hashes, never the dex/assets payloads.
 */
public final class PluginPackage {

    /** Exact {@code plugin.json} bytes from the archive (signed by hash). */
    public final byte[] manifestBytes;
    public final PluginManifest manifest;
    public final byte[] dex;
    /** Asset entries keyed by their full archive path, e.g. {@code assets/rules.json}. */
    public final Map<String, byte[]> assets;
    /** Raw {@code META-INF/plugin.sig} bytes, or null for an unsigned package. */
    public final byte[] signatureBytes;

    public PluginPackage(byte[] manifestBytes, PluginManifest manifest, byte[] dex,
                         Map<String, byte[]> assets, byte[] signatureBytes) {
        this.manifestBytes = manifestBytes;
        this.manifest = manifest;
        this.dex = dex;
        this.assets = Collections.unmodifiableMap(
                assets != null ? new LinkedHashMap<>(assets) : new LinkedHashMap<>());
        this.signatureBytes = signatureBytes;
    }

    public boolean isSigned() {
        return signatureBytes != null && signatureBytes.length > 0 && manifest != null
                && manifest.isSigned();
    }
}
