package com.adhook.config.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Private grants, separate from rule/configuration data and every configuration backup.
 * No built-in host catalog, lazy grant, migration from AppConfig, or exported grant API exists.
 */
public final class HostAuthorizationStore {
    private static final String PREFS = "adhook_host_authorizations";
    private static final int MAX_USER_GRANTS = 2000;
    private final Context context;
    private final SharedPreferences preferences;

    public HostAuthorizationStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAuthorized(String pkg, int uid) {
        if (!ConfigInputPolicy.isPackageName(pkg) || uid < 0) return false;
        String grant = preferences.getString(pkg, null);
        if (grant == null) return false;
        String identity = installedIdentity(pkg, uid);
        return identity != null && identity.equals(grant);
    }

    public boolean isAuthorized(String pkg) {
        try {
            return isAuthorized(pkg, context.getPackageManager().getApplicationInfo(pkg, 0).uid);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    /** Call only after an explicit in-console user confirmation, on an IO thread. */
    public synchronized boolean setUserAuthorized(String pkg, boolean authorized) {
        if (!ConfigInputPolicy.isPackageName(pkg)) return false;
        if (!authorized) return preferences.edit().remove(pkg).commit();
        try {
            if (!preferences.contains(pkg) && preferences.getAll().size() >= MAX_USER_GRANTS) return false;
            int uid = context.getPackageManager().getApplicationInfo(pkg, 0).uid;
            String identity = installedIdentity(pkg, uid);
            return identity != null && preferences.edit().putString(pkg, identity).commit();
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
