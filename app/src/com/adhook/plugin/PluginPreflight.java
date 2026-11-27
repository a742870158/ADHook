package com.adhook.plugin;

/**
 * Result of validating a plugin archive before any installation side effect.
 *
 * <p>{@code conflict} is non-null only for target ownership conflicts; {@code error} carries any
 * other blocking code. {@link #isOk()} requires both to be null. {@code alreadyInstalled} means
 * the exact same id+versionCode+zip hash is already present, so installing is idempotent.
 * {@code message} is a short, safe explanation suitable for the UI (no payload contents).</p>
 */
public final class PluginPreflight {

    public final PluginManifest manifest;
    public final String zipSha256;
    public final PluginTrustStore.TrustCheck trust;
    public final InstallError conflict;
    public final InstallError error;
    public final boolean alreadyInstalled;
    public final int installedVersionCode;
    public final String message;

    public PluginPreflight(PluginManifest manifest, String zipSha256,
                           PluginTrustStore.TrustCheck trust, InstallError conflict,
                           InstallError error, boolean alreadyInstalled, int installedVersionCode) {
        this(manifest, zipSha256, trust, conflict, error, alreadyInstalled, installedVersionCode, "");
    }

    public PluginPreflight(PluginManifest manifest, String zipSha256,
                           PluginTrustStore.TrustCheck trust, InstallError conflict,
                           InstallError error, boolean alreadyInstalled, int installedVersionCode,
                           String message) {
        this.manifest = manifest;
        this.zipSha256 = zipSha256 != null ? zipSha256 : "";
        this.trust = trust;
        this.conflict = conflict;
        this.error = error;
        this.alreadyInstalled = alreadyInstalled;
        this.installedVersionCode = installedVersionCode;
        this.message = message != null ? message : "";
    }

    public boolean isOk() {
        return error == null && conflict == null;
    }

    /** The first blocking code, conflict first; null when installable. */
    public InstallError blockingError() {
        if (conflict != null) return conflict;
        return error;
    }

    /** Convenience factory used by the installer for rejected preflights. */
    static PluginPreflight rejection(PluginManifest manifest, String zipSha256,
                                     PluginTrustStore.TrustCheck trust, InstallError error) {
        return rejection(manifest, zipSha256, trust, error, "");
    }

    static PluginPreflight rejection(PluginManifest manifest, String zipSha256,
                                     PluginTrustStore.TrustCheck trust, InstallError error,
                                     String message) {
        return new PluginPreflight(manifest, zipSha256, trust, null, error, false, 0, message);
    }
}
