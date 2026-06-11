package com.stardict.app.external;

/**
 * Configuration constants for the ExternalEditBridge subsystem.
 *
 * <p>All thresholds, directory names, and algorithm parameters are centralized here
 * so that they can be tuned without touching business logic.</p>
 */
public final class ExternalEditConfig {

    private ExternalEditConfig() {}

    /**
     * Sub-directory under {@code getFilesDir()} where temporary copies are stored.
     * The resolved absolute path will be {@code /data/data/<pkg>/files/external_edit/}.
     */
    public static final String EDIT_DIR_NAME = "external_edit";

    /** Files <= this size (bytes) get a full SHA-256. 10 MB. */
    public static final long FULL_HASH_THRESHOLD_BYTES = 10 * 1024 * 1024;

    /** Number of bytes sampled from head and tail for segmented hash. 8 KB. */
    public static final int SEGMENT_SAMPLE_SIZE = 8 * 1024;

    /** Buffer size used by AsyncCopier. 64 KB. */
    public static final int COPY_BUFFER_SIZE = 64 * 1024;

    /** If a file exceeds this size during copy, show a progress notification. 100 MB. */
    public static final long LARGE_FILE_NOTIFICATION_THRESHOLD_BYTES = 100 * 1024 * 1024;

    /** SQLite database name. */
    public static final String DB_NAME = "external_edit.db";

    /** SQLite database version. */
    public static final int DB_VERSION = 1;

    /** Orphan records older than this (days) are auto-cleaned on recovery. */
    public static final int ORPHAN_MAX_AGE_DAYS = 7;

    /** Notification channel ID for large-file copy progress. */
    public static final String NOTIFICATION_CHANNEL_ID = "external_edit_progress";

    /** Notification ID for large-file copy progress. */
    public static final int NOTIFICATION_ID = 2001;
}
