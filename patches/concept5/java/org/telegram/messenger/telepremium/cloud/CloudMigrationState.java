package org.telegram.messenger.telepremium.cloud;

public final class CloudMigrationState {
    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String CHANNEL_READY = "CHANNEL_READY";
    public static final String PAYLOAD_COPY = "PAYLOAD_COPY";
    public static final String SNAPSHOT_WRITTEN = "SNAPSHOT_WRITTEN";
    public static final String VERIFIED = "VERIFIED";
    public static final String SELF_CLEANUP = "SELF_CLEANUP";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";

    private CloudMigrationState() {}
}
