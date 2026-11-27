package com.adhook.plugin;

/**
 * Explicit install/rejection outcome. The UI must never show an optimistic success before the
 * installer returns {@link #OK}; every other value is a stable, safe failure code.
 */
public enum InstallError {
    OK,
    INVALID_ZIP,
    INVALID_MANIFEST,
    UNSIGNED,
    BAD_SIGNATURE,
    UNTRUSTED_PUBLISHER,
    PUBLISHER_CHANGED,
    ABI_MISMATCH,
    BASE_TOO_OLD,
    CONFLICT,
    DOWNGRADE,
    SAME_VERSION_DIFFERENT_HASH,
    STORAGE_FAILURE,
    STORE_UNHEALTHY,
    LIMIT_EXCEEDED,

    /** Market index/release metadata is missing or unusable. */
    MARKET_INDEX_INVALID,
    MARKET_RELEASE_MISSING,
    /** Downloaded zip bytes do not match the release SHA-256. */
    MARKET_HASH_MISMATCH,
    /** Manifest id/versionCode/minBaseVersionCode does not match the indexed release. */
    MARKET_VERSION_MISMATCH,
    /** Manifest publisher key is missing or differs from the indexed publisher. */
    MARKET_PUBLISHER_MISMATCH,
    /** Manifest targets do not match the market entry's target package list. */
    MARKET_TARGET_MISMATCH,
    MARKET_ID_MISMATCH;

    public boolean isOk() {
        return this == OK;
    }
}
