package com.adhook.plugin;

/**
 * A plugin package was rejected before anything was installed.
 *
 * <p>The message is safe to show in the UI: it never contains raw payload contents, only a
 * short Chinese explanation plus, where useful, an ASCII archive path already validated by
 * {@link PluginConstants#isSafeArchivePath(String)}.</p>
 */
public class PluginValidationException extends Exception {

    /** Coarse rejection stage so callers can map a single exception to a specific error code. */
    public enum Kind {
        /** Archive structure, entry set, hash or size mismatch. */
        ZIP,
        /** {@code plugin.json} missing, malformed or semantically invalid. */
        MANIFEST,
        /** Signature entry malformed or cryptographically invalid. */
        SIGNATURE,
        /** Local IO failed while reading the package. */
        IO
    }

    private final Kind kind;

    public PluginValidationException(String message) {
        this(message, Kind.ZIP);
    }

    public PluginValidationException(String message, Kind kind) {
        super(message);
        this.kind = kind != null ? kind : Kind.ZIP;
    }

    public Kind getKind() {
        return kind;
    }
}
