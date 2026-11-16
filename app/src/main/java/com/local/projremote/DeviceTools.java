package com.local.projremote;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;

/**
 * Picture and input shortcuts that only some firmwares have, offered to the phone only where they exist:
 * <ul>
 * <li>autocorrect: the camera-based focus and keystone run of Joyar's dispatcher (the projector's test pattern);</li>
 * <li>keystone, zoom: the Siviton system menu's Projection page, then its Four Point Keystone or Zoom item;</li>
 * <li>inputs: MediaTek's Source panel (HDMI, USB...).</li>
 * </ul>
 */
final class DeviceTools {
    private static final ComponentName DISPATCHER =
            new ComponentName("com.joyar.dispatcher", "com.joyar.dispatcher.DispatcherService");
    private static final ComponentName SYSTEM_MENU =
            new ComponentName("com.siviton.systemui", "com.siviton.systemui.SystemUIService");
    private static final ComponentName INPUTS =
            new ComponentName("com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.tvcenter.nav.inputpanel.InputsPanelActivity");

    private static JSONArray available;

    static synchronized JSONArray available(Context c) {
        if (available == null) {
            available = new JSONArray();
            if (service(c, DISPATCHER)) available.put("autocorrect");
            if (service(c, SYSTEM_MENU)) available.put("keystone").put("zoom");
            if (c.getPackageManager().resolveActivity(new Intent().setComponent(INPUTS), 0) != null) available.put("inputs");
        }
        return available;
    }

    private static boolean service(Context c, ComponentName cn) {
        return c.getPackageManager().resolveService(new Intent().setComponent(cn), 0) != null;
    }

    static boolean run(RemoteService s, String name) {
        boolean listed = false;
        JSONArray all = available(s);
        for (int i = 0; i < all.length(); i++) listed |= name.equals(all.optString(i));
        if (!listed) return false;
        try {
            switch (name) {
                case "autocorrect":
                    s.startService(new Intent().setComponent(DISPATCHER).putExtra("action", "focus_camera"));
                    return true;
                case "keystone":
                case "zoom":
                    // Page 2 of the system menu is Projection; the item is then clicked by its words (English).
                    s.startService(new Intent().setComponent(SYSTEM_MENU)
                            .putExtra("extra_system_ui", "menu_joyar").putExtra("extra_menu_parameter", "2"));
                    s.clickTextSoon(name.equals("zoom") ? "Zoom" : "Four Point Keystone");
                    return true;
                case "inputs":
                    s.startActivity(new Intent().setComponent(INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                default:
                    return false;
            }
        } catch (RuntimeException refused) {
            return false;
        }
    }
}
