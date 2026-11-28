package com.adhook.plugin;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Atomic installer for host-plugin packages.
 *
 * <p>Call only from an IO thread. The installer re-validates the archive immediately before
 * installing, keeps the previous version directory until {@link PluginStore#upsert} succeeds and
 * rolls back both files and a freshly pinned publisher on any failure. It never writes host
 * business state and never reports success before the catalogue commit is durable.</p>
 */
public final class PluginInstaller {

    private static final Pattern VERSION_DIRECTORY = Pattern.compile("^[0-9]{1,10}$");

    private final Context context;
    private final int baseVersionCode;
    private final PluginStore store;
    private final PluginTrustStore trustStore;
    private final File pluginsDirectory;

    public PluginInstaller(Context context, int baseVersionCode) {
        this(context, baseVersionCode, PluginStore.get(context), PluginTrustStore.get(context));
    }

    public PluginInstaller(Context context, int baseVersionCode, PluginStore store,
                           PluginTrustStore trustStore) {
        Context application = context.getApplicationContext();
        this.context = application != null ? application : context;
        this.baseVersionCode = baseVersionCode;
        this.store = store;
        this.trustStore = trustStore;
        this.pluginsDirectory = new File(this.context.getFilesDir(), "plugins");
    }

    /** Full validation; IO failures are returned as an explicit code instead of thrown to the UI. */
    public PluginPreflight preflight(byte[] zipBytesOrNull, File zipFile, boolean allowUnsigned) {
        if ((zipBytesOrNull == null) == (zipFile == null)) {
            return PluginPreflight.rejection(null, "", null, InstallError.INVALID_ZIP);
        }
        String zipSha;
        PluginPackage pkg;
        File temporary = null;
        try {
            File source;
            if (zipFile != null) {
                if (!zipFile.isFile()) return PluginPreflight.rejection(null, "", null, InstallError.INVALID_ZIP);
                if (zipFile.length() > PluginConstants.MAX_ZIP_BYTES) {
                    return PluginPreflight.rejection(null, "", null, InstallError.INVALID_ZIP);
                }
                zipSha = PluginFiles.sha256Hex(zipFile);
                source = zipFile;
            } else {
                if (zipBytesOrNull.length == 0 || zipBytesOrNull.length > PluginConstants.MAX_ZIP_BYTES) {
                    return PluginPreflight.rejection(null, "", null, InstallError.INVALID_ZIP);
                }
                zipSha = PluginFiles.sha256Hex(zipBytesOrNull);
                File incoming = new File(pluginsDirectory, ".incoming");
                if (!PluginFiles.ensureDirectory(incoming)) {
                    return PluginPreflight.rejection(null, zipSha, null, InstallError.STORAGE_FAILURE);
                }
                temporary = new File(incoming, "preflight-" + UUID.randomUUID() + ".zip");
                writeBytes(temporary, zipBytesOrNull);
                source = temporary;
            }
            pkg = PluginZip.read(source);
        } catch (PluginValidationException rejection) {
            return PluginPreflight.rejection(null, zipShaOrEmpty(zipBytesOrNull), null,
                    mapValidationError(rejection), rejection.getMessage());
        } catch (IOException failure) {
            return PluginPreflight.rejection(null, "", null, InstallError.STORAGE_FAILURE,
                    "无法读取插件包文件");
        } finally {
            if (temporary != null) temporary.delete();
        }
        return evaluate(pkg, zipSha, allowUnsigned);
    }

    /**
     * Re-validates the archive and installs atomically. {@code trustPublisher} is required only
     * for a signed first-use publisher; unsigned packages require {@code allowUnsigned}.
     */
    public InstallError install(PluginPreflight preflight, File zipFile, boolean allowUnsigned,
                                boolean trustPublisher, String sourceId) {
        if (preflight == null || zipFile == null || !zipFile.isFile()) return InstallError.INVALID_ZIP;
        if (!preflight.isOk()) return preflight.blockingError();

        PluginPreflight fresh = preflight(null, zipFile, allowUnsigned);
        if (!fresh.isOk()) return fresh.blockingError();
        if (!fresh.zipSha256.equals(preflight.zipSha256)) {
            return InstallError.INVALID_ZIP; // archive changed between preflight and install
        }
        if (fresh.alreadyInstalled) return InstallError.OK;

        PluginManifest manifest = fresh.manifest;
        boolean signed = manifest.isSigned();
        if (signed) {
            if (fresh.trust != null && fresh.trust.state == PluginTrustStore.TrustCheck.State.KEY_CHANGED) {
                return InstallError.PUBLISHER_CHANGED;
            }
            if ((fresh.trust == null || fresh.trust.isFirstUse()) && !trustPublisher) {
                return InstallError.UNTRUSTED_PUBLISHER;
            }
        } else if (!allowUnsigned) {
            return InstallError.UNSIGNED;
        }

        PluginPackage pkg;
        String actualZipSha;
        try {
            pkg = PluginZip.read(zipFile);
            actualZipSha = PluginFiles.sha256Hex(zipFile);
        } catch (PluginValidationException rejection) {
            return mapValidationError(rejection);
        } catch (IOException failure) {
            return InstallError.STORAGE_FAILURE;
        }
        if (!actualZipSha.equals(fresh.zipSha256)) return InstallError.INVALID_ZIP;
        if (signed && !PluginSignature.verify(pkg)) return InstallError.BAD_SIGNATURE;

        File pluginDir = new File(pluginsDirectory, manifest.getId());
        if (!PluginFiles.ensureDirectory(pluginDir)) return InstallError.STORAGE_FAILURE;
        File staging = new File(pluginDir,
                ".staging-" + manifest.getVersionCode() + "-" + UUID.randomUUID());
        File target = new File(pluginDir, Integer.toString(manifest.getVersionCode()));
        File replaced = new File(pluginDir,
                ".replaced-" + manifest.getVersionCode() + "-" + UUID.randomUUID());
        boolean hadExistingDirectory = target.isDirectory();
        boolean promoted = false;
        boolean pinned = false;
        try {
            if (!writeStaging(staging, pkg, manifest)) {
                PluginFiles.deleteRecursively(staging);
                return InstallError.STORAGE_FAILURE;
            }
            if (hadExistingDirectory && !PluginFiles.renameDirectory(target, replaced)) {
                PluginFiles.deleteRecursively(staging);
                return InstallError.STORAGE_FAILURE;
            }
            if (!PluginFiles.renameDirectory(staging, target)) {
                if (hadExistingDirectory) PluginFiles.renameDirectory(replaced, target);
                PluginFiles.deleteRecursively(staging);
                return InstallError.STORAGE_FAILURE;
            }
            promoted = true;

            boolean pinFirstUse = signed && (fresh.trust == null || fresh.trust.isFirstUse());
            if (pinFirstUse) {
                if (!trustStore.pin(manifest, true)) {
                    rollbackInstall(target, replaced, hadExistingDirectory, false);
                    return InstallError.STORAGE_FAILURE;
                }
                pinned = true;
            }

            InstalledPlugin record = new InstalledPlugin(manifest.getId(), manifest.getName(),
                    manifest.getVersion(), manifest.getVersionCode(), manifest.getAbi(),
                    manifest.getMinBaseVersionCode(), manifest.getEntry(), manifest.getAuthorName(),
                    manifest.getDescription(), manifest.getTargets(), manifest.getFeatures(),
                    sourceId != null ? sourceId : "",
                    System.currentTimeMillis(), manifest.getPublisherKeyId(),
                    PluginSignature.fingerprint(manifest.publisherPublicKeyDer()),
                    fresh.zipSha256, PluginFiles.sha256Hex(pkg.dex),
                    new String(pkg.manifestBytes, StandardCharsets.UTF_8));
            if (!store.upsert(record)) {
                rollbackInstall(target, replaced, hadExistingDirectory, pinned, manifest);
                return InstallError.STORAGE_FAILURE;
            }
            PluginFiles.deleteRecursively(replaced);
            deleteOtherVersionDirectories(pluginDir, manifest.getVersionCode());
            return InstallError.OK;
        } catch (RuntimeException failure) {
            if (promoted) rollbackInstall(target, replaced, hadExistingDirectory, pinned, manifest);
            else PluginFiles.deleteRecursively(staging);
            return InstallError.STORAGE_FAILURE;
        }
    }

    /** Removes the catalogue entry, then the plugin directory. Publisher pins are kept. */
    public boolean uninstall(String pluginId) {
        if (!PluginConstants.isValidPluginId(pluginId)) return false;
        InstalledPlugin existing = store.find(pluginId);
        if (existing != null && !store.remove(pluginId)) return false;
        return deleteInstalledFiles(context, pluginId);
    }

    /** Explicit UI action: forget the pinned publisher for an uninstalled/removed plugin. */
    public boolean forgetPublisher(String pluginId) {
        return trustStore.revoke(pluginId);
    }

    public PluginStore getStore() { return store; }

    public PluginTrustStore getTrustStore() { return trustStore; }

    public int getBaseVersionCode() { return baseVersionCode; }

    /** Deletes {@code files/plugins/<id>}; rejects malformed ids so no path escapes the root. */
    public static boolean deleteInstalledFiles(Context context, String pluginId) {
        if (context == null || !PluginConstants.isValidPluginId(pluginId)) return false;
        File pluginDirectory = new File(new File(context.getFilesDir(), "plugins"), pluginId);
        if (!pluginDirectory.exists()) return true;
        return PluginFiles.deleteRecursively(pluginDirectory);
    }

    private PluginPreflight evaluate(PluginPackage pkg, String zipSha, boolean allowUnsigned) {
        PluginManifest manifest = pkg.manifest;
        PluginTrustStore.TrustCheck trust = trustStore.check(manifest);
        if (manifest.getAbi() != PluginConstants.PLUGIN_ABI) {
            return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.ABI_MISMATCH);
        }
        if (manifest.getMinBaseVersionCode() > baseVersionCode) {
            return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.BASE_TOO_OLD);
        }
        boolean signaturePresent = pkg.signatureBytes != null && pkg.signatureBytes.length > 0;
        if (!manifest.isSigned() || !signaturePresent) {
            if (!allowUnsigned) {
                return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.UNSIGNED);
            }
        } else if (!PluginSignature.verify(pkg)) {
            return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.BAD_SIGNATURE);
        }
        if (trust.isKeyChanged()) {
            return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.PUBLISHER_CHANGED);
        }
        if (!store.isHealthy()) {
            return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.STORE_UNHEALTHY);
        }
        InstalledPlugin existing = store.find(manifest.getId());
        int installedVersionCode = existing != null ? existing.getVersionCode() : 0;
        if (existing != null) {
            if (manifest.getVersionCode() < installedVersionCode) {
                return new PluginPreflight(manifest, zipSha, trust, null, InstallError.DOWNGRADE,
                        false, installedVersionCode);
            }
            if (manifest.getVersionCode() == installedVersionCode) {
                if (zipSha.equals(existing.getZipSha256())) {
                    return new PluginPreflight(manifest, zipSha, trust, null, null, true,
                            installedVersionCode);
                }
                return new PluginPreflight(manifest, zipSha, trust, null,
                        InstallError.SAME_VERSION_DIFFERENT_HASH, false, installedVersionCode);
            }
        } else if (store.count() >= PluginConstants.MAX_PLUGINS) {
            return PluginPreflight.rejection(manifest, zipSha, trust, InstallError.LIMIT_EXCEEDED);
        }
        InstallError conflict = findConflict(manifest);
        return new PluginPreflight(manifest, zipSha, trust, conflict, null, false, installedVersionCode);
    }

    private InstallError findConflict(PluginManifest manifest) {
        Set<String> incoming = manifest.coveredPackages();
        if (incoming.isEmpty()) return null;
        List<InstalledPlugin> installed = store.list();
        for (InstalledPlugin other : installed) {
            if (other.getId().equals(manifest.getId())) continue;
            for (String packageName : other.coveredPackages()) {
                if (incoming.contains(packageName)) return InstallError.CONFLICT;
            }
        }
        return null;
    }

    private boolean writeStaging(File staging, PluginPackage pkg, PluginManifest manifest) {
        if (!PluginFiles.ensureDirectory(staging)) return false;
        try {
            if (!writeBytes(new File(staging, PluginZip.DEX_ENTRY), pkg.dex)) return false;
            for (Map.Entry<String, String> file : manifest.getFiles().entrySet()) {
                String name = file.getKey();
                if (PluginZip.DEX_ENTRY.equals(name)) continue;
                byte[] bytes = pkg.assets.get(name);
                if (bytes == null) return false;
                File asset = new File(staging, name);
                if (!isInside(staging, asset)) return false;
                File parent = asset.getParentFile();
                if (parent != null && !PluginFiles.ensureDirectory(parent)) return false;
                if (!writeBytes(asset, bytes)) return false;
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean writeBytes(File target, byte[] bytes) {
        if (target == null || bytes == null) return false;
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private static boolean isInside(File directory, File candidate) {
        try {
            String root = directory.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (IOException failure) {
            return false;
        }
    }

    /** Puts the previous version directory back after a failed promotion. */
    private static void restoreDirectories(File target, File replaced, boolean hadExistingDirectory) {
        PluginFiles.deleteRecursively(target);
        if (hadExistingDirectory) PluginFiles.renameDirectory(replaced, target);
    }

    private void rollbackInstall(File target, File replaced, boolean hadExistingDirectory,
                                 boolean pinned) {
        restoreDirectories(target, replaced, hadExistingDirectory);
    }

    private void rollbackInstall(File target, File replaced, boolean hadExistingDirectory,
                                 boolean pinned, PluginManifest manifest) {
        restoreDirectories(target, replaced, hadExistingDirectory);
        if (pinned) trustStore.revoke(manifest.getId());
    }

    private void deleteOtherVersionDirectories(File pluginDir, int currentVersionCode) {
        File[] children = pluginDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (VERSION_DIRECTORY.matcher(name).matches()) {
                try {
                    if (Integer.parseInt(name) != currentVersionCode) PluginFiles.deleteRecursively(child);
                } catch (NumberFormatException ignored) {
                    // Not a version directory.
                }
            } else if (name.startsWith(".replaced-") || name.startsWith(".staging-")) {
                PluginFiles.deleteRecursively(child);
            }
        }
    }

    private static InstallError mapValidationError(PluginValidationException rejection) {
        switch (rejection.getKind()) {
            case MANIFEST:
                return InstallError.INVALID_MANIFEST;
            case SIGNATURE:
                return InstallError.BAD_SIGNATURE;
            case IO:
                return InstallError.STORAGE_FAILURE;
            case ZIP:
            default:
                return InstallError.INVALID_ZIP;
        }
    }

    private String zipShaOrEmpty(byte[] zipBytes) {
        return zipBytes != null ? PluginFiles.sha256Hex(zipBytes) : "";
    }
}
