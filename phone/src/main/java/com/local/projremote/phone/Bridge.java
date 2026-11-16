package com.local.projremote.phone;

import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

/**
 * What the remote page can ask of this app (window.PhoneRemoteApp). Its storage lives here rather than in the
 * WebView, where it would be tied to the device's address and lost when the router hands out a new one; the page
 * keys it by the device's fixed id.
 */
final class Bridge {
    private final MainActivity app;
    private final SharedPreferences store;

    Bridge(MainActivity app, SharedPreferences store) {
        this.app = app;
        this.store = store;
    }

    @JavascriptInterface public String get(String key) {
        return store.getString(key, null);
    }

    @JavascriptInterface public void set(String key, String value) {
        store.edit().putString(key, value).apply();
    }

    @JavascriptInterface public void del(String key) {
        store.edit().remove(key).apply();
    }

    /** The page loaded: this device is the one to open next time. */
    @JavascriptInterface public void here(String id, String name, String origin) {
        app.runOnUiThread(() -> app.remember(id, name, origin));
    }

    @JavascriptInterface public boolean canListen() {
        return app.canListen();
    }

    /** Speech to text with the phone's own recognizer; the words come back through window.appHeard(text). */
    @JavascriptInterface public void listen() {
        app.runOnUiThread(app::listen);
    }
}
