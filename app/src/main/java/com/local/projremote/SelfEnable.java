package com.local.projremote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps the remote switched on across restarts on firmware that forgets it.
 *
 * <p>Android itself remembers which accessibility services are on. Some projector firmwares do not: a vendor
 * system app rewrites that list with only its own service at every boot, which switches this remote off. When the
 * owner has granted this app WRITE_SECURE_SETTINGS (once, over ADB:
 * {@code adb shell pm grant com.local.projremote android.permission.WRITE_SECURE_SETTINGS}), it adds itself back
 * after boot, and checks again a few times because the vendor's rewrite can come later than ours. Firmware that
 * also keeps the boot broadcast from sideloaded apps is covered by RemoteService itself: being switched off within
 * minutes of power-on makes it call {@link #ensure}. Without the grant this class does nothing, and nothing else
 * in the app uses the permission.
 */
public class SelfEnable extends BroadcastReceiver {
    private static final String TAG = "ProjRemote";
    private static final String RECHECK = "com.local.projremote.RECHECK";
    private static final long[] RECHECK_AFTER_MS = {15_000L, 45_000L, 120_000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !RECHECK.equals(action)) return;
        if (!allowed(context)) return;
        ensure(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            for (int i = 0; i < RECHECK_AFTER_MS.length; i++) {
                PendingIntent again = PendingIntent.getBroadcast(context, i,
                        new Intent(context, SelfEnable.class).setAction(RECHECK), PendingIntent.FLAG_IMMUTABLE);
                alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RECHECK_AFTER_MS[i], again);
            }
        }
    }

    /** Whether the owner gave this app the right to switch itself on. */
    static boolean allowed(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    /** Adds the remote to the enabled accessibility services, keeping the others. True when it is (now) listed. */
    static boolean ensure(Context context) {
        if (!allowed(context)) return false;
        try {
            ComponentName me = new ComponentName(context, RemoteService.class);
            String current = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current != null) {
                for (String part : current.split(":")) {
                    if (me.equals(ComponentName.unflattenFromString(part))) return true;
                }
            }
            String next = current == null || current.isEmpty() ? me.flattenToString() : current + ":" + me.flattenToString();
            Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "remote switched back on");
            return true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "cannot switch the remote on: " + failure);
            return false;
        }
    }
}
