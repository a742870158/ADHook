package com.adhook.config.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default host authorization: a caller may access only packages it actually owns.
 *
 * <p>There is no user grant switch anymore. The Binder caller UID is resolved by the Provider and
 * each owned package is checked against PackageManager metadata; a missing package, UID mismatch
 * or unreadable signing metadata still fails closed. shared UID does not authorize unrelated
 * packages because {@link CallerPolicy} only exposes the caller's own package set.</p>
 */
public final class HostAuthorizationStore {
    private final Context context;

    public HostAuthorizationStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isAuthorized(String pkg, int uid) {
        if (!ConfigInputPolicy.isPackageName(pkg) || uid < 0) return false;
        return installedIdentity(pkg, uid) != null;
    }

    public boolean isAuthorized(String pkg) {
        try {
            return isAuthorized(pkg, context.getPackageManager().getApplicationInfo(pkg, 0).uid);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String installedIdentity(String pkg, int expectedUid) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.applicationInfo == null || info.applicationInfo.uid != expectedUid) return null;
            Signature[] signatures = info.signingInfo != null
                    ? info.signingInfo.getApkContentsSigners() : info.signatures;
            if (signatures == null || signatures.length == 0) return null;
            List<String> hashes = new ArrayList<>();
            for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
                hashes.add(hex.toString());
            }
            Collections.sort(hashes);
            StringBuilder identity = new StringBuilder().append(expectedUid).append(':');
            for (String hash : hashes) {
                if (identity.charAt(identity.length() - 1) != ':') identity.append(',');
                identity.append(hash);
            }
            return identity.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
