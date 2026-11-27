package com.adhook.config;

/** Immutable app-side acknowledgement only; does not claim any host has consumed the policy. */
public final class RemotePublicationState {
    public final long desiredVersion;
    public final long publishedVersion;
    public final String error;
    public final int attempts;

    RemotePublicationState(long desiredVersion, long publishedVersion, String error, int attempts) {
        this.desiredVersion = desiredVersion;
        this.publishedVersion = publishedVersion;
        this.error = error;
        this.attempts = attempts;
    }

    public boolean isConfirmed() {
        return desiredVersion > 0L && desiredVersion == publishedVersion && error == null;
    }

    RemotePublicationState desired(long version) {
        return version <= desiredVersion ? this
                : new RemotePublicationState(version, publishedVersion, "PENDING", 0);
    }

    RemotePublicationState result(long version, boolean confirmed, String failure, int attemptCount) {
        if (version != desiredVersion) return this;
        return new RemotePublicationState(desiredVersion,
                confirmed ? version : publishedVersion, confirmed ? null : failure, attemptCount);
    }
}
