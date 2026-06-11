package com.stardict.app.external;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stardict.shared.logger.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Crash-safe metadata store for external file edits.
 *
 * <p>Each record bridges a temporary local file (edited inside Termux) and its
 * original {@code content://} URI, along with integrity metadata used to decide
 * whether a sync-back is necessary.</p>
 *
 * <p>WAL mode is enabled so that large-file copies do not block readers.</p>
 */
public final class ExternalEditStore extends SQLiteOpenHelper {

    private static final String LOG_TAG = "ExternalEditStore";

    private static final String TABLE_NAME = "external_edit";

    private static final String COL_TMP_PATH      = "tmp_path";
    private static final String COL_ORIGINAL_URI  = "original_uri";
    private static final String COL_IS_IN_PLACE   = "is_in_place";
    private static final String COL_ORIGINAL_HASH = "original_hash";
    private static final String COL_ORIGINAL_SIZE = "original_size";
    private static final String COL_ORIGINAL_MTIME= "original_mtime";
    private static final String COL_CREATED_AT    = "created_at";

    private static ExternalEditStore sInstance;

    private ExternalEditStore(@NonNull Context context) {
        super(context.getApplicationContext(),
              ExternalEditConfig.DB_NAME,
              null,
              ExternalEditConfig.DB_VERSION);
    }

    /** Singleton — always use the application context to avoid leaks. */
    public static synchronized ExternalEditStore get(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new ExternalEditStore(context);
            sInstance.getWritableDatabase().enableWriteAheadLogging();
        }
        return sInstance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                + COL_TMP_PATH      + " TEXT PRIMARY KEY,"
                + COL_ORIGINAL_URI  + " TEXT,"
                + COL_IS_IN_PLACE   + " INTEGER NOT NULL DEFAULT 0,"
                + COL_ORIGINAL_HASH + " TEXT,"
                + COL_ORIGINAL_SIZE + " INTEGER NOT NULL DEFAULT 0,"
                + COL_ORIGINAL_MTIME+ " INTEGER NOT NULL DEFAULT 0,"
                + COL_CREATED_AT    + " INTEGER NOT NULL DEFAULT 0"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 -> future migrations go here
    }

    /** Insert a new record. Must be called after the copy completes and before UI is notified. */
    public synchronized void insert(@NonNull ExternalEditRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TMP_PATH,       record.tmpPath);
        cv.put(COL_ORIGINAL_URI,   record.originalUri);
        cv.put(COL_IS_IN_PLACE,    record.isInPlace);
        cv.put(COL_ORIGINAL_HASH,  record.originalHash);
        cv.put(COL_ORIGINAL_SIZE,  record.originalSize);
        cv.put(COL_ORIGINAL_MTIME, record.originalMtime);
        cv.put(COL_CREATED_AT,     record.createdAt);

        long rowId = db.insertWithOnConflict(TABLE_NAME, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
        if (rowId < 0) {
            Logger.logError(LOG_TAG, "Failed to insert record for " + record.tmpPath);
        } else {
            Logger.logDebug(LOG_TAG, "Inserted record for " + record.tmpPath
                    + " (in_place=" + record.isInPlace + ")");
        }
    }

    /** Delete a record by its temporary path. Call after successful sync-back or cleanup. */
    public synchronized void delete(@NonNull String tmpPath) {
        SQLiteDatabase db = getWritableDatabase();
        int rows = db.delete(TABLE_NAME, COL_TMP_PATH + "=?", new String[]{tmpPath});
        Logger.logDebug(LOG_TAG, "Deleted record for " + tmpPath + " (rows=" + rows + ")");
    }

    /** Return all records that are still pending sync. */
    @NonNull
    public synchronized List<ExternalEditRecord> getAllPending() {
        List<ExternalEditRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_NAME, null, null, null, null, null, null)) {
            while (c.moveToNext()) {
                list.add(cursorToRecord(c));
            }
        }
        return list;
    }

    /** Return records older than the given epoch millis — used for orphan cleanup. */
    @NonNull
    public synchronized List<ExternalEditRecord> getOlderThan(long cutoffMillis) {
        List<ExternalEditRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_NAME, null,
                COL_CREATED_AT + "<?",
                new String[]{String.valueOf(cutoffMillis)},
                null, null, null)) {
            while (c.moveToNext()) {
                list.add(cursorToRecord(c));
            }
        }
        return list;
    }

    @Nullable
    private ExternalEditRecord cursorToRecord(@NonNull Cursor c) {
        try {
            return new ExternalEditRecord(
                    c.getString(c.getColumnIndexOrThrow(COL_TMP_PATH)),
                    c.getString(c.getColumnIndexOrThrow(COL_ORIGINAL_URI)),
                    c.getInt(c.getColumnIndexOrThrow(COL_IS_IN_PLACE)),
                    c.getString(c.getColumnIndexOrThrow(COL_ORIGINAL_HASH)),
                    c.getLong(c.getColumnIndexOrThrow(COL_ORIGINAL_SIZE)),
                    c.getLong(c.getColumnIndexOrThrow(COL_ORIGINAL_MTIME)),
                    c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT))
            );
        } catch (IllegalArgumentException e) {
            Logger.logError(LOG_TAG, "Corrupt record in DB — " + e.getMessage());
            return null;
        }
    }
}
