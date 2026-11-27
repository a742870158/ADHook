package com.adhook.plugin;

import com.adhook.config.security.ConfigInputPolicy;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Host-plugin wire contract: fixed limits and strict identifier/path validators.
 *
 * <p>The ABI, schema and remote-preference keys are shared with the host runtime and the
 * packaging tool. Changing any value here requires a plugin ABI/schema bump, never a
 * silent in-place change.</p>
 */
public final class PluginConstants {

    public static final int SCHEMA_VERSION = 1;
    public static final int PLUGIN_ABI = 2;

    /** Framework RemotePreferences group and keys owned by this module. */
    public static final String REMOTE_PREFS_GROUP = "adhook_plugins_v1";
    public static final String REMOTE_TABLE_KEY = "plugin_table_json";
    public static final String REMOTE_GENERATION_KEY = "plugin_generation";

    public static final int MAX_ZIP_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DEX_BYTES = 12 * 1024 * 1024;
    public static final long MAX_UNCOMPRESSED_BYTES = 48L * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 256;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final int MAX_TARGETS = 16;
    public static final int MAX_FEATURES = 64;
    public static final int MAX_PLUGINS = 256;
    public static final int MAX_SOURCES = 16;
    public static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_REMOTE_TABLE_BYTES = 64 * 1024;
    public static final int MAX_SIGNATURE_BYTES = 4096;
    public static final int MAX_ASSET_BYTES = 4 * 1024 * 1024;

    /** Extra per-plugin limits used by targets/process lists and display text. */
    public static final int MAX_PROCESSES = 16;
    public static final int MAX_NAME_LENGTH = 128;
    /** plugin.json {@code name} is shorter than generic display names (format contract). */
    public static final int MAX_PLUGIN_NAME_LENGTH = 64;
    public static final int MAX_TARGET_LABEL_LENGTH = 64;
    public static final int MAX_FEATURE_DESCRIPTION_LENGTH = 2048;
    public static final int MAX_URL_LENGTH = 512;
    public static final int MAX_DESCRIPTION_LENGTH = 4096;
    public static final int MAX_ENTRY_LENGTH = 256;
    public static final int MAX_PUBLISHER_KEY_LENGTH = MAX_SIGNATURE_BYTES;

    private static final Pattern PLUGIN_ID =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    private static final Pattern VERSION =
            Pattern.compile("^[0-9][0-9A-Za-z._-]{0,63}$");
    private static final Pattern CLASS_NAME_SEGMENT =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Pattern ARCHIVE_PATH =
            Pattern.compile("^[A-Za-z0-9._/-]+$");
    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");
    /** keyId = first 16 bytes of sha256(SPKI DER) as lowercase hex, i.e. 32 characters. */
    private static final Pattern HEX_16 = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern DEX_FILE_NAME =
            Pattern.compile("^p_[0-9a-f]{16}_[0-9a-f]{8}\\.dex$");

    private PluginConstants() {}

    public static boolean isValidPluginId(String value) {
        return value != null && PLUGIN_ID.matcher(value).matches();
    }

    public static boolean isValidVersion(String value) {
        return value != null && VERSION.matcher(value).matches();
    }

    /** Delegates to the shared IPC identifier policy so features stay one contract. */
    public static boolean isValidFeatureId(String value) {
        return ConfigInputPolicy.isFeatureId(value);
    }

    public static boolean isValidPackageName(String value) {
        return ConfigInputPolicy.isPackageName(value);
    }

    /**
     * Android process name: either a package name or {@code package:suffix} (for example
     * {@code com.example.app:remote}). Used by plugin targets and runtime routes.
     */
    public static boolean isValidProcessName(String value) {
        if (value == null || value.isEmpty() || value.length() > 256) return false;
        if (hasControlCharacters(value) || value.indexOf('\\') >= 0 || value.indexOf(' ') >= 0) {
            return false;
        }
        int colon = value.indexOf(':');
        if (colon < 0) return isValidPackageName(value);
        if (value.indexOf(':', colon + 1) >= 0) return false;
        if (!isValidPackageName(value.substring(0, colon))) return false;
        String suffix = value.substring(colon + 1);
        if (suffix.isEmpty() || suffix.length() > 64) return false;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            boolean letter = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
            boolean digit = c >= '0' && c <= '9';
            if (!(letter || digit || c == '_' || c == '.' || c == '-')) return false;
        }
        return true;
    }

    /** Java binary class name: dotted identifiers, {@code $} allowed for nested classes. */
    public static boolean isValidClassName(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ENTRY_LENGTH) return false;
        String[] segments = value.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || !CLASS_NAME_SEGMENT.matcher(segment).matches()) return false;
        }
        return true;
    }

    /** Strict archive path: ASCII only, relative, no empty/./.. segments and no backslash. */
    public static boolean isSafeArchivePath(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) return false;
        if (!ARCHIVE_PATH.matcher(value).matches()) return false;
        if (value.startsWith("/") || value.endsWith("/")) return false;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    public static boolean isHexSha256(String value) {
        return value != null && HEX_64.matcher(value).matches();
    }

    public static boolean isHexKeyId(String value) {
        return value != null && HEX_16.matcher(value).matches();
    }

    public static boolean isValidDexFileName(String value) {
        return value != null && DEX_FILE_NAME.matcher(value).matches();
    }

    public static boolean isForbiddenTargetPackage(String packageName) {
        return "android".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || "com.adhook".equals(packageName);
    }

    public static boolean hasControlCharacters(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /**
     * Validates an absolute http/https URL: no userinfo, no fragment, valid host/port and at most
     * 2048 characters. Used for market sources, redirects and release zip URLs.
     */
    public static boolean isValidHttpUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048) return false;
        if (value.indexOf('\\') >= 0 || hasControlCharacters(value)) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            if (uri.getHost() == null || uri.getHost().isEmpty()) return false;
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) return false;
            int port = uri.getPort();
            return port == -1 || (port >= 1 && port <= 65535);
        } catch (Exception malformed) {
            return false;
        }
    }

    /**
     * Validates a relative reference used as a market {@code zipUrl}: it must not carry a scheme,
     * authority, userinfo, fragment or traversal segment and stays within 512 characters.
     */
    public static boolean isValidRelativeReference(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) return false;
        if (value.startsWith("/") || value.startsWith("//") || value.indexOf('\\') >= 0
                || hasControlCharacters(value) || value.indexOf(' ') >= 0) return false;
        try {
            URI uri = new URI(value);
            if (uri.getScheme() != null || uri.getRawAuthority() != null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return false;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) return false;
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
            }
            return true;
        } catch (Exception malformed) {
            return false;
        }
    }
}
