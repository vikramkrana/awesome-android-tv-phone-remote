package com.local.projremote;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets this app see which app is playing media (MediaSessionManager asks for an enabled
 * notification listener). It does not read, keep or send notifications.
 *
 * <p>It also starts at power-on on firmware that keeps sideloaded apps from starting then, so it checks the remote
 * itself came up and wakes it if not.
 */
public class MediaListener extends NotificationListenerService {
    private static final long[] CHECK_AFTER_MS = {4_000L, 15_000L, 45_000L};

    @Override
    public void onListenerConnected() {
        Handler main = new Handler(Looper.getMainLooper());
        for (long delay : CHECK_AFTER_MS) {
            main.postDelayed(() -> {
                SelfEnable.ensure(getApplicationContext());
                SelfEnable.rebindIfStuck(getApplicationContext());
            }, delay);
        }
    }
}
