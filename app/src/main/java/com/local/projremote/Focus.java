package com.local.projremote;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Lens focus on projectors with a focus motor driven by Joyar's dispatcher (the firmware of many low-cost
 * projectors, PixPaq and other Siviton/MediaTek models): its own remote's focus keys start this service with a
 * motor command, and so does this. Elsewhere {@link #supported} is false and the phone shows no focus buttons.
 */
final class Focus {
    private static final String TAG = "ProjRemote";
    private static final ComponentName DISPATCHER =
            new ComponentName("com.joyar.dispatcher", "com.joyar.dispatcher.DispatcherService");
    private static final int STOP = 0, NEAR = 1, FAR = 2;
    /** How long one press turns the motor; the phone repeats a held key every 140 ms, so holding keeps it going. */
    private static final long NUDGE_MS = 180;

    private static Boolean supported;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static synchronized boolean supported(Context c) {
        if (supported == null) {
            Intent probe = new Intent().setComponent(DISPATCHER);
            supported = c.getPackageManager().resolveService(probe, 0) != null;
        }
        return supported;
    }

    /** One step of the motor (+ = "Focus +" on the PixPaq remote); held, the steps run together. */
    static boolean move(Context c, boolean plus) {
        if (!supported(c)) return false;
        main.removeCallbacks(stopper);
        if (!motor(c, plus ? FAR : NEAR)) return false;
        appContext = c.getApplicationContext();
        main.postDelayed(stopper, NUDGE_MS);
        return true;
    }

    static boolean auto(Context c) {
        if (!supported(c)) return false;
        return start(c, new Intent().setComponent(DISPATCHER).putExtra("action", "focus"));
    }

    private static Context appContext;
    private static final Runnable stopper = () -> { if (appContext != null) motor(appContext, STOP); };

    private static boolean motor(Context c, int command) {
        return start(c, new Intent().setComponent(DISPATCHER).putExtra("inputExtra", "motorCtrl").putExtra("motorCtrl", command));
    }

    private static boolean start(Context c, Intent intent) {
        try {
            return c.startService(intent) != null;
        } catch (RuntimeException background) {
            // Not allowed to start it from the background on this Android: as a foreground service it is.
            try {
                return c.startForegroundService(intent) != null;
            } catch (RuntimeException refused) {
                Log.w(TAG, "focus: " + refused);
                return false;
            }
        }
    }
}
