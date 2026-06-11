package com.stardict.app.external;

import androidx.annotation.Nullable;

/**
 * Callback for asynchronous external file resolution in {@link ExternalEditBridge#prepare}.
 *
 * <p>Called on the main thread with either a resolved absolute path or {@code null} on failure.</p>
 */
@FunctionalInterface
public interface ExternalEditCallback {
    /** Called when path resolution completes. */
    void onReady(@Nullable String resolvedPath);
}
