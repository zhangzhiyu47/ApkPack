package com.stardict.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.stardict.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * Helper class for posting Android system notifications from JSON files.
 *
 * <p>Scans the {@code home/.notify/} directory for JSON files, parses each
 * file into a notification, and binds click and delete actions so that
 * the notification lifecycle stays in sync with the file system.</p>
 *
 * <p>Each JSON file must contain a single notification entry with the
 * following fields:
 * <ul>
 *   <li>{@code id}      - unique integer identifier</li>
 *   <li>{@code title}   - notification title</li>
 *   <li>{@code content} - notification body text</li>
 *   <li>{@code args}    - optional array of strings passed to the main
 *                        binary when the notification is clicked</li>
 * </ul></p>
 */
public final class NotificationHelper {

    private static final String CHANNEL_ID = "user_notifications";
    private static final String CHANNEL_NAME = "User Notifications";

    private NotificationHelper() {}

    /**
     * Creates the notification channel required on Android 8.0 (API 26) and above.
     *
     * <p>Safe to call multiple times; existing channels are not recreated.</p>
     *
     * @param ctx application or service context
     */
    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /**
     * Reads a JSON file and posts a system notification.
     *
     * <p>The JSON file is <strong>not</strong> deleted by this method.
     * Deletion is performed when the user clicks the notification or when
     * the user swipes it away (via {@link NotificationDeleteReceiver}).</p>
     *
     * <p>If the JSON is malformed, the error is logged and the method
     * returns silently so that other notifications are not affected.</p>
     *
     * @param ctx       context for building the notification
     * @param jsonFile  absolute path to the JSON file
     */
    public static void postNotificationFromJson(Context ctx, File jsonFile) {
        if (!jsonFile.exists() || !jsonFile.isFile()) return;

        try {
            byte[] b = new byte[(int) jsonFile.length()];
            try (FileInputStream fis = new FileInputStream(jsonFile)) {
                fis.read(b);
            }
            JSONObject json = new JSONObject(new String(b, "UTF-8"));

            int id = json.optInt("id", jsonFile.hashCode());
            String title = json.optString("title", "Notification");
            String content = json.optString("content", "");

            JSONArray arr = json.optJSONArray("args");
            String[] args = null;
            if (arr != null) {
                args = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) args[i] = arr.optString(i);
            }

            Intent clickIntent = new Intent(ctx, TermuxActivity.class);
            clickIntent.putExtra("notification_args", args);
            clickIntent.putExtra("notification_id", id);
            clickIntent.putExtra("notification_json_path", jsonFile.getAbsolutePath());
            clickIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent clickPi = PendingIntent.getActivity(ctx, id, clickIntent, flags);

            Intent deleteIntent = new Intent(ctx, NotificationDeleteReceiver.class);
            deleteIntent.putExtra("notification_json_path", jsonFile.getAbsolutePath());
            deleteIntent.putExtra("notification_id", id);
            PendingIntent deletePi = PendingIntent.getBroadcast(ctx, id, deleteIntent, flags);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setLargeIcon(BitmapFactory.decodeResource(ctx.getResources(),
                            R.mipmap.ic_launcher))
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .setContentIntent(clickPi)
                .setDeleteIntent(deletePi);

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(id, builder.build());

        } catch (Exception e) {
            android.util.Log.e("NotificationHelper", "Failed to post from " + jsonFile.getName(), e);
        }
    }

    /**
     * Cancels a previously posted notification.
     *
     * @param ctx context for accessing the notification manager
     * @param id  the notification identifier used when posting
     */
    public static void cancelNotification(Context ctx, int id) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }
}
