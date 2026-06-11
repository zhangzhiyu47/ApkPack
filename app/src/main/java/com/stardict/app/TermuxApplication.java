package com.stardict.app;

import android.app.Application;
import android.content.Context;

import com.stardict.BuildConfig;
import com.stardict.shared.errors.Error;
import com.stardict.shared.logger.Logger;
import com.stardict.shared.termux.TermuxBootstrap;
import com.stardict.shared.termux.TermuxConstants;
import com.stardict.shared.termux.crash.TermuxCrashUtils;
import com.stardict.shared.termux.file.TermuxFileUtils;
import com.stardict.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.stardict.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.stardict.app.external.ExternalEditBridge;
import com.stardict.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.stardict.shared.termux.shell.am.TermuxAmSocketServer;
import com.stardict.shared.termux.shell.TermuxShellManager;
import com.stardict.shared.termux.theme.TermuxThemeUtils;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);
        }

        // Recover orphan external file edits after process restart (OOM kill, crash, etc.)
        ExternalEditBridge.recoverOrphans(this);
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
