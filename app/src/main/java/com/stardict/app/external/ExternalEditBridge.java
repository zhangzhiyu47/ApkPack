package com.stardict.app.external;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stardict.app.TermuxActivity;
import com.stardict.shared.logger.Logger;
import com.stardict.shared.notification.NotificationUtils;
import com.stardict.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Core bridge for editing files that arrive via {@code content://} or {@code file://} URIs.
 *
 * <p>Subsystems:</p>
 * <ul>
 *   <li><b>PathResolver</b> — decides whether a real path can be used in-place or a copy is needed.</li>
 *   <li><b>AsyncCopier</b> — streams {@code content://} data to the app-private
 *       {@code /data/data/<pkg>/files/external_edit/} directory off the UI thread.</li>
 *   <li><b>IntegrityChecker</b> — SHA-256 (full or segmented) to detect real user changes.</li>
 *   <li><b>SyncTrigger</b> — writes changes back to the original URI when a session ends
 *       or the process recovers from a crash.</li>
 * </ul>
 *
 * <p>Threading: all public static methods are safe to call from any thread.
 * Callbacks are always delivered on the main looper.</p>
 */
public final class ExternalEditBridge {

    private static final String LOG_TAG = "ExternalEditBridge";

    /** Sequential executor so that multiple large-file copies do not fight for I/O. */
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExternalEditBridge");
        t.setDaemon(true);
        return t;
    });

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private ExternalEditBridge() {}

    /* ================================================================ */
    /*  Public API                                                      */
    /* ================================================================ */

    /**
     * Resolve a URI to a local filesystem path that can be passed to a terminal session.
     *
     * <p>The callback runs on the main thread and receives either:</p>
     * <ul>
     *   <li>a valid absolute path — session can be created immediately, or</li>
     *   <li>{@code null} — resolution failed; caller should show a toast and skip.</li>
     * </ul>
     */
    public static void prepare(@NonNull Context context,
                               @Nullable Uri uri,
                               @NonNull final ExternalEditCallback onReady) {
        if (uri == null) {
            sMainHandler.post(() -> onReady.onReady(null));
            return;
        }

        final Context appCtx = context.getApplicationContext();
        final String scheme = uri.getScheme();

        // file:// — validate before returning
        if ("file".equals(scheme)) {
            String path = uri.getPath();
            if (path != null && isAcceptableFilePath(path)) {
                sMainHandler.post(() -> onReady.onReady(path));
            } else {
                Logger.logWarn(LOG_TAG, "Blocked file:// URI targeting app-private path: " + path);
                sMainHandler.post(() -> onReady.onReady(null));
            }
            return;
        }

        // content:// — need async resolution
        if ("content".equals(scheme)) {
            sExecutor.execute(() -> {
                try {
                    String resolved = resolveContentUri(appCtx, uri);
                    sMainHandler.post(() -> onReady.onReady(resolved));
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to resolve content URI: " + uri + " — " + e.getMessage());
                    sMainHandler.post(() -> onReady.onReady(null));
                }
            });
            return;
        }

        // Unknown scheme
        Logger.logWarn(LOG_TAG, "Unsupported URI scheme: " + scheme);
        sMainHandler.post(() -> onReady.onReady(null));
    }

    /**
     * Sync all pending edits back to their original URIs.
     * Should be called when a session is removed / service is being destroyed.
     */
    public static void syncAll(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        sExecutor.execute(() -> {
            try {
                List<ExternalEditRecord> pending = ExternalEditStore.get(appCtx).getAllPending();
                Logger.logDebug(LOG_TAG, "syncAll: " + pending.size() + " pending record(s)");
                for (ExternalEditRecord record : pending) {
                    syncSingle(appCtx, record);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "syncAll failed — " + e.getMessage());
            }
        });
    }

    /**
     * Recover orphan edits after a process restart (OOM kill, crash, etc.).
     * Should be called from {@code Application.onCreate()}.
     */
    public static void recoverOrphans(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        sExecutor.execute(() -> {
            try {
                ExternalEditStore store = ExternalEditStore.get(appCtx);

                // 1. Sync whatever is still valid
                List<ExternalEditRecord> pending = store.getAllPending();
                Logger.logDebug(LOG_TAG, "recoverOrphans: " + pending.size() + " record(s) to check");
                for (ExternalEditRecord record : pending) {
                    syncSingle(appCtx, record);
                }

                // 2. Clean up records older than ORPHAN_MAX_AGE_DAYS
                long cutoff = System.currentTimeMillis()
                        - (long) ExternalEditConfig.ORPHAN_MAX_AGE_DAYS * 24 * 60 * 60 * 1000;
                List<ExternalEditRecord> old = store.getOlderThan(cutoff);
                for (ExternalEditRecord record : old) {
                    Logger.logDebug(LOG_TAG, "Cleaning expired orphan: " + record.tmpPath);
                    File f = new File(record.tmpPath);
                    if (f.exists()) f.delete();
                    store.delete(record.tmpPath);
                }
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "recoverOrphans failed — " + e.getMessage());
            }
        });
    }

    /* ================================================================ */
    /*  PathResolver                                                    */
    /* ================================================================ */

    /**
     * Resolve a content:// URI.
     * @return the path to use (either real path or temp copy), or null on failure.
     */
    @Nullable
    private static String resolveContentUri(@NonNull Context context, @NonNull Uri uri) {
        // 1. Try _data column (many file managers populate this)
        String candidate = queryDataColumn(context, uri);
        if (candidate != null) {
            if (isValidRealPath(candidate)) {
                Logger.logDebug(LOG_TAG, "In-place edit for real path: " + candidate);
                return candidate;
            } else {
                Logger.logDebug(LOG_TAG, "_data path rejected, will copy: " + candidate);
            }
        }

        // 2. Fall back to async copy
        return copyContentToTemp(context, uri);
    }

    /** Query ContentResolver _data column. */
    @Nullable
    private static String queryDataColumn(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) {
                    String path = c.getString(idx);
                    if (path != null && !path.isEmpty()) {
                        return path;
                    }
                }
            }
        } catch (Exception e) {
            Logger.logDebug(LOG_TAG, "_data query failed for " + uri + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Validate a file:// URI path before passing it through.
     * Blocks access to the app's own private data directory to prevent
     * information disclosure via malicious ACTION_VIEW intents.
     */
    private static boolean isAcceptableFilePath(@NonNull String path) {
        // Reject access to any app's private data directory
        if (path.startsWith("/data/data/") || path.startsWith("/data/user/")) {
            return false;
        }
        // Also reject attempts to reach app-private storage via symlinks or relative paths
        if (path.contains("/..") || path.contains("/../")) {
            return false;
        }
        return true;
    }

    /**
     * Strict validation for a candidate real path for in-place editing.
     * Must be readable, writable, and outside other apps' private data.
     */
    private static boolean isValidRealPath(@NonNull String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead() || !f.canWrite()) {
            return false;
        }
        if (!f.isFile()) {
            return false;
        }
        // Reject paths inside other apps' Android/data/
        String normalized = path;
        if (normalized.contains("/Android/data/") && !normalized.contains("/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/")) {
            return false;
        }
        // Must be on primary or secondary external storage (not in private app data)
        if (!normalized.startsWith("/storage/") && !normalized.startsWith("/sdcard/")) {
            // Allow specific known good prefixes
            if (!normalized.startsWith("/data/media/")) {
                return false;
            }
        }
        return true;
    }

    /* ================================================================ */
    /*  AsyncCopier                                                     */
    /* ================================================================ */

    /**
     * Copy a content:// stream to the app-private external_edit directory.
     * Returns the temp file path, or null on failure.
     */
    @Nullable
    private static String copyContentToTemp(@NonNull Context context, @NonNull Uri uri) {
        File editDir = new File(context.getFilesDir(), ExternalEditConfig.EDIT_DIR_NAME);
        if (!editDir.exists() && !editDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Failed to create edit directory: " + editDir);
            return null;
        }

        // Check free space
        long usable = editDir.getUsableSpace();
        long fileSize = queryFileSize(context, uri);
        if (fileSize > 0 && fileSize > usable) {
            Logger.logError(LOG_TAG, "Insufficient space: need " + fileSize + ", have " + usable);
            return null;
        }

        // Generate a safe file name
        String displayName = queryDisplayName(context, uri);
        if (displayName == null || displayName.isEmpty()) {
            displayName = "edit_" + System.currentTimeMillis();
        }
        // Sanitize: keep only alphanum, dot, dash, underscore
        displayName = displayName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (displayName.length() > 128) {
            displayName = displayName.substring(0, 128);
        }

        File tmpFile = new File(editDir, displayName);
        // Handle collision
        int suffix = 1;
        String baseName = displayName;
        String ext = "";
        int lastDot = baseName.lastIndexOf('.');
        if (lastDot > 0) {
            ext = baseName.substring(lastDot);
            baseName = baseName.substring(0, lastDot);
        }
        while (tmpFile.exists()) {
            tmpFile = new File(editDir, baseName + "_" + suffix + ext);
            suffix++;
        }

        boolean isLargeFile = fileSize > ExternalEditConfig.LARGE_FILE_NOTIFICATION_THRESHOLD_BYTES;
        if (isLargeFile) {
            showCopyNotification(context, displayName, 0, true);
        }

        // Stream copy
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmpFile)) {

            if (in == null) {
                Logger.logError(LOG_TAG, "openInputStream returned null for " + uri);
                return null;
            }

            byte[] buffer = new byte[ExternalEditConfig.COPY_BUFFER_SIZE];
            long copied = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.interrupted()) {
                    tmpFile.delete();
                    throw new InterruptedException("Copy interrupted");
                }
                out.write(buffer, 0, n);
                copied += n;

                if (isLargeFile && copied % (10 * 1024 * 1024) < ExternalEditConfig.COPY_BUFFER_SIZE) {
                    int pct = fileSize > 0 ? (int) (copied * 100 / fileSize) : -1;
                    showCopyNotification(context, displayName, pct, true);
                }
            }

            // Ensure data is on disk before we hash
            out.getFD().sync();

            if (isLargeFile) {
                showCopyNotification(context, displayName, 100, false);
            }

        } catch (InterruptedException e) {
            Logger.logDebug(LOG_TAG, "Copy interrupted, cleaned up " + tmpFile);
            tmpFile.delete();
            return null;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Copy failed for " + uri + " — " + e.getMessage());
            tmpFile.delete();
            return null;
        }

        // Compute hash and persist metadata
        String hash = computeHash(tmpFile);
        long size = tmpFile.length();
        long mtime = tmpFile.lastModified();

        ExternalEditRecord record = new ExternalEditRecord(
                tmpFile.getAbsolutePath(),
                uri.toString(),
                0, // not in-place
                hash,
                size,
                mtime,
                System.currentTimeMillis()
        );
        ExternalEditStore.get(context).insert(record);

        Logger.logDebug(LOG_TAG, "Copied to " + tmpFile + " (" + size + " bytes, hash=" + hash + ")");
        return tmpFile.getAbsolutePath();
    }

    @Nullable
    private static String queryDisplayName(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static long queryFileSize(@NonNull Context context, @NonNull Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    /* ================================================================ */
    /*  IntegrityChecker                                                */
    /* ================================================================ */

    /** Compute SHA-256 (full or segmented) of a file. Returns hex string. */
    @Nullable
    static String computeHash(@NonNull File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long size = file.length();

            if (size <= ExternalEditConfig.FULL_HASH_THRESHOLD_BYTES) {
                // Full hash
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        md.update(buf, 0, n);
                    }
                }
            } else {
                // Segmented: head + tail + size
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] head = new byte[ExternalEditConfig.SEGMENT_SAMPLE_SIZE];
                    int headRead = in.read(head);
                    if (headRead > 0) md.update(head, 0, headRead);

                    long skip = size - ExternalEditConfig.SEGMENT_SAMPLE_SIZE;
                    if (skip > headRead) {
                        long skipped = in.skip(skip - headRead);
                        if (skipped >= 0) {
                            byte[] tail = new byte[ExternalEditConfig.SEGMENT_SAMPLE_SIZE];
                            int tailRead = in.read(tail);
                            if (tailRead > 0) md.update(tail, 0, tailRead);
                        }
                    }
                }
                // Mix file size into hash to catch truncation
                md.update(String.valueOf(size).getBytes());
            }

            byte[] digest = md.digest();
            return bytesToHex(digest);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Hash computation failed for " + file + " — " + e.getMessage());
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /* ================================================================ */
    /*  SyncTrigger                                                     */
    /* ================================================================ */

    /**
     * Attempt to sync a single record back to its original URI.
     * Handles in-place (no-op), deleted, unchanged, changed, and error cases.
     */
    private static void syncSingle(@NonNull Context context, @NonNull ExternalEditRecord record) {
        ExternalEditStore store = ExternalEditStore.get(context);
        File tmpFile = new File(record.tmpPath);

        // In-place edits: nothing to sync back
        if (record.isInPlace()) {
            store.delete(record.tmpPath);
            return;
        }

        // File was deleted by user/script → clean up record
        if (!tmpFile.exists()) {
            Logger.logDebug(LOG_TAG, "Temp file gone, cleaning record: " + record.tmpPath);
            store.delete(record.tmpPath);
            return;
        }

        // No original URI → can't sync back (shouldn't happen for copy mode)
        if (record.originalUri == null) {
            Logger.logWarn(LOG_TAG, "No original URI for " + record.tmpPath + ", keeping temp file");
            return;
        }

        // Quick checks: size change means content changed
        long currentSize = tmpFile.length();
        if (currentSize != record.originalSize) {
            writeBack(context, record, tmpFile, store);
            return;
        }

        // Size same → check mtime as fast path, then hash
        long currentMtime = tmpFile.lastModified();
        if (currentMtime == record.originalMtime) {
            // File untouched → clean up
            Logger.logDebug(LOG_TAG, "File unchanged, cleaning: " + record.tmpPath);
            tmpFile.delete();
            store.delete(record.tmpPath);
            return;
        }

        // Mtime changed → compute hash for definitive answer
        String currentHash = computeHash(tmpFile);
        if (currentHash != null && currentHash.equals(record.originalHash)) {
            // Only touch-noise (e.g. touch command), no real change
            Logger.logDebug(LOG_TAG, "Hash unchanged (touch only), cleaning: " + record.tmpPath);
            tmpFile.delete();
            store.delete(record.tmpPath);
            return;
        }

        // Content actually changed → write back
        writeBack(context, record, tmpFile, store);
    }

    /**
     * Write temp file back to the original content:// URI.
     * Uses atomic write via ContentResolver.openOutputStream("wt").
     */
    private static void writeBack(@NonNull Context context,
                                  @NonNull ExternalEditRecord record,
                                  @NonNull File tmpFile,
                                  @NonNull ExternalEditStore store) {
        Uri originalUri = Uri.parse(record.originalUri);

        try {
            // "wt" = write truncating, which many FileProvider implementations support atomically
            OutputStream out = context.getContentResolver()
                    .openOutputStream(originalUri, "wt");
            if (out == null) {
                throw new RuntimeException("openOutputStream returned null");
            }

            try (FileInputStream in = new FileInputStream(tmpFile)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            out.close();

            Logger.logDebug(LOG_TAG, "Synced back to " + originalUri);
            tmpFile.delete();
            store.delete(record.tmpPath);

        } catch (SecurityException | java.io.FileNotFoundException e) {
            // URI permission expired or original file deleted → notify user
            Logger.logWarn(LOG_TAG, "Cannot sync back to " + originalUri + ": " + e.getMessage());
            notifySyncFailed(context, tmpFile, originalUri);
            store.delete(record.tmpPath); // keep temp file, just remove DB record
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Unexpected sync failure for " + originalUri + " — " + e.getMessage());
            notifySyncFailed(context, tmpFile, originalUri);
            store.delete(record.tmpPath); // keep temp file, remove DB record to avoid infinite retry
        }
    }

    /* ================================================================ */
    /*  Notifications                                                   */
    /* ================================================================ */

    /** Show or update a progress notification for large-file copies. */
    private static void showCopyNotification(@NonNull Context context,
                                              @NonNull String fileName,
                                              int percent,
                                              boolean ongoing) {
        NotificationManager nm = NotificationUtils.getNotificationManager(context);
        if (nm == null) return;

        NotificationUtils.setupNotificationChannel(context,
                ExternalEditConfig.NOTIFICATION_CHANNEL_ID,
                "External File Edit",
                NotificationManager.IMPORTANCE_LOW);

        String text = percent >= 0
                ? "Preparing " + fileName + " (" + percent + "%)"
                : "Preparing " + fileName + "…";

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(
                context,
                ExternalEditConfig.NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_LOW,
                "External File Edit",
                text,
                null,
                null, null,
                NotificationUtils.NOTIFICATION_MODE_SILENT);

        if (builder == null) return;
        builder.setSmallIcon(android.R.drawable.ic_menu_save);
        builder.setOngoing(ongoing);
        if (!ongoing) {
            builder.setAutoCancel(true);
        }

        nm.notify(ExternalEditConfig.NOTIFICATION_ID, builder.build());

        if (!ongoing) {
            // Dismiss after a short delay
            sMainHandler.postDelayed(() -> nm.cancel(ExternalEditConfig.NOTIFICATION_ID), 3000);
        }
    }

    /** Notify the user that sync-back failed and the temp file is retained. */
    private static void notifySyncFailed(@NonNull Context context,
                                          @NonNull File retainedFile,
                                          @NonNull Uri originalUri) {
        NotificationManager nm = NotificationUtils.getNotificationManager(context);
        if (nm == null) return;

        NotificationUtils.setupNotificationChannel(context,
                ExternalEditConfig.NOTIFICATION_CHANNEL_ID,
                "External File Edit",
                NotificationManager.IMPORTANCE_HIGH);

        String fileName = retainedFile.getName();
        String content = "Original: " + originalUri.toString()
                + "\nSaved to: " + retainedFile.getAbsolutePath();

        // Tap notification → open TermuxActivity
        Intent intent = TermuxActivity.newInstance(context);
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(
                context,
                ExternalEditConfig.NOTIFICATION_CHANNEL_ID,
                Notification.PRIORITY_HIGH,
                "External file not synced: " + fileName,
                "Tap to open Termux. Use cp to copy the file back manually.",
                content,
                pi, null,
                NotificationUtils.NOTIFICATION_MODE_ALL);

        if (builder == null) return;
        builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
        builder.setAutoCancel(true);

        int id = ExternalEditConfig.NOTIFICATION_ID + retainedFile.getName().hashCode();
        nm.notify(id, builder.build());
    }
}
