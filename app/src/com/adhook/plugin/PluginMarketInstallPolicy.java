package com.adhook.plugin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Binds a downloaded plugin package to its market listing.
 *
 * <p>The market index is not itself signed, so installation must prove the zip bytes came from
 * the listed release ({@code sha256}) and that the signed manifest describes the same publisher,
 * version and target set. Any mismatch is a hard rejection; a missing publisher is never
 * treated as "trusted".</p>
 */
public final class PluginMarketInstallPolicy {

    private PluginMarketInstallPolicy() {}

    public static InstallError verify(PluginManifest manifest, PluginMarketEntry entry,
                                      String zipSha256) {
        if (entry == null) return InstallError.MARKET_RELEASE_MISSING;
        if (manifest == null) return InstallError.MARKET_INDEX_INVALID;
        if (!entry.getId().equals(manifest.getId())) return InstallError.MARKET_ID_MISMATCH;
        return verify(manifest, entry.getRelease(), zipSha256, entry.getTargets());
    }

    public static InstallError verify(PluginManifest manifest, PluginMarketRelease release,
                                      String zipSha256) {
        return verify(manifest, release, zipSha256, null);
    }

    private static InstallError verify(PluginManifest manifest, PluginMarketRelease release,
                                       String zipSha256, List<String> entryTargets) {
        if (release == null) return InstallError.MARKET_RELEASE_MISSING;
        if (manifest == null) return InstallError.MARKET_INDEX_INVALID;
        if (!PluginConstants.isHexSha256(release.getSha256())
                || !PluginConstants.isHexSha256(zipSha256)) {
            return InstallError.MARKET_INDEX_INVALID;
        }
        if (!release.getSha256().equalsIgnoreCase(zipSha256)) return InstallError.MARKET_HASH_MISMATCH;
        if (release.getVersionCode() != manifest.getVersionCode()
                || release.getMinBaseVersionCode() != manifest.getMinBaseVersionCode()
                || release.getPublishedAt() != manifest.getPublishedAt()) {
            return InstallError.MARKET_VERSION_MISMATCH;
        }
        if (release.getAbi() != manifest.getAbi()) return InstallError.ABI_MISMATCH;
        if (!release.hasPublisher() || !manifest.isSigned()) {
            return InstallError.MARKET_PUBLISHER_MISMATCH;
        }
        if (!release.getPublisherKeyId().equals(manifest.getPublisherKeyId())) {
            return InstallError.MARKET_PUBLISHER_MISMATCH;
        }
        byte[] releaseKey = release.publisherPublicKeyDer();
        byte[] manifestKey = manifest.publisherPublicKeyDer();
        if (releaseKey.length == 0 || !java.util.Arrays.equals(releaseKey, manifestKey)) {
            return InstallError.MARKET_PUBLISHER_MISMATCH;
        }
        if (entryTargets != null) {
            Set<String> listed = new LinkedHashSet<>(entryTargets);
            Set<String> declared = new LinkedHashSet<>();
            for (PluginTarget target : manifest.getTargets()) declared.add(target.getPackageName());
            if (!listed.equals(declared)) return InstallError.MARKET_TARGET_MISMATCH;
        }
        return InstallError.OK;
    }
}
