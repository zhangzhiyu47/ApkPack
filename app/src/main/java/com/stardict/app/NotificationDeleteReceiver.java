package com.stardict.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;

/**
 * Broadcast receiver invoked when the user manually dismisses a notification.
 *
 * <p>Each notification posted by {@link NotificationHelper} carries a
 * {@code deleteIntent} that targets this receiver. When the user swipes
 * the notification away, the receiver deletes the corresponding JSON file
 * so that the file system stays in sync with the notification shade.</p>
 */
public class NotificationDeleteReceiver extends BroadcastReceiver {

    /**
     * Handles the delete action.
     *
     * <p>Expects two extras in the intent:
     * <ul>
     *   <li>{@code notification_json_path} - absolute path to the JSON file</li>
     *   <li>{@code notification_id}        - notification id to cancel</li>
     * </ul></p>
     *
     * @param context application context
     * @param intent  the delete intent fired by the system
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        String path = intent.getStringExtra("notification_json_path");
        int id = intent.getIntExtra("notification_id", 0);
        if (path != null) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
        NotificationHelper.cancelNotification(context, id);
    }
}
