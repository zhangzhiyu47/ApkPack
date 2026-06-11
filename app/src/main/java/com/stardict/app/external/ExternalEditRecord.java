package com.stardict.app.external;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable data model representing a single external-edit entry persisted in
 * {@link ExternalEditStore}.
 *
 * <p>The record bridges a temporary local file (used by the terminal session)
 * and the original content:// URI, tracking integrity metadata for safe
 * sync-back.</p>
 */
public final class ExternalEditRecord {

    /** Absolute path to the temporary file used by the terminal session. */
    public final String tmpPath;

    /** The original content:// URI as a string. */
    @Nullable
    public final String originalUri;

    /** 1 = in-place edit (real path), 0 = temporary copy. */
    public final int isInPlace;

    /** SHA-256 (or segmented fingerprint) at the time the copy was completed. */
    @Nullable
    public final String originalHash;

    /** File size in bytes at the time the copy was completed. */
    public final long originalSize;

    /** Last-modified time in milliseconds at the time the copy was completed. */
    public final long originalMtime;

    /** Record creation time (epoch millis), used for orphan cleanup. */
    public final long createdAt;

    public ExternalEditRecord(@NonNull String tmpPath,
                              @Nullable String originalUri,
                              int isInPlace,
                              @Nullable String originalHash,
                              long originalSize,
                              long originalMtime,
                              long createdAt) {
        this.tmpPath = tmpPath;
        this.originalUri = originalUri;
        this.isInPlace = isInPlace;
        this.originalHash = originalHash;
        this.originalSize = originalSize;
        this.originalMtime = originalMtime;
        this.createdAt = createdAt;
    }

    /** Convenience: true if this record represents an in-place (real path) edit. */
    public boolean isInPlace() {
        return isInPlace == 1;
    }
}
